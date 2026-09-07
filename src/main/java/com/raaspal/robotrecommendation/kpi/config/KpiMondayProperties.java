package com.raaspal.robotrecommendation.kpi.config;

import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
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

    /** Days after a ticket closes within which a new ticket for the same serial counts as a repeat. */
    private int repeatWindowDays = 7;

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
            if (board.getServiceLine() == null) {
                throw new IllegalStateException(
                        "app.kpi.monday.boards[" + i + "].service-line is required (CLEANING or DELIVERY)");
            }
            if (!seen.add(board.getId())) {
                throw new IllegalStateException("app.kpi.monday.boards lists board " + board.getId() + " twice");
            }
            if (board.getSlaDaysMetro() <= 0 || board.getSlaDaysUpcountry() <= 0) {
                throw new IllegalStateException("app.kpi.monday.boards[" + i + "] SLA days must be positive");
            }
        }
        if (repeatWindowDays < 0) {
            throw new IllegalStateException("app.kpi.monday.repeat-window-days must not be negative");
        }
    }

    @Getter
    @Setter
    public static class Board {

        private String id;
        private ServiceLine serviceLine;

        /** Empty means every group on the board. */
        private List<String> groupIds = new ArrayList<>();

        /** Status texts that mean the case is finished, for boards without a close-date column. */
        private List<String> closedStatuses = new ArrayList<>();

        /**
         * SLA in calendar days. Cleaning is 3 everywhere; Delivery is 3 in greater
         * Bangkok and 5 elsewhere (confirmed with the RE team, 2026-08-27/28). A
         * case at exactly the limit is still within SLA.
         */
        private int slaDaysMetro = 3;
        private int slaDaysUpcountry = 5;

        private Columns columns = new Columns();

        /** True when {@code status} is one of the configured finished statuses (case-insensitive). */
        public boolean isClosedStatus(String status) {
            if (status == null) {
                return false;
            }
            String needle = status.strip();
            return closedStatuses.stream().anyMatch(s -> s.strip().equalsIgnoreCase(needle));
        }
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
    }
}
