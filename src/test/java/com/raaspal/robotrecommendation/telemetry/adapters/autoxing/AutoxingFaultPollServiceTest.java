package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFaultPollService.Key;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFaultPollService.Plan;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFaultPollService.RobotSnapshot;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingFaultSummary;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent.Kind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutoxingFaultPollServiceTest {

    private static RobotSnapshot online(String id, boolean estop, Integer... codes) {
        return new RobotSnapshot(id, "biz", true, estop, List.of(codes));
    }

    private static RobotSnapshot offline(String id, Integer... codes) {
        return new RobotSnapshot(id, "biz", false, false, List.of(codes));
    }

    @Test
    void fleetResponseParsesCodesAndTheOddlyCasedOnlineFlag() throws Exception {
        RobotSnapshot s = RobotSnapshot.of(new ObjectMapper().readTree("""
                {"robotId":"R1","businessId":"b1","isOnLine":true,"isEmergencyStop":true,"errors":[9504,2008]}"""));
        assertThat(s.online()).isTrue();
        assertThat(s.emergencyStop()).isTrue();
        assertThat(s.errorCodes()).containsExactly(9504, 2008);
    }

    @Test
    void newFaultsOpenAndClearedFaultsClose() {
        Set<Key> open = Set.of(
                new Key("R1", Kind.ERROR, 2008),          // cleared now
                new Key("R1", Kind.ERROR, 9504));         // still active
        Plan plan = AutoxingFaultPollService.plan(open, List.of(online("R1", true, 9504, 4013)));

        assertThat(plan.toOpen()).containsExactlyInAnyOrder(
                new Key("R1", Kind.ERROR, 4013),
                new Key("R1", Kind.EMERGENCY_STOP, null));
        assertThat(plan.toClose()).containsExactly(new Key("R1", Kind.ERROR, 2008));
    }

    @Test
    void anOfflineRobotKeepsItsErrorsAndOpensAnOutage() {
        Set<Key> open = Set.of(new Key("R1", Kind.ERROR, 9504));
        Plan plan = AutoxingFaultPollService.plan(open, List.of(offline("R1")));

        assertThat(plan.toOpen()).containsExactly(new Key("R1", Kind.OFFLINE, null));
        assertThat(plan.toClose()).isEmpty();
    }

    @Test
    void comingBackOnlineClosesTheOutage() {
        Set<Key> open = Set.of(new Key("R1", Kind.OFFLINE, null), new Key("R1", Kind.ERROR, 9504));
        Plan plan = AutoxingFaultPollService.plan(open, List.of(online("R1", false, 9504)));

        assertThat(plan.toClose()).containsExactly(new Key("R1", Kind.OFFLINE, null));
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void aRobotMissingFromTheResponseIsLeftAlone() {
        Set<Key> open = Set.of(new Key("GONE", Kind.ERROR, 9504));
        Plan plan = AutoxingFaultPollService.plan(open, List.of(online("R1", false)));

        assertThat(plan.toClose()).isEmpty();
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void summaryClipsDurationsToThePeriodAndFlagsPartialCoverage() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        Instant end = Instant.parse("2026-10-01T00:00:00Z");
        Instant now = Instant.parse("2026-09-21T12:00:00Z");
        List<RobotFaultEvent> rows = List.of(
                fault(Kind.ERROR, 2008, "Wheel is major slipping", "2026-09-10T10:00:00Z", "2026-09-10T10:30:00Z"),
                fault(Kind.ERROR, 2008, "Wheel is major slipping", "2026-09-12T08:00:00Z", "2026-09-12T08:10:00Z"),
                fault(Kind.ERROR, 9504, "V2X firmware version too low", "2026-09-21T11:00:00Z", null),
                fault(Kind.EMERGENCY_STOP, null, null, "2026-09-15T09:00:00Z", "2026-09-15T09:05:00Z"));

        AutoxingFaultSummary s = AutoxingFaultQueryService.summarise(
                rows, Instant.parse("2026-09-05T00:00:00Z"), start, end, now);

        assertThat(s.partialPeriod()).isTrue();
        assertThat(s.errorOccurrences()).isEqualTo(3);
        assertThat(s.errors().get(0).code()).isEqualTo(2008);
        assertThat(s.errors().get(0).occurrences()).isEqualTo(2);
        assertThat(s.errors().get(0).activeSeconds()).isEqualTo(40 * 60);
        // still open: runs to "now", not to the end of the month
        assertThat(s.errors().get(1).activeSeconds()).isEqualTo(3600);
        assertThat(s.errors().get(1).activeNow()).isTrue();
        assertThat(s.emergencyStops()).isEqualTo(1);
        assertThat(s.emergencyStopSeconds()).isEqualTo(300);
    }

    private static RobotFaultEvent fault(Kind kind, Integer code, String message, String from, String to) {
        return RobotFaultEvent.builder()
                .brand("AUTOXING").robotId("R1").kind(kind).errorCode(code).message(message)
                .firstSeenAt(Instant.parse(from))
                .clearedAt(to == null ? null : Instant.parse(to))
                .build();
    }
}
