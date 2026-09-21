package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent.Kind;
import com.raaspal.robotrecommendation.telemetry.repository.RobotFaultEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records AutoXing robot faults as they happen, so there is a history to report on.
 *
 * <p>AutoXing only ever says what is wrong <em>now</em>; nothing in its API returns a past
 * fault. Each poll reads the whole fleet in one call and compares it with the faults
 * already open in {@code robot_fault_event}: a fault that appeared is opened, one that
 * disappeared is closed. A fault that simply stays active writes nothing, so a 10-second
 * interval costs one AutoXing call and, most of the time, zero database writes.
 *
 * <p>What a poll can and cannot conclude:
 * <ul>
 *   <li>A robot that is <b>offline</b> reports nothing reliable, so its open errors are left
 *       as they are - neither closed nor extended - and an OFFLINE row opens instead.</li>
 *   <li>A robot <b>missing</b> from the list entirely is skipped, never closed: absence from a
 *       response is not evidence that a fault cleared.</li>
 *   <li>A <b>failed</b> call changes nothing. The next poll starts from the same open rows.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoxingFaultPollService {

    public static final String BRAND = "AUTOXING";

    private final AutoxingApiClient apiClient;
    private final AutoxingReportService reportService;
    private final RobotFaultEventRepository faults;

    /** Error code -> the robot's message for it. Codes mean the same thing on every robot. */
    private final Map<Integer, Message> messages = new ConcurrentHashMap<>();

    private int failureStreak;

    record Message(String text, Integer level) {
    }

    /** One robot as the fleet call reports it. */
    record RobotSnapshot(String robotId, String businessId, boolean online, boolean emergencyStop,
                         List<Integer> errorCodes) {

        static RobotSnapshot of(JsonNode n) {
            List<Integer> codes = new ArrayList<>();
            n.path("errors").forEach(c -> {
                if (c.canConvertToInt()) codes.add(c.asInt());
                else if (c.has("code")) codes.add(c.path("code").asInt());
            });
            boolean online = n.has("isOnLine") ? n.path("isOnLine").asBoolean(false)
                    : n.path("isOnline").asBoolean(false);
            return new RobotSnapshot(
                    n.path("robotId").asText(null),
                    blankToNull(n.path("businessId").asText(null)),
                    online,
                    n.path("isEmergencyStop").asBoolean(false),
                    codes);
        }
    }

    /** Identity of one open fault: which robot, what kind, which code. */
    record Key(String robotId, Kind kind, Integer code) {
        static Key of(RobotFaultEvent e) {
            return new Key(e.getRobotId(), e.getKind(), e.getErrorCode());
        }
    }

    /** What one poll should change. */
    record Plan(List<Key> toOpen, List<Key> toClose, Map<String, String> businessByRobot) {
    }

    /** What one poll changed, for the log. */
    public record PollResult(int robots, int online, int opened, int closed) {
    }

    /** One poll. Returns null when AutoXing could not be read (nothing was changed). */
    public PollResult poll() {
        if (!apiClient.isConfigured()) {
            return null;
        }
        JsonNode fleet;
        try {
            fleet = reportService.withReauth(apiClient::getRobotFleet);
            if (failureStreak > 0) {
                log.info("AutoXing fault poll recovered after {} failed poll(s)", failureStreak);
            }
            failureStreak = 0;
        } catch (Exception e) {
            // Logged once per outage, not every 10 seconds.
            if (failureStreak++ == 0) {
                log.warn("AutoXing fault poll failed; no faults recorded until it recovers: {}", e.getMessage());
            }
            return null;
        }

        List<RobotSnapshot> robots = new ArrayList<>();
        fleet.forEach(n -> {
            RobotSnapshot s = RobotSnapshot.of(n);
            if (s.robotId() != null) robots.add(s);
        });

        Map<Key, RobotFaultEvent> open = new HashMap<>();
        faults.findByBrandAndClearedAtIsNull(BRAND).forEach(e -> open.put(Key.of(e), e));

        Plan plan = plan(open.keySet(), robots);
        Instant now = Instant.now();

        int closed = 0;
        for (Key key : plan.toClose()) {
            RobotFaultEvent e = open.get(key);
            e.setClearedAt(now);
            faults.save(e);
            closed++;
        }

        int opened = 0;
        for (Key key : plan.toOpen()) {
            Message m = key.kind() == Kind.ERROR ? message(key.robotId(), key.code()) : null;
            try {
                faults.save(RobotFaultEvent.builder()
                        .brand(BRAND)
                        .robotId(key.robotId())
                        .businessId(plan.businessByRobot().get(key.robotId()))
                        .kind(key.kind())
                        .errorCode(key.code())
                        .errorLevel(m == null ? null : m.level())
                        .message(m == null ? null : m.text())
                        .firstSeenAt(now)
                        .build());
                opened++;
            } catch (DataIntegrityViolationException dup) {
                // uq_robot_fault_event_open: another poller opened it first. Not an error.
                log.debug("Fault {} already open elsewhere", key);
            }
        }

        int online = (int) robots.stream().filter(RobotSnapshot::online).count();
        if (opened > 0 || closed > 0) {
            log.info("AutoXing fault poll: {} robots ({} online) - {} fault(s) opened, {} cleared",
                    robots.size(), online, opened, closed);
        }
        return new PollResult(robots.size(), online, opened, closed);
    }

    /**
     * The change between what is open and what the fleet reports now. Pure, so the rules
     * above are tested without AutoXing or a database.
     */
    static Plan plan(Set<Key> open, List<RobotSnapshot> robots) {
        List<Key> toOpen = new ArrayList<>();
        List<Key> toClose = new ArrayList<>();
        Map<String, String> business = new HashMap<>();

        for (RobotSnapshot r : robots) {
            business.put(r.robotId(), r.businessId());
            Key offline = new Key(r.robotId(), Kind.OFFLINE, null);

            if (!r.online()) {
                // Unobservable while offline: leave its errors alone, record the outage.
                if (!open.contains(offline)) toOpen.add(offline);
                continue;
            }
            if (open.contains(offline)) toClose.add(offline);

            Set<Key> now = new HashSet<>();
            for (Integer code : r.errorCodes()) now.add(new Key(r.robotId(), Kind.ERROR, code));
            if (r.emergencyStop()) now.add(new Key(r.robotId(), Kind.EMERGENCY_STOP, null));

            for (Key k : now) {
                if (!open.contains(k)) toOpen.add(k);
            }
            for (Key k : open) {
                if (k.robotId().equals(r.robotId()) && k.kind() != Kind.OFFLINE && !now.contains(k)) {
                    toClose.add(k);
                }
            }
        }
        return new Plan(toOpen, toClose, business);
    }

    /**
     * The text for an error code, read once from one robot's live state and then cached -
     * the fleet call carries codes only. A failure leaves the message blank rather than
     * delaying the row: the code alone is still worth recording.
     */
    private Message message(String robotId, Integer code) {
        Message cached = messages.get(code);
        if (cached != null) return cached;
        try {
            JsonNode state = reportService.withReauth(token -> apiClient.getRobotState(robotId, token));
            for (JsonNode err : state.path("errors")) {
                if (err.has("code")) {
                    messages.putIfAbsent(err.path("code").asInt(), new Message(
                            blankToNull(err.path("message").asText(null)),
                            err.hasNonNull("level") ? err.path("level").asInt() : null));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the message for error {} on {}: {}", code, robotId, e.getMessage());
        }
        return messages.get(code);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
