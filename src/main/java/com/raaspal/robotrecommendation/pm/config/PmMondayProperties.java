package com.raaspal.robotrecommendation.pm.config;

import com.raaspal.robotrecommendation.pm.entity.PmServiceLine;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Board and column mapping for the monday PM boards.
 *
 * <p>Column ids are configuration, not constants, because they are meaningless
 * strings that differ per board - the province cell is {@code text_mkmx2ccz} on
 * PM Cleaning and {@code text_mkmxnkw5} on PM Delivery - and anyone with edit
 * rights can add a column that shifts them. Keeping them in properties means a
 * renamed board column is a config change, not a release.
 *
 * <p>Unlike the rest of this codebase, which reads single values with
 * {@code @Value}, this binds a list of nested objects. {@code @Value} cannot do
 * that, and forty separately-named properties would lose the per-board grouping
 * that makes a wrong mapping obvious.
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.pm.monday")
public class PmMondayProperties {

    /** Whether the scheduled sync runs. The manual endpoint works regardless. */
    private boolean syncEnabled = false;

    private String syncCron = "0 30 2 * * *";

    private String syncZone = "Asia/Bangkok";

    /**
     * Items per API page. Higher than the case-report reader's 50 on purpose: the
     * Cleaning subitem board alone holds about 2,800 rows, so small pages turn one
     * sync into dozens of calls against a daily API budget.
     */
    private int pageSize = 250;

    /** Stops a cursor that never terminates. 200 pages is far beyond any board here. */
    private int maxPages = 200;

    private List<Board> boards = new ArrayList<>();

    @Getter
    @Setter
    public static class Board {

        /** Parent board id, e.g. 2048972900 (PM Cleaning). */
        private String id;

        private PmServiceLine serviceLine;

        /** Board holding this board's subitems - where the actual visits live. */
        private String subitemBoardId;

        private Columns columns = new Columns();

        private SubitemColumns subitemColumns = new SubitemColumns();

        /** Every parent column id that must be requested from monday. */
        public List<String> parentColumnIds() {
            List<String> ids = new ArrayList<>();
            addIfSet(ids, columns.project);
            addIfSet(ids, columns.customerName);
            addIfSet(ids, columns.province);
            addIfSet(ids, columns.region);
            addIfSet(ids, columns.district);
            addIfSet(ids, columns.location);
            addIfSet(ids, columns.contractType);
            addIfSet(ids, columns.warrantyTimeline);
            addIfSet(ids, columns.robotModel);
            addIfSet(ids, columns.robotCount);
            columns.serialColumnIds().forEach(id -> addIfSet(ids, id));
            return ids;
        }

        public List<String> subitemColumnIds() {
            List<String> ids = new ArrayList<>();
            addIfSet(ids, subitemColumns.planDate);
            addIfSet(ids, subitemColumns.actionDate);
            addIfSet(ids, subitemColumns.status);
            addIfSet(ids, subitemColumns.owner);
            addIfSet(ids, subitemColumns.time);
            addIfSet(ids, subitemColumns.jobNo);
            return ids;
        }

        private static void addIfSet(List<String> target, String id) {
            if (id != null && !id.isBlank() && !target.contains(id)) {
                target.add(id);
            }
        }
    }

    @Getter
    @Setter
    public static class Columns {
        private String project;
        private String customerName;
        private String province;
        private String region;
        private String district;
        private String location;
        private String contractType;
        private String warrantyTimeline;
        private String robotModel;
        private String robotCount;
        /** Comma-separated; the boards spread serials over up to five columns. */
        private String serials;

        public List<String> serialColumnIds() {
            if (serials == null || serials.isBlank()) {
                return List.of();
            }
            return List.of(serials.split("\\s*,\\s*"));
        }
    }

    @Getter
    @Setter
    public static class SubitemColumns {
        private String planDate;
        private String actionDate;
        private String status;
        private String owner;
        private String time;
        private String jobNo;
    }

    /**
     * Rejects a mapping that cannot work, at startup rather than at 2:30am.
     *
     * <p>A board with no subitem board or no plan-date column would sync
     * successfully and produce an empty planner, which is the failure mode
     * hardest to notice.
     */
    @PostConstruct
    void validate() {
        for (int i = 0; i < boards.size(); i++) {
            Board board = boards.get(i);
            String where = "app.pm.monday.boards[" + i + "]";
            require(board.getId(), where + ".id");
            require(board.getSubitemBoardId(), where + ".subitem-board-id");
            require(board.getSubitemColumns().getPlanDate(), where + ".subitem-columns.plan-date");
            if (board.getServiceLine() == null) {
                throw new IllegalStateException(where + ".service-line is required (CLEANING or DELIVERY)");
            }
        }
        if (boards.isEmpty()) {
            log.warn("No monday PM boards configured (app.pm.monday.boards); the PM planner will have no data");
        } else {
            log.info("Configured {} monday PM board(s): {}", boards.size(),
                    boards.stream().map(b -> b.getServiceLine() + "=" + b.getId()).toList());
        }
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
    }
}
