package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetApiClient;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the AOT sheet is and how to read it, as staff set it in the console.
 *
 * <p>A saved row (V61) wins over the {@code app.googlesheet.aot.*} properties, which stay as
 * the fallback for a server nobody has configured from the console yet. The service-account
 * key is never part of this: it is a secret and stays in the environment.
 */
@Service
@RequiredArgsConstructor
public class AotSheetSettingsService {

    /**
     * The id in {@code https://docs.google.com/spreadsheets/d/<id>/edit#gid=0}. A browser signed
     * into several Google accounts puts {@code /u/1} before the {@code /d/}.
     */
    private static final Pattern ID_IN_URL =
            Pattern.compile("/spreadsheets/(?:u/\\d+/)?d/([A-Za-z0-9_-]{20,})");
    /** A bare id pasted on its own. */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BARE_ID = Pattern.compile("[A-Za-z0-9_-]{20,}");

    private final AotSheetProperties properties;
    private final CaseSourceSheetRepository repository;
    private final GoogleSheetApiClient client;

    /** What the console edits. Blank strings mean "not set". */
    public record SettingsRequest(String sheetUrl,
                                  String tab,
                                  Integer headerRow,
                                  String rowIdHeader,
                                  String statusHeader,
                                  String closedStatuses,
                                  String closeDateHeader,
                                  String colourHeader,
                                  String closedColours,
                                  List<AotSheetProperties.ColourLabel> colourLabels,
                                  String openDateHeader,
                                  Boolean syncEnabled) {
    }

    /**
     * @param savedInConsole false while the properties are all there is
     * @param shareWith      the address the sheet must be shared with; null without a key
     */
    public record SettingsView(String sheetUrl,
                               String spreadsheetId,
                               String tab,
                               int headerRow,
                               String rowIdHeader,
                               String statusHeader,
                               String closedStatuses,
                               String closeDateHeader,
                               String colourHeader,
                               String closedColours,
                               List<AotSheetProperties.ColourLabel> colourLabels,
                               String openDateHeader,
                               boolean syncEnabled,
                               boolean savedInConsole,
                               boolean credentialsConfigured,
                               String shareWith,
                               List<String> notReadyForSync,
                               String updatedBy,
                               OffsetDateTime updatedAt) {
    }

    /** The configuration every read and sync uses. */
    @Transactional(readOnly = true)
    public AotSheetProperties effective() {
        return repository.findBySourceKey(CaseSourceSheet.AOT)
                .map(this::overlay)
                .orElse(properties);
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        CaseSourceSheet saved = repository.findBySourceKey(CaseSourceSheet.AOT).orElse(null);
        AotSheetProperties p = saved == null ? properties : overlay(saved);
        return new SettingsView(
                saved == null ? urlFor(p.getSpreadsheetId()) : saved.getSheetUrl(),
                blankToNull(p.getSpreadsheetId()),
                p.getTab(),
                p.getHeaderRow(),
                blankToNull(p.getRowIdHeader()),
                blankToNull(p.getStatusHeader()),
                p.getClosedStatuses().isEmpty() ? null : String.join(", ", p.getClosedStatuses()),
                blankToNull(p.getCloseDateHeader()),
                blankToNull(p.getColourHeader()),
                p.getClosedColours().isEmpty() ? null : String.join(", ", p.getClosedColours()),
                p.getColourLabels(),
                blankToNull(p.getOpenDateHeader()),
                p.isSyncEnabled(),
                saved != null,
                client.isConfigured(),
                client.serviceAccountEmail(),
                p.notReadyForSync(),
                saved == null ? null : saved.getUpdatedBy(),
                saved == null ? null : saved.getUpdatedAt());
    }

    @Transactional
    public SettingsView save(SettingsRequest request, String user) {
        String url = trim(request.sheetUrl());
        if (url == null) {
            throw new BadRequestException("Paste the Google Sheet's link.");
        }
        String spreadsheetId = spreadsheetIdFrom(url);

        CaseSourceSheet row = repository.findBySourceKey(CaseSourceSheet.AOT)
                .orElseGet(() -> CaseSourceSheet.builder().sourceKey(CaseSourceSheet.AOT).build());
        row.setSheetUrl(url);
        row.setSpreadsheetId(spreadsheetId);
        row.setTab(trim(request.tab()) == null ? "Case" : trim(request.tab()));
        row.setHeaderRow(request.headerRow() == null || request.headerRow() < 1 ? 1 : request.headerRow());
        row.setRowIdHeader(trim(request.rowIdHeader()));
        row.setStatusHeader(trim(request.statusHeader()));
        row.setClosedStatuses(trim(request.closedStatuses()));
        row.setCloseDateHeader(trim(request.closeDateHeader()));
        row.setColourHeader(trim(request.colourHeader()));
        row.setClosedColours(closedColours(request.closedColours()));

        row.setOpenDateHeader(trim(request.openDateHeader()));
        row.setColourLabels(toJson(colourLabels(request.colourLabels(), row.getClosedColours())));
        row.setUpdatedBy(user);
        row.setUpdatedAt(OffsetDateTime.now());

        boolean wantsSync = Boolean.TRUE.equals(request.syncEnabled());
        if (wantsSync) {
            // Refused here rather than at 06:15 tomorrow: a switch that is on but cannot
            // run looks like it is working until somebody reads the log.
            List<String> missing = overlay(row).notReadyForSync();
            if (!missing.isEmpty()) {
                throw new BadRequestException("Cannot turn the sync on yet: " + String.join("; ", missing)
                        + ". Save without the sync first, then turn it on once these are set.");
            }
        }
        row.setSyncEnabled(wantsSync);

        repository.save(row);
        return view();
    }

    /**
     * Unlinks the sheet: the saved row goes, and the daily sync with it. The cases already
     * synced stay in {@code case_ticket} under the old sheet's id, but nothing lists them,
     * because every reader asks for the sheet linked now.
     *
     * <p>If the server's own properties name a sheet, that one is linked again - the same
     * fallback as before anything was saved here.
     */
    @Transactional
    public SettingsView remove() {
        repository.findBySourceKey(CaseSourceSheet.AOT).ifPresent(repository::delete);
        return view();
    }

    /**
     * The closed colours as stored: {@code #rrggbb}, lower case, comma-separated, each once.
     * Anything that is not a colour is refused rather than dropped, so a typo is seen when
     * saving instead of as a case that never closes.
     */
    static String closedColours(String value) {
        String text = trim(value);
        if (text == null) return null;
        Set<String> colours = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            if (part.isBlank()) continue;
            String colour = AotSheetRowMapper.normaliseColour(part);
            if (colour == null) {
                throw new BadRequestException("'" + part.trim() + "' is not a colour. Use the #rrggbb form, "
                        + "for example #c9daf8, or pick it from the colours Test connection lists.");
            }
            colours.add(colour);
        }
        return colours.isEmpty() ? null : String.join(", ", colours);
    }

    /** Longest name a colour may be given: it shows in a table cell. */
    static final int LABEL_MAX = 60;

    /**
     * The colour names worth keeping: each colour once, as {@code #rrggbb}, names trimmed.
     * A colour that means the old part is back keeps no name - its stage says it - and a
     * blank name is no name. A colour marked as meaning nothing is kept as ignored.
     */
    static List<AotSheetProperties.ColourLabel> colourLabels(List<AotSheetProperties.ColourLabel> labels,
                                                             String closedColours) {
        if (labels == null) return List.of();
        Set<String> closed = closedColours == null ? Set.of() : Set.of(closedColours.split(",\\s*"));
        Map<String, AotSheetProperties.ColourLabel> out = new LinkedHashMap<>();
        for (AotSheetProperties.ColourLabel l : labels) {
            if (l == null) continue;
            String colour = AotSheetRowMapper.normaliseColour(l.colour());
            if (colour == null) {
                throw new BadRequestException("'" + l.colour() + "' is not a colour. Pick it from the colours the sheet uses.");
            }
            if (closed.contains(colour)) continue;
            if (l.ignored()) {
                out.put(colour, AotSheetProperties.ColourLabel.ignore(colour));
                continue;
            }
            String label = trim(l.label());
            if (label == null) continue;
            if (label.length() > LABEL_MAX) {
                throw new BadRequestException("Keep a colour's name to " + LABEL_MAX + " characters: '" + label + "'.");
            }
            out.put(colour, new AotSheetProperties.ColourLabel(colour, label));
        }
        return List.copyOf(out.values());
    }

    private static String toJson(List<AotSheetProperties.ColourLabel> labels) {
        if (labels.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(labels);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the colour names", e);
        }
    }

    private static List<AotSheetProperties.ColourLabel> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.of(JSON.readValue(json, AotSheetProperties.ColourLabel[].class));
        } catch (JsonProcessingException e) {
            // A stored value nobody can read is as good as none: the rows show no name.
            return List.of();
        }
    }

    /** Accepts the sheet's full link, or the id on its own. */
    static String spreadsheetIdFrom(String linkOrId) {
        Matcher inUrl = ID_IN_URL.matcher(linkOrId);
        if (inUrl.find()) return inUrl.group(1);
        if (BARE_ID.matcher(linkOrId).matches()) return linkOrId;
        throw new BadRequestException("That does not look like a Google Sheet link. It should look like "
                + "https://docs.google.com/spreadsheets/d/…/edit — copy it from the browser's address bar.");
    }

    /** The properties with the saved row laid over them. Credentials are not part of it. */
    AotSheetProperties overlay(CaseSourceSheet saved) {
        AotSheetProperties p = new AotSheetProperties();
        // Descriptive column names keep coming from the properties' defaults, the issue
        // date unless one was chosen in the console.
        p.setOpenDateHeader(saved.getOpenDateHeader() == null ? properties.getOpenDateHeader() : saved.getOpenDateHeader());
        p.setTicketNoHeader(properties.getTicketNoHeader());
        p.setSerialHeader(properties.getSerialHeader());
        p.setModelHeader(properties.getModelHeader());
        p.setSiteHeader(properties.getSiteHeader());
        p.setProblemHeader(properties.getProblemHeader());
        p.setRequestedPartHeader(properties.getRequestedPartHeader());
        p.setVerifyHeader(properties.getVerifyHeader());
        p.setRepairByHeader(properties.getRepairByHeader());
        p.setPartReceivedHeader(properties.getPartReceivedHeader());
        p.setWaitingHeader(properties.getWaitingHeader());
        p.setReviewWords(properties.getReviewWords());

        p.setSpreadsheetId(saved.getSpreadsheetId());
        p.setTab(saved.getTab());
        p.setHeaderRow(saved.getHeaderRow());
        p.setRowIdHeader(nullToBlank(saved.getRowIdHeader()));
        p.setStatusHeader(nullToBlank(saved.getStatusHeader()));
        p.setClosedStatuses(saved.getClosedStatuses() == null ? List.of()
                : Arrays.stream(saved.getClosedStatuses().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
        p.setCloseDateHeader(nullToBlank(saved.getCloseDateHeader()));
        p.setColourHeader(nullToBlank(saved.getColourHeader()));
        p.setClosedColours(saved.getClosedColours() == null ? List.of()
                : Arrays.stream(saved.getClosedColours().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
        p.setColourLabels(fromJson(saved.getColourLabels()));
        p.setSyncEnabled(saved.isSyncEnabled());
        return p;
    }

    private static String urlFor(String spreadsheetId) {
        return spreadsheetId == null || spreadsheetId.isBlank()
                ? null
                : "https://docs.google.com/spreadsheets/d/" + spreadsheetId + "/edit";
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
