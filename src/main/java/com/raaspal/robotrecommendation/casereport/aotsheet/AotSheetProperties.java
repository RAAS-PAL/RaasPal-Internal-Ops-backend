package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * The column whose cell colour is read; blank means {@link #statusHeader}, which is
     * highlighted with the rest of the row.
     */
    private String colourHeader = "";

    /**
     * Background colours that mean closed, {@code #rrggbb}. When set, a row whose status
     * says closed but whose colour does not is fixed and waiting for its part: still
     * pending, on hold.
     */
    private List<String> closedColours = new ArrayList<>();

    /**
     * Names the team gives the colours that do not mean closed - "Waiting for AOT" for
     * yellow - shown on the tracker's rows in that colour. RAASPAL's reading of the
     * colour only: the sheet has no such column, and nothing is written back to it.
     */
    private List<ColourLabel> colourLabels = new ArrayList<>();

    /**
     * @param colour  {@code #rrggbb}
     * @param label   the team's name for it; null when ignored
     * @param ignored the colour means nothing (someone's highlight): its rows read as if
     *                uncoloured - their stage from the sheet's columns - and show no name
     */
    public record ColourLabel(String colour, String label, boolean ignored) {

        @JsonCreator
        public ColourLabel {
        }

        public ColourLabel(String colour, String label) {
            this(colour, label, false);
        }

        public static ColourLabel ignore(String colour) {
            return new ColourLabel(colour, null, true);
        }
    }

    private String openDateHeader = "Issue Date";
    private String ticketNoHeader = "AOT Ticket no.";
    private String serialHeader = "S/N";
    private String modelHeader = "Model";
    private String siteHeader = "Location";
    private String problemHeader = "Problem details";
    private String requestedPartHeader = "Request for Spare part";
    private String verifyHeader = "RE RAAS Verify Issue";
    private String repairByHeader = "repair By";
    private String partReceivedHeader = "Spare Part Received";
    private String waitingHeader = "Waiting";

    /**
     * Words in {@link #requestedPartHeader} that mean AOT asks RAASPAL to look into the case
     * further rather than for a part - "รบกวน RAAS PAL ตรวจสอบเพิ่มเติม". Such a case has two
     * steps of its own, by colour: pending RAASPAL's review while white, closed once blue.
     */
    private List<String> reviewWords = new ArrayList<>(List.of("ตรวจสอบเพิ่มเติม"));

    public boolean isConfigured() {
        return !isBlank(spreadsheetId);
    }

    /**
     * Whether a "Request for Spare part" cell asks RAASPAL to review the case: it holds one of
     * {@link #reviewWords}, matched ignoring spaces and case, as the sheet spaces Thai freely.
     */
    public boolean asksForReview(String requestedPart) {
        if (isBlank(requestedPart)) return false;
        String cell = squeeze(requestedPart);
        return reviewWords.stream().filter(w -> !isBlank(w)).anyMatch(w -> cell.contains(squeeze(w)));
    }

    private static String squeeze(String text) {
        return text.replaceAll("[\\s\\u00a0\\u200b]+", "").toLowerCase(Locale.ROOT);
    }

    /** The column whose colour is read: {@link #colourHeader}, or else the status column. */
    public String colourColumn() {
        return isBlank(colourHeader) ? statusHeader : colourHeader;
    }

    /** Whether closure is judged by colour: closed colours chosen, and a column to read them from. */
    public boolean readsColour() {
        return closedColours.stream().anyMatch(c -> !isBlank(c)) && !isBlank(colourColumn());
    }

    /**
     * Whether a closed case can be told apart: a status column with its closed values, a
     * close-date column, or closed colours with a column to read them from.
     */
    public boolean closureConfigured() {
        boolean byStatus = !isBlank(statusHeader) && closedStatuses.stream().anyMatch(c -> !isBlank(c));
        return byStatus || !isBlank(closeDateHeader) || readsColour();
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
        boolean coloursChosen = closedColours.stream().anyMatch(c -> !isBlank(c));
        if (isBlank(statusHeader) && isBlank(closeDateHeader) && !coloursChosen) {
            problems.add("no way to tell a closed case: choose a status column and its closed values, "
                    + "a close-date column, or the colours that mean closed");
        }
        if (coloursChosen && isBlank(colourColumn())) {
            problems.add("colours that mean closed but no column to read the colour from");
        }
        // With colours, the status alone never closes a case, so its values may stay empty.
        if (!isBlank(statusHeader) && !coloursChosen
                && closedStatuses.stream().allMatch(AotSheetProperties::isBlank)) {
            problems.add("a status column but no values that mean closed");
        }
        return problems;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
