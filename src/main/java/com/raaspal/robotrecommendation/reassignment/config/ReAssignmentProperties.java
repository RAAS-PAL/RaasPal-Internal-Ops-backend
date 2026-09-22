package com.raaspal.robotrecommendation.reassignment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * The operating rules for RE assignment, {@code app.re-assignment.*}.
 *
 * <p>Board column ids, which groups count as open work, how heavy each status is, and
 * which Issue Level needs which skill level. All of it is policy the Senior RE may want to
 * tune, so it is configuration rather than code. Weighted lists are written as
 * {@code Label=weight} pairs because a properties key cannot carry the spaces in monday
 * labels such as "Working on it".
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.re-assignment")
public class ReAssignmentProperties {

    /** Cleaning Tickets. */
    private String boardId = "3451717331";

    private String boardType = "CLEANING";

    /** e.g. https://raaspal.monday.com - for the "open in monday" link; blank hides it. */
    private String mondayWebUrl = "";

    private Columns columns = new Columns();

    /** Groups whose tickets are open work - suggested for and counted as load. */
    private List<String> activeGroups = new ArrayList<>(List.of(
            "All Case", "Check", "AOTGA", "AOTGA 30 Credit cases", "Makro Project"));

    /** Statuses that mean finished. */
    private List<String> closedStatuses = new ArrayList<>(List.of("Done"));

    /**
     * "Type of case" values that are not corrective maintenance. Everything else -
     * including a blank type, which is a third of the board - counts as CM, because the
     * Cleaning Tickets board is the CM board.
     */
    private List<String> nonCmCaseTypes = new ArrayList<>(List.of(
            "Request case", "Traning", "Training", "Research", "Planning", "รับอะไหล่", "ส่งอะไหล่"));

    /** Queue order, most urgent first. Types not listed come after these. */
    private List<String> urgencyOrder = new ArrayList<>(List.of("Urgent", "Incident case", "Service case"));

    /**
     * Load one open ticket puts on each person in its RE column, by status. Waiting states
     * are light: one person holds 68 tickets in Check today, and counting those as full
     * work would make everyone look busy.
     */
    private List<String> statusLoad = new ArrayList<>(List.of(
            "New=1", "Working on it=1", "Manday=1", "Waiting for the schedule to fix=1",
            "Pending=0.5", "On Hold=0.25", "Check=0.25", "Follow up=0.25", "Waiting for review=0.25",
            "No Feedback=0.1"));

    /** Load for a status not listed above. */
    private BigDecimal defaultLoad = new BigDecimal("0.5");

    /** Load a newly assigned ticket adds. */
    private BigDecimal newTicketLoad = BigDecimal.ONE;

    /** Minimum model level AND CM level per Issue Level (1-4 = L1-L4). */
    private List<String> requiredLevels = new ArrayList<>(List.of("L1-Easy=2", "L2-Mid=3", "L3-Hard=4"));

    /**
     * Difficulty assumed when Issue Level is blank (a third of tickets). The suggestion is
     * flagged so the Senior RE sees the assumption before approving.
     */
    private String assumedIssueLevel = "L2-Mid";

    /**
     * "Online / On Site" labels that take the engineer out for the day: someone on an open
     * ticket of this kind is busy on its "RE Action" date. Online work does not block a day.
     */
    private List<String> busyServiceModes = new ArrayList<>(List.of("On Site"));

    /**
     * Zones an engineer can be based in, each with the place names that put a ticket there.
     * The Cleaning board has no province column, so a ticket's zone is found by looking for
     * these words in its name, branch and project (case, spaces and punctuation ignored).
     */
    private Map<String, List<String>> zones = new LinkedHashMap<>(Map.of(
            "EASTERN_SEABOARD", List.of(
                    "ชลบุรี", "Chonburi", "Chon Buri", "ศรีราชา", "Sriracha", "Si Racha", "พัทยา", "Pattaya",
                    "บางแสน", "Bangsaen", "แหลมฉบัง", "Laem Chabang", "อมตะ", "Amata", "ปลวกแดง", "Pluak Daeng",
                    "บ่อวิน", "Bowin", "สัตหีบ", "Sattahip", "อู่ตะเภา", "U-Tapao", "บางพระ", "หนองมน",
                    "ระยอง", "Rayong", "มาบตาพุด", "Map Ta Phut", "บ้านฉาง", "ฉะเชิงเทรา", "Chachoengsao",
                    "บางปะกง", "Bang Pakong", "Eastern Seaboard", "อีสเทิร์นซีบอร์ด")));

    private Email email = new Email();

    private MondayWrite mondayWrite = new MondayWrite();

    private Refresh refresh = new Refresh();

    @Getter
    @Setter
    public static class Columns {
        private String people = "people3";
        private String model = "status_17";
        private String issueLevel = "status_1";
        private String caseType = "color_mkyj4ncq";
        private String serviceMode = "color_mktj24tx";
        private String status = "status";
        private String subStatus = "status7";
        private String serial = "text0";
        private String customer = "asset_owner3__1";
        private String branch = "text6";
        private String mainIssue = "text";
        private String openDate = "date8";
        private String actionDate = "date_1";

        public List<String> all() {
            return List.of(people, model, issueLevel, caseType, serviceMode, status, subStatus,
                    serial, customer, branch, mainIssue, openDate, actionDate);
        }
    }

    @Getter
    @Setter
    public static class Email {
        /**
         * Off by default: local development shares the production database and has real
         * SMTP settings, so a test approval must not email a real engineer.
         */
        private boolean enabled = false;

        /** When set, every assignment email goes here instead of to the engineer (for testing). */
        private String redirectTo = "";
    }

    @Getter
    @Setter
    public static class MondayWrite {
        /**
         * Approving puts the engineer into the ticket's RE column (and cancelling takes them
         * out again). Off = approvals are only recorded and the Senior RE sets monday by hand.
         */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Refresh {
        /** Scheduled refresh from monday. The console's Refresh button works regardless. */
        private boolean enabled = false;

        private String cron = "0 */10 7-20 * * *";

        private String zone = "Asia/Bangkok";
    }

    /* ─── Parsed views ────────────────────────────────────────────────────── */

    public Map<String, BigDecimal> statusLoadMap() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String pair : statusLoad) {
            int eq = pair.lastIndexOf('=');
            if (eq > 0) out.put(norm(pair.substring(0, eq)), new BigDecimal(pair.substring(eq + 1).trim()));
        }
        return out;
    }

    public Map<String, Integer> requiredLevelMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String pair : requiredLevels) {
            int eq = pair.lastIndexOf('=');
            if (eq > 0) out.put(norm(pair.substring(0, eq)), Integer.parseInt(pair.substring(eq + 1).trim()));
        }
        return out;
    }

    /** The first zone whose place names appear in any of the texts, or null when none does. */
    public String zoneOf(String... texts) {
        StringBuilder hay = new StringBuilder();
        for (String t : texts) if (t != null) hay.append(squash(t)).append('|');
        for (Map.Entry<String, List<String>> zone : zones.entrySet()) {
            for (String place : zone.getValue()) {
                String needle = squash(place);
                if (!needle.isEmpty() && hay.indexOf(needle) >= 0) return zone.getKey();
            }
        }
        return null;
    }

    /** Lowercase, without spaces or punctuation: "Laem Chabang" and "laemchabang" meet. */
    static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    public static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
