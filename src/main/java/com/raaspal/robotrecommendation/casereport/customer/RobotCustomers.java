package com.raaspal.robotrecommendation.casereport.customer;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whose a pending case is, read from the robot it is about rather than from what someone
 * typed on the ticket. The "Location raaspal robot" board lists every robot with its serial
 * number and site, and the site's name carries the customer ("IFS : …", "MK …"), so a case
 * whose ticket never says IFS is still IFS's when its robot is at an IFS site.
 *
 * <p>A case is matched by its robot's serial number first - exact, upper-cased, spaces
 * removed. Failing that, by its site: the ticket's branch must contain one of a robot's
 * site names whole, or the other way round; words in common are not enough ("Bangkok" and
 * "Hospital" are on too many sites). A match counts only when every robot found points
 * at one customer. Anything else is left to the ticket's own text, which the console still
 * reads (lib/caseCustomerViews.ts).
 *
 * <p>The board is read every few hours and kept in memory: 1,089 robots, three pages of
 * one board. If monday cannot be read, the last copy stands; before the first, nothing is
 * matched and every case keeps its text rule.
 */
@Slf4j
@Service
public class RobotCustomers {

    /** The customers the pending tabs are cut by, spelled as the console spells them. */
    public static final String MK = "MK";
    public static final String MAKRO = "Makro";
    public static final String PCS = "PCS";
    public static final String IFS = "IFS";

    /** How a case was matched to its robot. */
    public enum Via { SERIAL, SITE }

    /** @param customer one of {@link #MK}, {@link #MAKRO}, {@link #PCS}, {@link #IFS} */
    public record Match(String customer, Via via) {
    }

    /** A case to match: its key in the console's list, and what its ticket says. */
    public record CaseRef(String key, String serialNumber, String project, String branch) {
    }

    /** One robot on the board, as far as matching needs it. */
    record Robot(String customer, Set<String> serials, Set<String> sites) {
    }

    record Directory(List<Robot> robots, Map<String, List<Robot>> bySerial, Instant loadedAt) {

        static final Directory EMPTY = new Directory(List.of(), Map.of(), null);
    }

    /** Whole words, as the console matches them: "PCS : Makro" yes, "PCSX" no. */
    private static final Map<String, Pattern> CUSTOMER_WORDS = new LinkedHashMap<>();

    static {
        CUSTOMER_WORDS.put(IFS, Pattern.compile("(^|[^a-z])ifs([^a-z]|$)", Pattern.CASE_INSENSITIVE));
        CUSTOMER_WORDS.put(PCS, Pattern.compile("(^|[^a-z])pcs([^a-z]|$)", Pattern.CASE_INSENSITIVE));
        // The board writes "Makro", "MaKro", "Makroหาดใหญ่": a part of a word is enough.
        CUSTOMER_WORDS.put(MAKRO, Pattern.compile("makro", Pattern.CASE_INSENSITIVE));
        // MK Restaurant Group runs Yayoi and Bonus Suki too: one customer.
        CUSTOMER_WORDS.put(MK, Pattern.compile("(^|[^a-z])(mk|yayoi|bonus ?suki)([^a-z]|$)", Pattern.CASE_INSENSITIVE));
    }

    /** Words in a site's name that say nothing about which site it is. */
    private static final Pattern NOT_SITE = Pattern.compile("สาขา|โครงการ|branch", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("[\\s:,.()\\-/#_]+");
    /** Shorter than this, a site name matches too much ("BKK", "สาขา 1"). */
    static final int MIN_SITE = 8;
    static final Duration MAX_AGE = Duration.ofHours(6);

    private static final String C_SERIAL = "text_mm5wyq2f";      // S/N
    private static final String C_BRANCH = "dropdown_mm5w19h";   // สาขา/โครงการ
    private static final String C_LOCATION = "text_mm5wbbke";    // Location

    private final MondayBoardReader boards;
    private final String boardId;
    private final String groupId;
    private volatile Directory directory = Directory.EMPTY;

    public RobotCustomers(MondayBoardReader boards,
                          @Value("${app.casereport.robot-board.id:18424839088}") String boardId,
                          @Value("${app.casereport.robot-board.group:group_mm5xap8j}") String groupId) {
        this.boards = boards;
        this.boardId = boardId;
        this.groupId = groupId;
    }

    /** Whether the robot list has been read at least once. */
    public boolean available() {
        return current().loadedAt() != null;
    }

    /** Each case that matched a robot, by its key; a case that did not is left out. */
    public Map<String, Match> match(List<CaseRef> cases) {
        Directory d = current();
        Map<String, Match> out = new HashMap<>();
        for (CaseRef c : cases) {
            if (c == null || c.key() == null) continue;
            Match m = match(d, c);
            if (m != null) out.put(c.key(), m);
        }
        return out;
    }

    /**
     * Each row's customer from the robot list, as a lookup; null when the robot is not on it.
     *
     * @param loadedOnly use only the list already read, never monday - for a public link,
     *                   whose visitor must not wait on monday or set off a read of it. The
     *                   list is kept warm (RobotListWarmer), so it is there in practice.
     */
    public java.util.function.Function<CaseRef, Match> matcher(boolean loadedOnly) {
        Directory d = loadedOnly ? directory : current();
        return c -> c == null ? null : match(d, c);
    }

    /** Reads the robot list again if it is older than {@link #MAX_AGE}. */
    public void refresh() {
        current();
    }

    private Directory current() {
        Directory d = directory;
        if (d.loadedAt() != null && d.loadedAt().isAfter(Instant.now().minus(MAX_AGE))) return d;
        synchronized (this) {
            d = directory;
            if (d.loadedAt() != null && d.loadedAt().isAfter(Instant.now().minus(MAX_AGE))) return d;
            try {
                List<MondayItem> items = boards.readGroup(boardId, groupId, List.of(C_SERIAL, C_BRANCH, C_LOCATION), false)
                        .items();
                directory = build(items.stream()
                        .map(i -> new RobotRow(i.name(), i.columnText(C_SERIAL), i.columnText(C_LOCATION),
                                i.columnText(C_BRANCH)))
                        .toList(), Instant.now());
                log.info("Robot list read: {} robots, {} with a pending-tab customer",
                        items.size(), directory.robots().size());
            } catch (RuntimeException e) {
                // The cases keep their text rule; the next request tries again.
                log.warn("Could not read the robot list from monday: {}", e.getMessage());
            }
            return directory;
        }
    }

    /** A robot as the board gives it. */
    record RobotRow(String name, String serials, String location, String branch) {
    }

    /** The robots that belong to a pending-tab customer, indexed by serial. */
    static Directory build(List<RobotRow> rows, Instant at) {
        List<Robot> robots = new ArrayList<>();
        Map<String, List<Robot>> bySerial = new HashMap<>();
        for (RobotRow r : rows) {
            String customer = customerOf(String.join(" ", nz(r.name()), nz(r.location()), nz(r.branch())));
            if (customer == null) continue;
            Set<String> sites = new LinkedHashSet<>();
            for (String s : List.of(nz(r.name()), nz(r.location()), nz(r.branch()))) {
                String site = site(s);
                if (site.length() >= MIN_SITE) sites.add(site);
            }
            Robot robot = new Robot(customer, serials(r.serials()), sites);
            robots.add(robot);
            for (String s : robot.serials()) bySerial.computeIfAbsent(s, k -> new ArrayList<>()).add(robot);
        }
        return new Directory(List.copyOf(robots), Map.copyOf(bySerial), at);
    }

    static Match match(Directory d, CaseRef c) {
        Set<String> bySerial = serials(c.serialNumber()).stream()
                .flatMap(s -> d.bySerial().getOrDefault(s, List.of()).stream())
                .map(Robot::customer)
                .collect(Collectors.toSet());
        if (bySerial.size() == 1) return new Match(bySerial.iterator().next(), Via.SERIAL);
        if (bySerial.size() > 1) return null;
        Set<String> mine = new LinkedHashSet<>();
        for (String s : List.of(nz(c.branch()), nz(c.project()))) {
            String site = site(s);
            if (site.length() >= MIN_SITE) mine.add(site);
        }
        if (mine.isEmpty()) return null;
        Set<String> bySite = d.robots().stream()
                .filter(r -> r.sites().stream().anyMatch(a -> mine.stream().anyMatch(b -> a.contains(b) || b.contains(a))))
                .map(Robot::customer)
                .collect(Collectors.toSet());
        return bySite.size() == 1 ? new Match(bySite.iterator().next(), Via.SITE) : null;
    }

    /**
     * The one pending-tab customer a text names, or null for none or several. "PCS" and
     * "Makro" together are Makro: Makro is PCS's customer, so its cases are PCS's anyway.
     */
    static String customerOf(String text) {
        Set<String> hits = new LinkedHashSet<>();
        CUSTOMER_WORDS.forEach((customer, words) -> {
            if (words.matcher(text).find()) hits.add(customer);
        });
        if (hits.equals(Set.of(PCS, MAKRO))) return MAKRO;
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    /** A site's name to compare: lower case, without customer words, "สาขา" or punctuation. */
    static String site(String text) {
        String s = nz(text).toLowerCase(Locale.ROOT);
        for (Pattern words : CUSTOMER_WORDS.values()) s = words.matcher(s).replaceAll(" ");
        s = NOT_SITE.matcher(s).replaceAll(" ");
        return SEPARATORS.matcher(s).replaceAll(" ").trim();
    }

    /** A cell's serial numbers: several may share one cell. Upper-cased, spaces removed. */
    static Set<String> serials(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : nz(raw).split("[,/;\\n]+")) {
            String s = part.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
