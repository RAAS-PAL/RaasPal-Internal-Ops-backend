package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.proposal.dto.SlideManifest;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptRules;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptTemplates;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Claude AI implementation — activated automatically when ANTHROPIC_API_KEY is set.
 * Falls back to MockAiService when the key is absent or empty.
 */
@Service
@ConditionalOnExpression("'${app.anthropic.api-key:}' != ''")
public class ClaudeAiService
        implements RequirementExtractionService, RobotRecommendationAiService, ProposalGenerationAiService,
        TranslationAiService, CmReportExtractionService, CaseSolutionAiService,
        CasePartsAiService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAiService.class);

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION  = "2023-06-01";

    private final RestClient  restClient;
    private final ObjectMapper objectMapper;
    private final Path         uploadDir;
    private final String       model;
    private final String       proposalModel;
    private final int          maxTokens;

    public ClaudeAiService(
            @Value("${app.anthropic.api-key}") String apiKey,
            @Value("${app.file.upload-dir}") String uploadDir,
            @Value("${app.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${app.anthropic.proposal-model:claude-opus-4-8}") String proposalModel,
            @Value("${app.anthropic.max-tokens:16000}") int maxTokens,
            @Value("${app.anthropic.connect-timeout-seconds:15}") int connectTimeoutSeconds,
            @Value("${app.anthropic.read-timeout-seconds:300}") int readTimeoutSeconds,
            ObjectMapper objectMapper) {
        this.uploadDir     = Path.of(uploadDir);
        this.model         = model;
        this.proposalModel = proposalModel;
        this.maxTokens     = maxTokens;
        this.objectMapper  = objectMapper;
        // Timeouts, for the reason the telemetry sync taught us: a request with none
        // waits forever on a silent server, and the caller has no way to tell that from
        // slow generation. The read timeout is generous because a long proposal
        // legitimately takes minutes.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient   = RestClient.builder()
                .baseUrl(ANTHROPIC_API_URL)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    // ─── RequirementExtractionService ─────────────────────────────────────────

    @Override
    public ExtractedRequirementData extract(FileUpload fileUpload, RobotType robotType) {
        try {
            byte[] bytes = Files.readAllBytes(uploadDir.resolve(fileUpload.getStoredFilename()));
            String system = AiPromptTemplates.extractionSystemPrompt();
            String user   = buildExtractionUserPrompt(robotType);

            String response = callClaude(system, user, bytes, fileUpload.getContentType(), model);
            return parseExtraction(response, robotType);
        } catch (IOException e) {
            log.error("Cannot read uploaded file for AI extraction: {}", fileUpload.getStoredFilename(), e);
            throw new RuntimeException("Failed to read uploaded file for AI extraction", e);
        }
    }

    private String buildExtractionUserPrompt(RobotType robotType) {
        return """
                Extract all robot solution requirements from the attached survey document.
                The customer is looking for a %s robot solution.

                Return ONLY a valid JSON object — no explanation, no markdown code fences — with exactly these fields:
                {
                  "robotType": "%s",
                  "title": "short title summarising the requirement",
                  "description": "full requirement description",
                  "environment": "INDOOR | OUTDOOR | CLEANROOM | HAZARDOUS | null",
                  "cleaningFunctions": ["functions mentioned, or empty array"],
                  "floorTypes": ["floor types mentioned, or empty array"],
                  "minPassableWidthMm": null,
                  "coverageAreaSqm": null,
                  "budgetBand": "LOW | MODERATE | HIGH | null",
                  "priorityNotes": "any priority or special notes, or null",
                  "missingQuestions": ["questions to ask the customer about missing information"]
                }

                Rules:
                - Use null for unknown numeric or enum fields.
                - Never invent data not present in the document.
                - robotType must remain "%s".
                """.formatted(robotType, robotType, robotType);
    }

    private ExtractedRequirementData parseExtraction(String text, RobotType robotType) {
        try {
            return objectMapper.readValue(extractJson(text), ExtractedRequirementData.class);
        } catch (JsonProcessingException e) {
            log.warn("Could not parse extraction JSON — returning needs-confirmation result. Response: {}", text);
            return new ExtractedRequirementData(
                    robotType,
                    AiPromptRules.NEEDS_CONFIRMATION,
                    text,
                    null,
                    List.of(),
                    List.of(),
                    null, null, null,
                    AiPromptRules.NEEDS_CONFIRMATION,
                    List.of("AI response could not be parsed. Please review the document manually.")
            );
        }
    }

    // ─── RobotRecommendationAiService ─────────────────────────────────────────

    @Override
    public AiRecommendationResult recommend(
            RequirementResponse requirement,
            List<RobotCatalogData> robotCatalog,
            int optionCount) {
        try {
            String system = AiPromptTemplates.recommendationSystemPrompt(requirement);
            String user   = buildRecommendationUserPrompt(requirement, robotCatalog, optionCount);

            String response = callClaude(system, user, null, null, model);
            return parseRecommendation(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise recommendation request", e);
            throw new RuntimeException("Failed to build AI recommendation request", e);
        }
    }

    private String buildRecommendationUserPrompt(
            RequirementResponse requirement,
            List<RobotCatalogData> robotCatalog,
            int optionCount) throws JsonProcessingException {
        String reqJson     = objectMapper.writeValueAsString(requirement);
        String catalogJson = objectMapper.writeValueAsString(robotCatalog);
        return """
                CUSTOMER REQUIREMENT:
                %s

                AVAILABLE ROBOTS FROM DATABASE (use ONLY these robots — never invent specs):
                %s

                Recommend the top %d robot options from the catalog above that best match the requirement.

                Return ONLY a valid JSON object — no explanation, no markdown code fences — with exactly this structure:
                {
                  "aiExplanation": "brief explanation of the overall recommendation approach",
                  "options": [
                    {
                      "robotId": "exact UUID string from the catalog — must match one of the robotId values above",
                      "rankPosition": 1,
                      "fitLevel": "Best fit | Good fit | Possible fit",
                      "proposalTitle": "short proposal title",
                      "proposalSummary": "1–2 sentence summary",
                      "whyRecommended": "detailed technical reasoning referencing catalog data only",
                      "customerSummary": ["exactly 5 short professional phrases as a JSON array of strings — each phrase 4–8 words, outcome-focused, no technical jargon, no spec numbers, no model codes — written for a business decision-maker; cover: environment fit, key capability, productivity benefit, ease of operation, and a standout advantage"],
                      "matchedRequirements": "which customer requirements this robot satisfies",
                      "businessValue": "business value for the customer",
                      "limitations": "limitations or constraints to note",
                      "missingInformation": "what data is still needed to confirm this recommendation",
                      "suggestedNextStep": "recommended next action"
                    }
                  ]
                }

                Rules:
                - robotId MUST be one of the UUID values from the catalog above.
                - Do not invent robot specifications not present in the catalog.
                - Use "%s" for any field where data is missing or unconfirmed.
                """.formatted(reqJson, catalogJson, optionCount, AiPromptRules.NEEDS_CONFIRMATION);
    }

    private AiRecommendationResult parseRecommendation(String text) {
        try {
            return objectMapper.readValue(extractJson(text), AiRecommendationResult.class);
        } catch (JsonProcessingException e) {
            log.error("Could not parse recommendation JSON. Response: {}", text);
            throw new RuntimeException("AI returned an unreadable recommendation response. Please try again.", e);
        }
    }

    // ─── ProposalGenerationAiService ──────────────────────────────────────────

    @Override
    public AiProposalResult generateProposal(AiProposalRequest request) {
        String system  = AiPromptTemplates.proposalSystemPrompt();
        String user    = buildProposalUserPrompt(request);
        String content = callClaude(system, user, null, null, proposalModel);

        var opt = request.selectedOption();
        String title = opt.proposalTitle() != null && !opt.proposalTitle().isBlank()
                ? opt.proposalTitle()
                : opt.robot().brand() + " " + opt.robot().model() + " Proposal";

        return new AiProposalResult(title, content, "MARKDOWN");
    }

    private String buildProposalUserPrompt(AiProposalRequest request) {
        var opt   = request.selectedOption();
        var robot = opt.robot();
        var req   = request.requirement();

        String templateSection = request.template() != null && request.template().templateContent() != null
                ? "\n\nPROPOSAL TEMPLATE STYLE (follow its structure and tone; do not copy irrelevant details):\n"
                        + request.template().templateContent()
                : "";

        return """
                Generate a professional customer-facing robot solution proposal for RAASPAL.

                CUSTOMER REQUIREMENT:
                - Type: %s
                - Description: %s
                - Environment: %s
                - Coverage Area: %s sqm
                - Budget Band: %s
                - Priority Notes: %s

                SELECTED ROBOT SOLUTION:
                - Robot: %s %s
                - Fit Level: %s
                - Why Recommended: %s
                - Matched Requirements: %s
                - Business Value: %s
                - Limitations: %s
                - Missing Information: %s
                - Suggested Next Step: %s
                %s

                Write a structured proposal in Markdown with these sections:
                1. Executive Summary
                2. Customer Requirements Overview
                3. Recommended Solution: %s %s
                4. Why This Solution
                5. Business Value
                6. Limitations & Considerations
                7. Missing Information / Open Questions
                8. Suggested Next Steps
                9. Disclaimer

                Include in the Disclaimer: "Final solution confirmation requires RAASPAL verification and/or site survey."
                Use "%s" wherever information is missing or unconfirmed.
                """.formatted(
                req.robotType(), req.description(), req.environment(),
                req.coverageAreaSqm(), req.budgetBand(), req.priorityNotes(),
                robot.brand(), robot.model(),
                opt.fitLevel(), opt.whyRecommended(), opt.matchedRequirements(),
                opt.businessValue(), opt.limitations(), opt.missingInformation(),
                opt.suggestedNextStep(),
                templateSection,
                robot.brand(), robot.model(),
                AiPromptRules.NEEDS_CONFIRMATION
        );
    }

    // ─── ProposalGenerationAiService: slide manifest ──────────────────────────

    private static final String SLIDE_MODEL = "claude-haiku-4-5-20251001";

    @Override
    public SlideManifest generateSlideManifest(String proposalContent) {
        if (proposalContent == null || proposalContent.isBlank()) return null;
        String system = "You are a professional PowerPoint slide deck designer for RAASPAL, a robot solution company.";
        String user   = buildSlideManifestPrompt(proposalContent);
        try {
            String response = callClaude(system, user, null, null, SLIDE_MODEL);
            return parseSlideManifest(response);
        } catch (Exception e) {
            log.warn("Slide manifest generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildSlideManifestPrompt(String proposalContent) {
        return """
                Convert the following robot solution proposal into a structured JSON slide manifest \
                for a RAASPAL customer-facing PowerPoint presentation.

                Return ONLY a valid JSON object — no explanation, no markdown code fences:
                {
                  "slides": [
                    {
                      "type": "title",
                      "title": "short compelling title (max 10 words)",
                      "subtitle": "customer / site name extracted from the proposal"
                    },
                    {
                      "type": "key_stats",
                      "title": "Key Numbers",
                      "stats": [
                        {"label": "metric name", "value": "numeric value with unit"}
                      ]
                    },
                    {
                      "type": "content",
                      "title": "section heading",
                      "bullets": ["concise point (max 15 words)", "..."]
                    },
                    {
                      "type": "table",
                      "title": "Robot Specifications",
                      "headers": ["Specification", "Details"],
                      "rows": [["spec name", "value"], "..."]
                    },
                    {
                      "type": "closing",
                      "title": "Next Steps",
                      "bullets": ["action item", "..."]
                    }
                  ]
                }

                Rules:
                - First slide must be type "title".
                - Include exactly 1 "key_stats" slide with 3-4 measurable numbers.
                - Include "content" slides for Executive Summary, Business Value, Limitations.
                - Include 1 "table" slide for robot specifications.
                - Last slide must be type "closing" with title "Next Steps".
                - Total 7-10 slides.
                - Bullets max 6 per slide, max 15 words each.
                - Extract real values from the proposal — do not invent data.
                - Use "Needs confirmation" for unknown values.

                PROPOSAL:
                %s
                """.formatted(proposalContent);
    }

    private SlideManifest parseSlideManifest(String text) {
        try {
            String json = extractJson(text);
            return objectMapper.readValue(json, SlideManifest.class);
        } catch (JsonProcessingException e) {
            log.warn("Could not parse slide manifest JSON: {}", e.getMessage());
            return null;
        }
    }

    // ─── TranslationAiService ─────────────────────────────────────────────────

    private static final String TRANSLATION_MODEL = "claude-haiku-4-5-20251001";

    @Override
    public List<String> translateToThai(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            String textsJson = objectMapper.writeValueAsString(texts);
            String system = "You are a professional Thai translator for a business robotics company. Translate naturally and professionally.";
            String user = """
                    Translate the following English texts to Thai (ภาษาไทย).

                    Return ONLY a valid JSON array with exactly %d elements in the same order as the input.
                    Each element must be the Thai translation of the corresponding input.
                    No explanation, no markdown, only the JSON array.

                    Input:
                    %s
                    """.formatted(texts.size(), textsJson);
            String response = callClaude(system, user, null, null, TRANSLATION_MODEL);
            return parseTranslationArray(response, texts);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise texts for translation", e);
            return texts;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTranslationArray(String text, List<String> fallback) {
        try {
            String trimmed = text.trim();
            int start = trimmed.indexOf('[');
            int end   = trimmed.lastIndexOf(']');
            if (start == -1 || end == -1 || end <= start) return fallback;
            List<String> result = objectMapper.readValue(trimmed.substring(start, end + 1), List.class);
            if (result.size() != fallback.size()) {
                log.warn("Translation returned {} items but expected {}; using originals", result.size(), fallback.size());
                return fallback;
            }
            return result;
        } catch (JsonProcessingException e) {
            log.warn("Could not parse translation response; using originals: {}", e.getMessage());
            return fallback;
        }
    }

    // ─── CmReportExtractionService ────────────────────────────────────────────

    /**
     * Field extraction from one pasted ticket — a small, well-specified task, so it
     * runs on Haiku rather than the default model. At roughly a cent per report the
     * cost is negligible, and every field is reviewed by a human before it is saved.
     */
    private static final String CM_MODEL = "claude-haiku-4-5-20251001";

    @Override
    public CmReportDraft extractCmReport(String sourceText) {
        String system = AiPromptTemplates.cmReportExtractionSystemPrompt();
        String user = """
                Extract the Corrective Maintenance report fields from the service ticket below.

                Return ONLY a valid JSON object — no explanation, no markdown code fences — with
                exactly these fields:
                {
                  "reportDate": "yyyy-MM-dd or null",
                  "ticketNo": "string or null",
                  "customerName": "string or null",
                  "technicianName": "string or null",
                  "robotModel": "string or null",
                  "serialNumber": "string or null",
                  "causeDetail": "string or null",
                  "inspectionResult": "string or null",
                  "correctiveActions": ["step", "step"],
                  "testResult": "string or null"
                }

                Ticket:
                ---
                %s
                ---
                """.formatted(sourceText);
        try {
            String response = callClaude(system, user, null, null, CM_MODEL);
            return objectMapper.readValue(extractJson(response), CmReportDraft.class);
        } catch (Exception e) {
            // Degrade rather than fail: the operator still gets the form, pre-filled with
            // the raw paste, and can type the fields in by hand.
            log.warn("CM report extraction failed — returning an empty draft: {}", e.getMessage());
            return new CmReportDraft(null, null, null, null, null, null, sourceText, null, List.of(), null);
        }
    }

    // ─── CaseSolutionAiService ────────────────────────────────────────────────

    /**
     * Paraphrasing a short comment thread into a few dated lines, once per ticket per
     * report. Sonnet 5 since 2026-09-16, up from Haiku: the Solution column is what the
     * customer reads, and the RE team wanted it to read better. Still cents per report —
     * the threads are short and every line is reviewed before a report is sent. The
     * report is frozen with whatever this produced, so a regenerated draft can differ
     * slightly. That is expected of a paraphrase.
     */
    private static final String CASE_SOLUTION_MODEL = "claude-sonnet-5";

    /**
     * The AOTGA part fields: four short values pulled from the same thread. Left on
     * Haiku when the Solution column moved up — it is extraction, not prose, and
     * nothing there reads better for a bigger model.
     */
    private static final String CASE_PARTS_MODEL = "claude-haiku-4-5-20251001";

    @Override
    public String summariseProgress(CaseProgressRequest request) {
        if (request.comments() == null || request.comments().isEmpty()) {
            return "";
        }

        StringBuilder thread = new StringBuilder();
        for (CaseProgressRequest.Comment c : request.comments()) {
            thread.append(c.postedOn()).append("  [").append(c.author()).append("]  ")
                  .append(c.body() == null ? "" : c.body().strip()).append('\n');
        }

        String user = """
                Report date: %s
                Branch: %s
                Problem: %s
                Current board status: %s
                Current sup status: %s

                Comment thread, oldest first (date, author, text):
                ---
                %s---

                Write the Solution line.
                """.formatted(
                request.asOf(),
                nullToDash(request.branch()),
                nullToDash(request.problem()),
                nullToDash(request.currentStatus()),
                nullToDash(request.currentSupStatus()),
                thread);

        try {
            String response = callClaude(
                    AiPromptTemplates.caseSolutionSystemPrompt(), user, null, null, CASE_SOLUTION_MODEL);
            // One line was asked for; take the first non-empty one in case the model
            // wrapped it in whitespace anyway.
            return response == null ? "" : response.lines()
                    .map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        } catch (Exception e) {
            // An empty cell is reviewable; a failed report is not.
            log.warn("Case solution summary failed for {} — leaving the cell empty: {}",
                    request.branch(), e.getMessage());
            return "";
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    // ─── CasePartsAiService ───────────────────────────────────────────────────

    /**
     * Same per-ticket shape as {@link #summariseProgress}: the thread is short, the
     * answer is four fields, and every row is reviewed before the report is sent.
     */
    @Override
    public CasePartsSummary extractParts(CaseProgressRequest request) {
        if (request.comments() == null || request.comments().isEmpty()) {
            return CasePartsSummary.EMPTY;
        }

        StringBuilder thread = new StringBuilder();
        for (CaseProgressRequest.Comment c : request.comments()) {
            thread.append(c.postedOn()).append("  [").append(c.author()).append("]  ")
                  .append(c.body() == null ? "" : c.body().strip()).append('\n');
        }

        String user = """
                Report date: %s
                Site: %s
                Problem: %s
                Current board status: %s
                Current sup status: %s

                Comment thread, oldest first (date, author, text):
                ---
                %s---

                Reply with the JSON object.
                """.formatted(
                request.asOf(),
                nullToDash(request.branch()),
                nullToDash(request.problem()),
                nullToDash(request.currentStatus()),
                nullToDash(request.currentSupStatus()),
                thread);

        try {
            String response = callClaude(
                    AiPromptTemplates.casePartsSystemPrompt(), user, null, null, CASE_PARTS_MODEL);
            return CasePartsSummary.parse(response, objectMapper);
        } catch (Exception e) {
            // Dashes are reviewable; a failed report is not.
            log.warn("Case parts extraction failed for {} — leaving the cells empty: {}",
                    request.branch(), e.getMessage());
            return CasePartsSummary.EMPTY;
        }
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────────

    private String callClaude(String systemPrompt, String userPrompt, byte[] fileBytes, String contentType, String claudeModel) {
        List<Map<String, Object>> contentBlocks = buildContentBlocks(userPrompt, fileBytes, contentType);
        Map<String, Object> requestBody = Map.of(
                "model", claudeModel,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", contentBlocks))
        );

        AnthropicResponse response = restClient.post()
                .body(requestBody)
                .retrieve()
                .body(AnthropicResponse.class);

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new RuntimeException("Empty response received from Claude API");
        }

        // A response cut off at the token ceiling is not malformed JSON, it is an
        // unfinished answer -- and it used to reach the parser as one, which reported
        // "unreadable response" and sent whoever was waiting looking for a bug in the
        // prompt. The recommendation is the worst case: extractJson takes the LAST
        // closing brace, so a truncated reply yields a plausible-looking fragment
        // ending inside a half-written option.
        if ("max_tokens".equals(response.stopReason())) {
            log.error("Claude response for model {} was truncated at the {}-token ceiling",
                    claudeModel, maxTokens);
            throw new BadRequestException(
                    "The AI response was cut off before it finished (token limit reached). "
                            + "Try again, or reduce the number of options requested.");
        }

        return response.content().stream()
                .filter(b -> "text".equals(b.type()))
                .map(AnthropicContentBlock::text)
                .reduce("", String::concat);
    }

    private List<Map<String, Object>> buildContentBlocks(
            String userPrompt, byte[] fileBytes, String contentType) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        if (fileBytes != null && contentType != null) {
            String mime = contentType.toLowerCase();
            if (mime.contains("pdf")) {
                blocks.add(documentBlock("application/pdf", fileBytes));
                blocks.add(textBlock(userPrompt));
                return blocks;
            }
            if (mime.contains("image")) {
                blocks.add(imageBlock(mime, fileBytes));
                blocks.add(textBlock(userPrompt));
                return blocks;
            }
            if (mime.contains("excel") || mime.contains("spreadsheet") || mime.contains("xls")) {
                String extracted = extractExcelText(fileBytes);
                blocks.add(textBlock("Survey document content:\n" + extracted + "\n\n" + userPrompt));
                return blocks;
            }
            // Plain text or CSV
            blocks.add(textBlock("Survey document content:\n" + new String(fileBytes) + "\n\n" + userPrompt));
            return blocks;
        }

        blocks.add(textBlock(userPrompt));
        return blocks;
    }

    private static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> imageBlock(String mime, byte[] bytes) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", normaliseImageMime(mime));
        source.put("data", Base64.getEncoder().encodeToString(bytes));
        return Map.of("type", "image", "source", source);
    }

    private static Map<String, Object> documentBlock(String mime, byte[] bytes) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", mime);
        source.put("data", Base64.getEncoder().encodeToString(bytes));
        return Map.of("type", "document", "source", source);
    }

    private static String normaliseImageMime(String raw) {
        if (raw.contains("jpeg") || raw.contains("jpg")) return "image/jpeg";
        if (raw.contains("png"))  return "image/png";
        if (raw.contains("gif"))  return "image/gif";
        if (raw.contains("webp")) return "image/webp";
        return "image/jpeg";
    }

    private String extractExcelText(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : workbook) {
                sb.append("Sheet: ").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(formatter.formatCellValue(cell)).append("\t");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Excel text extraction failed — will use raw content fallback", e);
            return "[Excel content could not be extracted]";
        }
    }

    /** Strips markdown code fences and returns the first {...} block found. */
    private static String extractJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end   = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ─── Anthropic API response types ─────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnthropicResponse(
            List<AnthropicContentBlock> content,
            /** "end_turn" when finished; "max_tokens" when cut off at the ceiling. */
            @JsonProperty("stop_reason") String stopReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnthropicContentBlock(String type, String text) {}
}
