package com.raaspal.robotrecommendation.casereport.aotsheet;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the AOT sheet is and which of its columns mean what.
 *
 * <p>Columns are named by header text. The descriptive ones default to the headers the
 * sheet carried on 2026-09-28. The three that decide identity and closure -
 * {@link #rowIdHeader}, {@link #statusHeader} and {@link #closeDateHeader} - default to
 * blank, because nobody has yet confirmed which columns they are, and a wrong guess
 * there does real damage: the wrong id merges two cases into one, and a missing closure
 * rule reads every case since 2023 as still open.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.googlesheet.aot")
public class AotSheetProperties {

    /**
     * Whether the sync may write. Off by default, and deliberately separate from having
     * a sheet configured: the preview reads the same sheet and writes nothing, so the
     * mapping can be checked against the real sheet before anything reaches the
     * database - which, on a dev machine with {@code DB_URL} set, is production's.
     */
    private boolean syncEnabled = false;

    /** The long id between {@code /d/} and {@code /edit} in the sheet's URL. */
    private String spreadsheetId = "";

    /** The tab's name, exactly as its label shows. */
    private String tab = "Case";

    /** The header's row number as the sheet shows it. */
    private int headerRow = 1;

    /**
     * A column whose value names one case and never changes. Row position is not one:
     * sorting or inserting rows moves every case after it.
     */
    private String rowIdHeader = "";

    /** A column holding the case's status. Pairs with {@link #closedStatuses}. */
    private String statusHeader = "";

    /** Values of {@link #statusHeader} that mean closed, matched ignoring case. */
    private List<String> closedStatuses = new ArrayList<>();

    /** A column holding the date a case closed; any date there means closed. */
    private String closeDateHeader = "";

    private String openDateHeader = "Issue Date";
    private String ticketNoHeader = "AOT Ticket no.";
    private String serialHeader = "S/N";
    private String modelHeader = "Model";
    private String siteHeader = "Location";
    private String problemHeader = "Problem details";
    private String requestedPartHeader = "Request for Spare part";
    private String verifyHeader = "RE RAAS Verify Issue";
    private String repairByHeader = "repair By";

    public boolean isConfigured() {
        return !isBlank(spreadsheetId);
    }

    /** What still has to be configured before the sync may write; empty when ready. */
    public List<String> notReadyForSync() {
        List<String> problems = new ArrayList<>();
        if (isBlank(spreadsheetId)) {
            problems.add("no sheet linked");
        }
        if (isBlank(rowIdHeader)) {
            problems.add("no case id column chosen");
        }
        if (isBlank(statusHeader) && isBlank(closeDateHeader)) {
            problems.add("no way to tell a closed case: choose a status column and its closed values, "
                    + "or a close-date column");
        }
        if (!isBlank(statusHeader) && closedStatuses.stream().allMatch(AotSheetProperties::isBlank)) {
            problems.add("a status column but no values that mean closed");
        }
        return problems;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
