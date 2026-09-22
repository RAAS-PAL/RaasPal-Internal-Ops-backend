package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.*;
import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import com.raaspal.robotrecommendation.reassignment.entity.ReMatrixRevision;
import com.raaspal.robotrecommendation.reassignment.entity.ReSkillDefinition;
import com.raaspal.robotrecommendation.reassignment.repository.ReEngineerRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReSkillDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/**
 * Imports the Senior RE's "RE Skill Matrix" workbook.
 *
 * <p>Two steps, both from the same uploaded file: a <b>preview</b> (who matches whom, which
 * levels would change, anything that blocks) and a <b>commit</b> that re-reads the file and
 * applies it as one revision. Nothing is stored between the two, so the commit applies
 * exactly the file it is given.
 *
 * <p>The layout is found, not assumed: the header row is the one holding "ชื่อ-สกุล", the
 * row under it holds the skill names, and each column's group is the merged cell above it
 * ("CM", "Cleaning Model"...). Group + skill name map to a stable skill code, tolerating
 * the workbook's "Cleanning" spelling and the CLN/DLV abbreviations. A column that maps to
 * nothing is reported, never guessed. Levels must be L1-L4, "-" or blank; anything else
 * blocks the commit.
 *
 * <p>Engineers are matched by full name, then by nickname when that is unique. An
 * unmatched row becomes a new engineer on commit; a nickname that matches two people
 * blocks it. The workbook is authoritative for the engineers it lists: a "-" over a
 * stored level clears it. Engineers the workbook does not list are left as they are.
 */
@Service
@RequiredArgsConstructor
public class ReSkillImportService {

    private static final String SHEET = "skill matrix";
    private static final String NAME_HEADER = "ชื่อ-สกุล";
    private static final String NICK_HEADER = "ชื่อเล่น";
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ReEngineerRepository engineers;
    private final ReSkillDefinitionRepository skillDefinitions;
    private final ReSkillMatrixService matrix;
    private final ReEventLog events;

    /** One parsed data row. */
    record Parsed(int sourceRow, String fullName, String nickname, Map<String, ParsedCell> cells) {
    }

    record ParsedCell(String skillCode, Integer level, String sourceCell, String sourceValue, String error) {
    }

    record Sheet(String name, List<Parsed> rows, List<String> unmapped, List<String> errors) {
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(byte[] file) {
        Sheet sheet = parse(file);
        return buildPreview(sheet, hash(file));
    }

    @Transactional
    public ImportResult commit(byte[] file, String label, String reason, String actor) {
        if (label == null || label.isBlank()) throw new BadRequestException("A revision label is required, e.g. 001");
        if (reason == null || reason.isBlank()) throw new BadRequestException("A reason is required");
        Sheet sheet = parse(file);
        String fileHash = hash(file);
        ImportPreview preview = buildPreview(sheet, fileHash);
        if (!preview.canCommit()) {
            throw new BadRequestException("The workbook has problems that block the import: "
                    + String.join("; ", preview.errors()));
        }

        int created = 0;
        Map<Integer, UUID> engineerByRow = new HashMap<>();
        for (ImportRow row : preview.rows()) {
            UUID id = row.engineerId();
            if ("NEW".equals(row.match())) {
                ReEngineer e = engineers.save(ReEngineer.builder()
                        .fullName(row.fullName()).nickname(row.nickname())
                        .note("Created by skill-matrix import " + label.trim()).build());
                events.record("ENGINEER", e.getId(), "CREATED", actor, Map.of("source", "import " + label.trim()));
                id = e.getId();
                created++;
            }
            engineerByRow.put(row.sourceRow(), id);
        }

        ReMatrixRevision rev = matrix.newRevision(label.trim(), "EXCEL", fileHash, reason.trim(), actor);
        List<LevelChange> changes = new ArrayList<>();
        for (Parsed p : sheet.rows()) {
            UUID id = engineerByRow.get(p.sourceRow());
            p.cells().values().forEach(c -> changes.add(new LevelChange(id, c.skillCode(), c.level())));
        }
        int changed = matrix.applyChanges(rev, changes);
        events.record("SKILL_MATRIX", rev.getId(), "IMPORTED", actor,
                Map.of("label", rev.getLabel(), "fileHash", fileHash, "cells", changed, "engineersCreated", created));
        return new ImportResult(new RevisionView(rev.getId(), rev.getLabel(), rev.getSource(), rev.getReason(),
                rev.getCreatedBy(), rev.getCreatedAt(), changed), created, changed);
    }

    /* ─── Preview ─────────────────────────────────────────────────────────── */

    private ImportPreview buildPreview(Sheet sheet, String fileHash) {
        List<ReEngineer> all = engineers.findAll();
        Map<UUID, Map<String, Integer>> current = matrix.currentLevels();
        List<String> errors = new ArrayList<>(sheet.errors());
        List<String> warnings = new ArrayList<>();
        List<ImportRow> rows = new ArrayList<>();
        Set<UUID> matchedIds = new HashSet<>();
        int newEngineers = 0;
        int changed = 0;

        for (Parsed p : sheet.rows()) {
            List<ReEngineer> byName = all.stream().filter(e -> key(e.getFullName()).equals(key(p.fullName()))).toList();
            List<ReEngineer> byNick = p.nickname() == null ? List.of()
                    : all.stream().filter(e -> e.getNickname() != null && key(e.getNickname()).equals(key(p.nickname()))).toList();
            String match;
            ReEngineer engineer = null;
            if (byName.size() == 1) {
                match = "EXISTING";
                engineer = byName.get(0);
            } else if (byName.isEmpty() && byNick.size() == 1) {
                match = "EXISTING";
                engineer = byNick.get(0);
                warnings.add("Row " + p.sourceRow() + ": matched " + engineer.displayName() + " by nickname only");
            } else if (byName.isEmpty() && byNick.isEmpty()) {
                match = "NEW";
                newEngineers++;
            } else {
                match = "AMBIGUOUS";
                errors.add("Row " + p.sourceRow() + " (" + p.fullName() + ") matches more than one engineer");
            }
            if (engineer != null && !matchedIds.add(engineer.getId())) {
                errors.add("Row " + p.sourceRow() + ": " + engineer.displayName() + " appears twice in the workbook");
            }

            Map<String, Integer> have = engineer == null ? Map.of() : current.getOrDefault(engineer.getId(), Map.of());
            List<ImportCell> cells = new ArrayList<>();
            int rowChanges = 0;
            boolean anyLevel = false;
            for (ParsedCell c : p.cells().values()) {
                if (c.error() != null) errors.add("Row " + p.sourceRow() + " " + c.sourceCell() + ": " + c.error());
                Integer now = have.get(c.skillCode());
                if (!Objects.equals(now, c.level())) rowChanges++;
                if (c.level() != null) anyLevel = true;
                cells.add(new ImportCell(c.skillCode(), c.level(), now, c.sourceCell(), c.sourceValue()));
            }
            changed += rowChanges;
            rows.add(new ImportRow(p.sourceRow(), p.fullName(), p.nickname(), match,
                    engineer == null ? null : engineer.getId(), engineer == null ? null : engineer.displayName(),
                    cells, rowChanges, !anyLevel));
        }

        all.stream().filter(e -> e.isActive() && !matchedIds.contains(e.getId()))
                .forEach(e -> warnings.add(e.displayName() + " is not in the workbook - their levels stay as they are"));
        if (!sheet.unmapped().isEmpty()) {
            warnings.add("Columns not recognised and skipped: " + String.join(", ", sheet.unmapped()));
        }
        if (rows.isEmpty()) errors.add("No engineer rows found under the header");

        return new ImportPreview(fileHash, sheet.name(), rows, sheet.unmapped(), warnings, errors,
                newEngineers, changed, errors.isEmpty());
    }

    /* ─── Parsing ─────────────────────────────────────────────────────────── */

    Sheet parse(byte[] file) {
        if (file == null || file.length == 0) throw new BadRequestException("The file is empty");
        if (file.length > MAX_BYTES) throw new BadRequestException("The file is larger than 10 MB");
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            org.apache.poi.ss.usermodel.Sheet ws = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (norm(wb.getSheetName(i)).equals(SHEET)) ws = wb.getSheetAt(i);
            }
            if (ws == null) throw new BadRequestException("No \"Skill Matrix\" sheet in this workbook");
            return parseSheet(ws);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Could not read the workbook (.xlsx expected): " + e.getMessage());
        }
    }

    private Sheet parseSheet(org.apache.poi.ss.usermodel.Sheet ws) {
        DataFormatter fmt = new DataFormatter();
        int headerRow = -1;
        int nameCol = -1;
        int nickCol = -1;
        for (int r = 0; r <= Math.min(ws.getLastRowNum(), 30) && headerRow < 0; r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String v = text(fmt, cell);
                if (norm(v).equals(norm(NAME_HEADER))) {
                    headerRow = r;
                    nameCol = cell.getColumnIndex();
                }
                if (norm(v).equals(norm(NICK_HEADER))) nickCol = cell.getColumnIndex();
            }
        }
        if (headerRow < 0) throw new BadRequestException("Could not find the \"ชื่อ-สกุล\" header on the Skill Matrix sheet");

        Row groupRow = ws.getRow(headerRow);
        Row subRow = ws.getRow(headerRow + 1);
        int lastCol = Math.max(groupRow.getLastCellNum(), subRow == null ? 0 : subRow.getLastCellNum());
        Map<String, ReSkillDefinition> models = new HashMap<>();
        skillDefinitions.findAll().stream().filter(s -> "MODEL".equals(s.getGroupCode()))
                .forEach(s -> models.put(s.getBoardType() + ":" + compact(s.getLabel()), s));

        Map<Integer, String> columnSkill = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        int firstSkillCol = Math.max(nameCol, nickCol) + 1;
        for (int col = firstSkillCol; col < lastCol; col++) {
            String group = mergedText(ws, fmt, headerRow, col);
            String sub = subRow == null ? "" : text(fmt, subRow.getCell(col));
            if (group.isBlank() && sub.isBlank()) continue;
            if (compact(sub).contains("priorit") || compact(group).contains("priorit")) continue; // derived count
            String code = skillCode(group, sub, models);
            String letter = CellReference.convertNumToColString(col);
            if (code == null) {
                unmapped.add(letter + " (" + (group.isBlank() ? "" : group + " / ") + sub + ")");
                continue;
            }
            if (!seenCodes.add(code)) errors.add("Column " + letter + " repeats skill " + code);
            columnSkill.put(col, code);
        }

        List<Parsed> rows = new ArrayList<>();
        for (int r = headerRow + 2; r <= ws.getLastRowNum(); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            String fullName = clean(text(fmt, row.getCell(nameCol)));
            if (fullName.isBlank()) continue;
            String nickname = nickCol < 0 ? null : blankToNull(clean(text(fmt, row.getCell(nickCol))));
            Map<String, ParsedCell> cells = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : columnSkill.entrySet()) {
                Cell cell = row.getCell(e.getKey());
                String raw = clean(text(fmt, cell));
                String ref = CellReference.convertNumToColString(e.getKey()) + (r + 1);
                Integer level = null;
                String error = null;
                if (!raw.isBlank() && !raw.equals("-")) {
                    String u = raw.toUpperCase(Locale.ROOT);
                    if (u.matches("L[1-4]")) level = u.charAt(1) - '0';
                    else error = "\"" + raw + "\" is not L1-L4 or -";
                }
                cells.put(e.getValue(), new ParsedCell(e.getValue(), level, ref, raw, error));
            }
            rows.add(new Parsed(r + 1, fullName, nickname, cells));
        }
        return new Sheet(ws.getSheetName(), rows, unmapped, errors);
    }

    /** Group + skill name → skill code, or null when the column is not a known skill. */
    static String skillCode(String group, String sub, Map<String, ReSkillDefinition> models) {
        String g = compact(group);
        String s = compact(sub);
        String side = s.startsWith("clean") || s.equals("cln") ? "CLEANING"
                : s.startsWith("deliver") || s.equals("dlv") ? "DELIVERY" : null;
        if (g.contains("soft")) {
            if (s.contains("การสื่อสาร") || s.contains("communication")) return "COMMUNICATION";
            if (s.contains("การบริการ") || s.contains("service")) return "SERVICE";
            if (s.contains("training")) return "TRAINING";
            return null;
        }
        if (g.contains("model")) {
            String board = g.contains("deliver") ? "DELIVERY" : "CLEANING";
            ReSkillDefinition d = models.get(board + ":" + s);
            return d == null ? null : d.getCode();
        }
        if (g.contains("expert")) {
            String board = g.contains("deliver") ? "DELIVERY" : "CLEANING";
            if (s.startsWith("electric")) return "ELECTRICAL_" + board;
            if (s.startsWith("mechanic")) return "MECHANICAL_" + board;
            if (s.startsWith("software")) return "SOFTWARE_" + board;
            if (s.startsWith("trouble")) return "TROUBLESHOOTING_" + board;
            return null;
        }
        if (side == null) return null;
        if (g.startsWith("overall")) return "OVERALL_" + side;
        if (g.startsWith("installation")) return "INSTALLATION_" + side;
        if (g.equals("pm")) return "PM_" + side;
        if (g.equals("cm")) return "CM_" + side;
        return null;
    }

    /** The text of the merged header cell covering (row, col), or the cell itself. */
    private static String mergedText(org.apache.poi.ss.usermodel.Sheet ws, DataFormatter fmt, int row, int col) {
        for (CellRangeAddress range : ws.getMergedRegions()) {
            if (range.isInRange(row, col)) {
                Row r = ws.getRow(range.getFirstRow());
                return r == null ? "" : text(fmt, r.getCell(range.getFirstColumn()));
            }
        }
        Row r = ws.getRow(row);
        return r == null ? "" : text(fmt, r.getCell(col));
    }

    private static String text(DataFormatter fmt, Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                default -> "";
            };
        }
        return fmt.formatCellValue(cell);
    }

    private static String hash(byte[] file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unicode-normalised, single-spaced, trimmed. */
    static String clean(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC).replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    static String key(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }

    static String norm(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }

    /** Lower-case with spaces, hyphens and slashes removed: "X-Human" → "xhuman". */
    static String compact(String s) {
        return clean(s).toLowerCase(Locale.ROOT).replaceAll("[\\s\\-/_.]", "");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
