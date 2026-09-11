package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationOption;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptRules;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnExpression("'${app.anthropic.api-key:}' == ''")
public class MockAiService implements RequirementExtractionService, RobotRecommendationAiService,
        ProposalGenerationAiService, TranslationAiService, CmReportExtractionService,
        CaseSolutionAiService {

    @Override
    public ExtractedRequirementData extract(FileUpload fileUpload, RobotType robotType) {
        return new ExtractedRequirementData(
                robotType,
                "Extracted requirement from " + fileUpload.getOriginalFilename(),
                "Mock extraction is active. Review the uploaded survey data before generating recommendations.",
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                AiPromptRules.NEEDS_CONFIRMATION,
                List.of(
                        "Confirm site environment.",
                        "Confirm cleaning area per shift.",
                        "Confirm floor type and passable width."
                )
        );
    }

    @Override
    public AiRecommendationResult recommend(
            RequirementResponse requirement,
            List<RobotCatalogData> robotCatalog,
            int optionCount
    ) {
        if (robotCatalog.isEmpty()) {
            return new AiRecommendationResult(
                    "No robot catalog data was provided from the database.",
                    List.of()
            );
        }

        List<AiRecommendationOption> options = new ArrayList<>();
        List<RobotCatalogData> rankedCatalog = robotCatalog.stream()
                .sorted(Comparator
                        .comparing((RobotCatalogData robot) -> robot.spec() == null)
                        .thenComparing(robot -> robot.brand() + " " + robot.model()))
                .toList();
        int limit = Math.min(optionCount, rankedCatalog.size());
        for (int index = 0; index < limit; index++) {
            RobotCatalogData robot = rankedCatalog.get(index);
            options.add(new AiRecommendationOption(
                    robot.robotId(),
                    index + 1,
                    fitLevel(index, robot),
                    robot.brand() + " " + robot.model() + " solution",
                    proposalSummary(robot),
                    whyRecommended(requirement, robot),
                    null,
                    matchedRequirements(requirement, robot),
                    businessValue(robot),
                    limitations(robot),
                    missingInformation(requirement, robot),
                    "Confirm missing survey details, then generate a proposal from the selected option."
            ));
        }

        return new AiRecommendationResult(
                "Mock recommendation generated from " + robotCatalog.size() + " database robot record(s).",
                options
        );
    }

    @Override
    public AiProposalResult generateProposal(AiProposalRequest request) {
        String robotName = request.selectedOption().robot().brand() + " " + request.selectedOption().robot().model();
        String title = request.selectedOption().proposalTitle() == null
                ? robotName + " Proposal"
                : request.selectedOption().proposalTitle();
        String content = """
                ## Executive Summary
                %s

                ## Customer Requirement
                %s

                ## Recommended Robot
                %s

                ## Why Recommended
                %s

                ## Business Value
                %s

                ## Limitations / Missing Information
                %s

                ## Suggested Next Step
                %s
                """.formatted(
                title,
                valueOrNeedsConfirmation(request.requirement().description()),
                robotName,
                valueOrNeedsConfirmation(request.selectedOption().whyRecommended()),
                valueOrNeedsConfirmation(request.selectedOption().businessValue()),
                valueOrNeedsConfirmation(request.selectedOption().missingInformation()),
                valueOrNeedsConfirmation(request.selectedOption().suggestedNextStep())
        );

        return new AiProposalResult(title, content, "TEXT");
    }

    @Override
    public List<String> translateToThai(List<String> texts) {
        return texts == null ? List.of() : texts;
    }

    private String valueOrNeedsConfirmation(String value) {
        return value == null || value.isBlank() ? AiPromptRules.NEEDS_CONFIRMATION : value;
    }

    private String fitLevel(int index, RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Needs confirmation";
        }
        return index == 0 ? "Best fit" : "Possible fit";
    }

    private String proposalSummary(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Robot exists in the database, but specifications are incomplete.";
        }
        return "Uses database robot specifications for a first-pass RAASPAL solution option.";
    }

    private String whyRecommended(RequirementResponse requirement, RobotCatalogData robot) {
        StringBuilder builder = new StringBuilder();
        builder.append("Recommended because ")
                .append(robot.brand())
                .append(" ")
                .append(robot.model())
                .append(" is available in the database for ")
                .append(requirement.robotType())
                .append(" requirements.");

        RobotSpecResponse spec = robot.spec();
        if (spec != null && spec.widthCleaningMm() != null) {
            builder.append(" Cleaning width: ").append(spec.widthCleaningMm()).append(" mm.");
        }
        if (spec != null && spec.minimumPassableWidthMm() != null) {
            builder.append(" Minimum passable width: ").append(spec.minimumPassableWidthMm()).append(" mm.");
        }
        return builder.toString();
    }

    private String matchedRequirements(RequirementResponse requirement, RobotCatalogData robot) {
        List<String> matches = new ArrayList<>();
        if (requirement.robotType() == robot.robotType()) {
            matches.add("Robot type matches: " + requirement.robotType());
        }
        if (requirement.minPassableWidthMm() != null
                && robot.spec() != null
                && robot.spec().minimumPassableWidthMm() != null) {
            matches.add("Passable width can be checked against database value: "
                    + robot.spec().minimumPassableWidthMm() + " mm");
        }
        if (requirement.coverageAreaSqm() != null) {
            matches.add("Coverage area requested: " + requirement.coverageAreaSqm() + " sqm");
        }
        return matches.isEmpty() ? AiPromptRules.NEEDS_CONFIRMATION : String.join("; ", matches);
    }

    private String businessValue(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return "Can be reviewed as a possible option after robot specifications are confirmed.";
        }
        return "Helps RAASPAL quickly prepare a data-backed customer proposal using verified catalog information.";
    }

    private String limitations(RobotCatalogData robot) {
        if (robot.spec() == null) {
            return AiPromptRules.NEEDS_CONFIRMATION;
        }
        return "Mock AI does not perform weighted scoring. Final fit must be reviewed by RAASPAL team.";
    }

    private String missingInformation(RequirementResponse requirement, RobotCatalogData robot) {
        List<String> missing = new ArrayList<>();
        if (requirement.environment() == null) {
            missing.add("site environment");
        }
        if (requirement.coverageAreaSqm() == null) {
            missing.add("coverage area");
        }
        if (requirement.floorTypes() == null || requirement.floorTypes().length == 0) {
            missing.add("floor types");
        }
        if (robot.spec() == null) {
            missing.add("robot specifications");
        }
        return missing.isEmpty() ? "No major missing information detected by mock AI." : String.join(", ", missing);
    }

    // ─── CaseSolutionAiService ────────────────────────────────────────────────

    /**
     * One dated entry per comment, first few words of each. Deterministic, so a test
     * can assert on it, and honest about what it is: without an API key the column
     * shows the raw thread condensed, not a paraphrase. Same contract as the real
     * service — never throws, empty string for nothing to say.
     */
    @Override
    public String summariseProgress(CaseProgressRequest request) {
        if (request.comments() == null) return "";
        java.time.format.DateTimeFormatter dmy =
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM", java.util.Locale.ENGLISH);
        StringBuilder out = new StringBuilder();
        for (CaseProgressRequest.Comment c : request.comments()) {
            String body = c.body() == null ? "" : c.body().replaceAll("\\s+", " ").strip();
            if (body.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(c.postedOn() == null ? "??-???" : c.postedOn().format(dmy))
               .append(' ')
               .append(body.length() > 40 ? body.substring(0, 40) + "…" : body);
        }
        return out.toString();
    }

    // ─── CmReportExtractionService ────────────────────────────────────────────

    /**
     * Deterministic label-based parse of a pasted ticket.
     * <p>
     * Unlike the other mock responses this one is genuinely useful rather than
     * canned: CM tickets are already written as "label : value" lines, so a plain
     * scan handles the common case and keeps the feature usable locally without an
     * API key. It only recognises exact labels — anything unusual comes back null
     * for the operator to fill in, which is the same contract as the real service.
     */
    @Override
    public CmReportDraft extractCmReport(String sourceText) {
        Map<String, String> fields = new LinkedHashMap<>();
        String currentLabel = null;
        StringBuilder buffer = new StringBuilder();

        for (String rawLine : sourceText.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            String matched = matchLabel(line);
            if (matched != null) {
                flush(fields, currentLabel, buffer);
                currentLabel = matched;
                buffer.setLength(0);
                String remainder = line.substring(line.indexOf(':', LABELS.get(matched).length() - 1) + 1).strip();
                if (!remainder.isEmpty()) buffer.append(remainder);
            } else if (currentLabel != null) {
                if (!buffer.isEmpty()) buffer.append('\n');
                buffer.append(line);
            }
        }
        flush(fields, currentLabel, buffer);

        List<String> actions = fields.containsKey("correctiveActions")
                ? Arrays.stream(fields.get("correctiveActions").split("\\R"))
                        .map(s -> s.replaceFirst("^\\s*(?:\\d+[.)]|-)\\s*", "").strip())
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();

        return new CmReportDraft(
                parseThaiDate(fields.get("reportDate")),
                fields.get("ticketNo"),
                fields.get("customerName"),
                fields.get("technicianName"),
                fields.get("robotModel"),
                fields.get("serialNumber"),
                fields.get("causeDetail"),
                fields.get("inspectionResult"),
                actions,
                fields.get("testResult"));
    }

    /** Ticket label → draft field. Insertion order is longest-prefix-first where labels overlap. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("reportDate", "วันที่");
        LABELS.put("ticketNo", "Ticket No.");
        LABELS.put("customerName", "ชื่อบริษัทลูกค้า");
        LABELS.put("technicianName", "เจ้าหน้าที่ผู้เข้าดำเนินการ");
        LABELS.put("robotModel", "รุ่นหุ่นยนต์");
        LABELS.put("serialNumber", "Serial Number");
        LABELS.put("causeDetail", "รายละเอียดของสาเหตุ");
        LABELS.put("inspectionResult", "ผลการตรวจสอบ");
        LABELS.put("correctiveActions", "การดำเนินการแก้ไข");
        LABELS.put("testResult", "ผลการทดสอบ");
    }

    private static String matchLabel(String line) {
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            String label = entry.getValue();
            if (line.startsWith(label) && line.substring(label.length()).stripLeading().startsWith(":")) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void flush(Map<String, String> fields, String label, StringBuilder buffer) {
        if (label != null && !buffer.isEmpty()) {
            fields.put(label, buffer.toString().strip());
        }
    }

    private static final List<String> THAI_MONTHS = List.of(
            "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
            "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม");

    /** "17 มิถุนายน 2569" -> "2026-06-17". Returns the input unchanged if it isn't that shape. */
    private static String parseThaiDate(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.strip().split("\\s+");
        if (parts.length != 3) return value.strip();
        int monthIndex = THAI_MONTHS.indexOf(parts[1]);
        if (monthIndex < 0) return value.strip();
        try {
            int day = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[2]);
            if (year > 2200) year -= 543; // Buddhist era
            return String.format("%04d-%02d-%02d", year, monthIndex + 1, day);
        } catch (NumberFormatException e) {
            return value.strip();
        }
    }
}
