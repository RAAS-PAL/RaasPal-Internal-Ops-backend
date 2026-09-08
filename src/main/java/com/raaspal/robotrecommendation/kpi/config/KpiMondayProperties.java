package com.raaspal.robotrecommendation.kpi.config;

import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which monday boards feed the RE KPI dashboard, and which column on each board
 * means what. See the {@code app.kpi.monday.*} block in
 * {@code application.properties} for the reasoning behind every field.
 *
 * <p>Column ids are configuration rather than constants because they collide
 * across boards with different meanings ({@code text} is Main Issue on Cleaning
 * but Solution on Delivery) and because they can be confirmed and corrected
 * from the console via {@code GET /api/v1/kpi/monday/boards/{id}} without a
 * deploy.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kpi.monday")
public class KpiMondayProperties {

    private boolean syncEnabled;
    private String syncCron;
    private String syncZone = "Asia/Bangkok";

    /**
     * First Time Fix: a CM followed by another CM naming the same serial within
     * this many days scores zero. RE team's figure, 2026-09-08.
     */
    private int repeatWindowDays = 14;

    /**
     * 1st Time Install: an installation followed by a CM naming the same serial
     * within this many days of the install finishing scores zero. RE team's
     * figure, 2026-09-08.
     */
    private int installFollowUpDays = 30;

    private List<Board> boards = new ArrayList<>();

    /** The board config for a board id, if it is one of the synced boards. */
    public Optional<Board> board(String boardId) {
        return boards.stream().filter(b -> b.getId().equals(boardId)).findFirst();
    }

    /**
     * A half-configured board would sync rows with no service line and
     * silently vanish from every split, so the context refuses to start instead.
     */
    @PostConstruct
    void validate() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < boards.size(); i++) {
            Board board = boards.get(i);
            if (board.getId() == null || board.getId().isBlank()) {
                throw new IllegalStateException("app.kpi.monday.boards[" + i + "].id is required");
            }
            // A CM board must state its line: those boards are single-line, and a
            // CM ticket is the evidence that classifies everything else, so an
            // unstated line there would leave the whole split empty.
            //
            // An INSTALLATION board may state neither. Its tickets are classified by
            // serial instead — the same robot's serial appears on a CM board, which
            // does know — so requiring a column it does not have would be wrong.
            if (board.getTicketType() == TicketType.CM
                    && board.getServiceLine() == null && isBlank(board.getServiceLineColumn())) {
                throw new IllegalStateException("app.kpi.monday.boards[" + i
                        + "] is a CM board and needs service-line (CLEANING or DELIVERY) or service-line-column");
            }
            if (board.getTicketType() == null) {
                throw new IllegalStateException(
                        "app.kpi.monday.boards[" + i + "].ticket-type is required (CM or INSTALLATION)");
            }
            if (!seen.add(board.getId())) {
                throw new IllegalStateException("app.kpi.monday.boards lists board " + board.getId() + " twice");
            }
            if (board.getSlaDays() <= 0) {
                throw new IllegalStateException("app.kpi.monday.boards[" + i + "].sla-days must be positive");
            }
        }
        if (repeatWindowDays < 0) {
            throw new IllegalStateException("app.kpi.monday.repeat-window-days must not be negative");
        }
        if (installFollowUpDays < 0) {
            throw new IllegalStateException("app.kpi.monday.install-follow-up-days must not be negative");
        }
    }

    @Getter
    @Setter
    public static class Board {

        private String id;

        /** Stated when every ticket on the board is one line; else set serviceLineColumn. */
        private ServiceLine serviceLine;

        /**
         * Column whose text says which robot type the ticket is about, for a board
         * carrying both. The Installation board has no such column outright — its
         * "Robot  Models" column names models — so the match is by keyword rather
         * than by the words "cleaning" and "delivery" appearing literally.
         */
        private String serviceLineColumn;

        /**
         * Case-insensitive substrings of {@link #serviceLineColumn}'s text that mean
         * a cleaning robot. Model names belong here for a board that only names
         * models. Checked before {@link #deliveryKeywords}.
         */
        private List<String> cleaningKeywords = new ArrayList<>(List.of("clean"));

        /** As above, for delivery robots. */
        private List<String> deliveryKeywords = new ArrayList<>(List.of("deliver"));

        private TicketType ticketType = TicketType.CM;

        /** Empty means every group on the board. */
        private List<String> groupIds = new ArrayList<>();

        /** Status texts that mean the case is finished, for boards without a close-date column. */
        private List<String> closedStatuses = new ArrayList<>();

        /**
         * SLA in calendar days: the case must be <em>checked</em> (the board's RE
         * Action date) within this many days of being reported. 7 for both ticket
         * boards — RE team, 2026-09-08. Exactly the limit is still within SLA.
         *
         * <p>This replaces an earlier 3-metro/5-upcountry reading taken from the
         * daily-report feature; that threshold measured something else (time to
         * close a pending case) and does not apply here.
         */
        private int slaDays = 7;

        private Columns columns = new Columns();

        /**
         * Which kind of robot a ticket is about: the board's own line when it has
         * one, else read out of {@link #serviceLineColumn} by keyword.
         *
         * <p>Returns null when nothing matches. That is deliberate — see
         * {@code V40__allow_unclassified_service_line.sql}. Guessing would inflate
         * one side of every split with an error that looks exactly like data.
         */
        public ServiceLine resolveServiceLine(String columnText) {
            if (serviceLine != null) {
                return serviceLine;
            }
            if (columnText == null || columnText.isBlank()) {
                return null;
            }
            String text = columnText.toLowerCase(java.util.Locale.ROOT);
            if (matchesAny(text, cleaningKeywords)) {
                return ServiceLine.CLEANING;
            }
            if (matchesAny(text, deliveryKeywords)) {
                return ServiceLine.DELIVERY;
            }
            return null;
        }

        private static boolean matchesAny(String text, List<String> keywords) {
            return keywords.stream()
                    .map(k -> k.strip().toLowerCase(java.util.Locale.ROOT))
                    .filter(k -> !k.isEmpty())
                    .anyMatch(text::contains);
        }

        /** True when {@code status} is one of the configured finished statuses (case-insensitive). */
        public boolean isClosedStatus(String status) {
            if (status == null) {
                return false;
            }
            String needle = status.strip();
            return closedStatuses.stream().anyMatch(s -> s.strip().equalsIgnoreCase(needle));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** monday column ids per field. Null = not mapped on this board; the field stays null. */
    @Getter
    @Setter
    public static class Columns {
        private String ticketNo;
        private String project;
        private String branch;
        private String branchCode;
        private String province;
        private String serial;
        private String robotModel;
        private String status;
        private String supStatus;
        private String issueLevel;
        private String mainIssue;
        private String openDate;
        private String closeDate;
        /** The board's "RE Action" date — the SLA clock's second hand. */
        private String actionDate;
        /** Installation boards: a TimeLine (or date) column; the LATER date is used. */
        private String installDate;
    }
}
