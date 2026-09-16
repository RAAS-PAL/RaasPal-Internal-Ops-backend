package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookParser;
import com.raaspal.robotrecommendation.kpi.entity.KpiCsatWorkbook;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookRepository;
import com.raaspal.robotrecommendation.kpi.dto.CsatWorkbookHistoryEntry;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookSummary;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Accepts a survey workbook, and refuses one that would not count.
 *
 * <p>The workbook is parsed <em>before</em> it is stored. {@code CsatStream.detect()}
 * reads the sheet title and falls back to the file name, and it can genuinely
 * fail — a renamed file, a new survey. Storing a workbook whose survey nobody
 * can tell means it sits in the table being silently ignored by every read,
 * which is the worst of the available outcomes: the uploader believes CSAT is
 * updated and the figure never moves. So detection failure is a 400 at the
 * moment of upload, while the person still has the file in front of them.
 *
 * <p>The parser's own warnings come back with a successful upload too, so an
 * unreadable month sheet is seen now rather than found later on the dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsatWorkbookUploadService {

    /**
     * Generous for a survey workbook — they run to a few hundred KB — and small
     * enough that a wrong file cannot bloat the table. Enforced here rather than
     * by the column, which on Postgres is an unbounded bytea.
     */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final KpiCsatWorkbookRepository workbooks;

    private final CsatWorkbookParser parser;

    private final KpiCsatService csatService;

    private final UserRepository users;

    /** What an upload did, for the console to show without re-reading the list. */
    public record UploadResult(
            UUID id,
            String stream,
            String fileName,
            long sizeBytes,
            Instant uploadedAt,
            boolean duplicate,
            List<String> warnings
    ) {
    }

    /** A stored workbook, exactly as it was uploaded. */
    public record Download(String fileName, byte[] content) {
    }

    @Transactional
    public UploadResult upload(MultipartFile file, UUID uploadedBy, String note) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was uploaded");
        }
        String name = sanitise(file.getOriginalFilename());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BadRequestException("A CSAT survey workbook must be an .xlsx file; got '" + name + "'");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("That file is " + (file.getSize() / (1024 * 1024))
                    + " MB; survey workbooks are a few hundred KB, so this is refused at 10 MB "
                    + "in case it is the wrong file");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file: " + e.getMessage());
        }

        CsatWorkbook parsed = parse(name, bytes);
        if (parsed.stream() == null) {
            throw new BadRequestException("Could not tell which survey '" + name + "' is. The survey is read from "
                    + "the sheet title, and failing that the file name, and it has to say one of installation, "
                    + "MA (for PM), cleaning or delivery. Rename the file to include it and upload again.");
        }
        if (parsed.months().isEmpty()) {
            throw new BadRequestException("No month sheets could be read from '" + name + "', so it would add "
                    + "nothing to CSAT. Month sheets are found by their label; check this is the right workbook.");
        }

        String sha = sha256(bytes);
        // The same bytes again for the same survey is a double-click or a
        // re-upload of the file already in use. Answer it rather than adding a
        // row nobody could tell from the one above it.
        if (workbooks.findCurrentSha256(parsed.stream()).filter(sha::equals).isPresent()) {
            KpiCsatWorkbookSummary current = workbooks.findCurrent().stream()
                    .filter(w -> w.getStream() == parsed.stream())
                    .findFirst()
                    .orElseThrow();
            log.info("CSAT upload of {} is identical to the current {} workbook; keeping the existing row",
                    name, parsed.stream().key());
            return new UploadResult(current.getId(), current.getStream().key(), current.getFileName(),
                    current.getSizeBytes(), current.getUploadedAt(), true, parsed.warnings());
        }

        KpiCsatWorkbook saved = workbooks.save(KpiCsatWorkbook.builder()
                .stream(parsed.stream())
                .fileName(name)
                .sizeBytes(bytes.length)
                .sha256(sha)
                .content(bytes)
                .uploadedAt(Instant.now())
                .uploadedBy(uploadedBy)
                .note(note == null || note.isBlank() ? null : note.trim())
                .build());

        // The set changed, so whatever the service is holding is now stale.
        csatService.reload();
        log.info("CSAT workbook uploaded: {} ({}, {} bytes) is now the current {} workbook",
                name, saved.getId(), bytes.length, parsed.stream().key());

        return new UploadResult(saved.getId(), parsed.stream().key(), name, bytes.length,
                saved.getUploadedAt(), false, parsed.warnings());
    }

    /**
     * Removes one upload. Deleting the current workbook for a survey is allowed
     * and is how a wrong upload is undone: because "current" is the most recent
     * row rather than a stored flag, the one before it simply becomes current
     * again, and CSAT goes back to what it said before.
     */
    @Transactional
    public CsatWorkbookHistoryEntry delete(UUID id) {
        KpiCsatWorkbookSummary row = workbooks.findSummary(id)
                .orElseThrow(() -> new BadRequestException("No such CSAT workbook: " + id));
        boolean wasCurrent = workbooks.findCurrent().stream().anyMatch(w -> w.getId().equals(id));
        workbooks.deleteById(id);
        csatService.reload();
        log.info("CSAT workbook deleted: {} ({}, {} survey, was current: {})",
                row.getFileName(), id, row.getStream().key(), wasCurrent);
        return new CsatWorkbookHistoryEntry(row.getId(), row.getStream().name(), row.getStream().key(),
                row.getFileName(), row.getSizeBytes(), row.getUploadedAt(), row.getUploadedBy(),
                null, row.getNote(), false);
    }

    /** The stored bytes of one upload, for downloading it back out. */
    @Transactional(readOnly = true)
    public Download download(UUID id) {
        KpiCsatWorkbookSummary row = workbooks.findSummary(id)
                .orElseThrow(() -> new BadRequestException("No such CSAT workbook: " + id));
        byte[] content = workbooks.findContent(id)
                .orElseThrow(() -> new BadRequestException("No such CSAT workbook: " + id));
        return new Download(row.getFileName(), content);
    }

    /**
     * Every upload, newest first, with the current one per survey marked.
     *
     * <p>Never selects the workbook bytes: this is a list of names and dates, and
     * a page of it carrying a megabyte blob per row would be a needless load on a
     * pool capped at six connections.
     */
    @Transactional(readOnly = true)
    public List<CsatWorkbookHistoryEntry> history() {
        Set<UUID> current = workbooks.findCurrent().stream()
                .map(KpiCsatWorkbookSummary::getId)
                .collect(Collectors.toCollection(HashSet::new));

        List<KpiCsatWorkbookSummary> rows = workbooks.findHistory();
        Set<UUID> uploaderIds = rows.stream()
                .map(KpiCsatWorkbookSummary::getUploadedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = uploaderIds.isEmpty() ? Map.of()
                : users.findAllById(uploaderIds).stream()
                        .collect(Collectors.toMap(u -> u.getId(), u -> u.getFullName(), (a, b) -> a));

        return rows.stream()
                .map(r -> new CsatWorkbookHistoryEntry(
                        r.getId(),
                        r.getStream().name(),
                        r.getStream().key(),
                        r.getFileName(),
                        r.getSizeBytes(),
                        r.getUploadedAt(),
                        r.getUploadedBy(),
                        r.getUploadedBy() == null ? null : names.get(r.getUploadedBy()),
                        r.getNote(),
                        current.contains(r.getId())))
                .toList();
    }

    private CsatWorkbook parse(String name, byte[] bytes) {
        try {
            return parser.parse(name, Instant.now(), new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new BadRequestException("'" + name + "' could not be read as a survey workbook: " + e.getMessage());
        }
    }

    /** Strips any path a browser may send, so the stored name is a name. */
    private static String sanitise(String original) {
        if (original == null || original.isBlank()) {
            throw new BadRequestException("The uploaded file has no name");
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name.trim() : name.substring(slash + 1).trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
