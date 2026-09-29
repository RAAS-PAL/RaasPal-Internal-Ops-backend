package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetApiClient;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
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
        // Descriptive column names keep coming from the properties' defaults.
        p.setOpenDateHeader(properties.getOpenDateHeader());
        p.setTicketNoHeader(properties.getTicketNoHeader());
        p.setSerialHeader(properties.getSerialHeader());
        p.setModelHeader(properties.getModelHeader());
        p.setSiteHeader(properties.getSiteHeader());
        p.setProblemHeader(properties.getProblemHeader());
        p.setRequestedPartHeader(properties.getRequestedPartHeader());
        p.setVerifyHeader(properties.getVerifyHeader());
        p.setRepairByHeader(properties.getRepairByHeader());

        p.setSpreadsheetId(saved.getSpreadsheetId());
        p.setTab(saved.getTab());
        p.setHeaderRow(saved.getHeaderRow());
        p.setRowIdHeader(nullToBlank(saved.getRowIdHeader()));
        p.setStatusHeader(nullToBlank(saved.getStatusHeader()));
        p.setClosedStatuses(saved.getClosedStatuses() == null ? List.of()
                : Arrays.stream(saved.getClosedStatuses().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
        p.setCloseDateHeader(nullToBlank(saved.getCloseDateHeader()));
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
