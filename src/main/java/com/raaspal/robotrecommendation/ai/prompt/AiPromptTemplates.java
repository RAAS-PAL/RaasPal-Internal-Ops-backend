package com.raaspal.robotrecommendation.ai.prompt;

import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;

public final class AiPromptTemplates {

    public static String extractionSystemPrompt() {
        return """
                You extract structured customer robot requirements for RAASPAL internal team review.
                Follow these rules:
                - %s
                """.formatted(String.join("\n- ", AiPromptRules.MVP_RULES));
    }

    public static String recommendationSystemPrompt(RequirementResponse requirement) {
        return """
                You recommend 2-3 robot solution options for RAASPAL.
                Requirement ID: %s
                Robot type: %s

                Follow these rules:
                - %s
                """.formatted(
                requirement.id(),
                requirement.robotType(),
                String.join("\n- ", AiPromptRules.MVP_RULES)
        );
    }

    public static String proposalSystemPrompt() {
        return """
                You generate a customer-facing robot solution proposal for RAASPAL.
                Follow the stored proposal template style when provided.
                Do not copy irrelevant template details.
                Use "%s" for missing or unconfirmed details.
                """.formatted(AiPromptRules.NEEDS_CONFIRMATION);
    }

    /**
     * System prompt for parsing a pasted Corrective Maintenance ticket.
     * <p>
     * The overriding rule is <em>transcription, not authorship</em>: the output goes
     * onto a signed customer document, so the Thai source text must survive
     * byte-for-byte. The MVP rules are deliberately not applied here — the
     * "Needs confirmation" sentinel would print as literal text on the report, and
     * a missing field must render as an empty row instead.
     */
    public static String cmReportExtractionSystemPrompt() {
        return """
                You extract fields from a robot service ticket for a RAASPAL Corrective
                Maintenance report (รายงานการซ่อมบำรุงแก้ไข).

                Follow these rules exactly:
                - Copy text VERBATIM from the ticket. Do not translate, summarise, rephrase,
                  correct spelling, or change punctuation. Thai text must stay in Thai.
                - Never invent a value. If a field is not present in the ticket, return null.
                - Do not add commentary, headings, or placeholder text of any kind.
                - The ticket often labels its sections in Thai. Map them as follows:
                  วันที่ -> reportDate, Ticket No. -> ticketNo,
                  ชื่อบริษัทลูกค้า -> customerName, เจ้าหน้าที่ผู้เข้าดำเนินการ -> technicianName,
                  รุ่นหุ่นยนต์ -> robotModel, Serial Number -> serialNumber,
                  รายละเอียดของสาเหตุ -> causeDetail, ผลการตรวจสอบ -> inspectionResult,
                  การดำเนินการแก้ไข -> correctiveActions, ผลการทดสอบ -> testResult.
                - "รายละเอียดการแก้ไข" is a container, not a field: split its contents into
                  inspectionResult, correctiveActions, and testResult.
                - correctiveActions is a JSON array of steps in order. Strip any leading
                  numbering ("1.", "2)", "-") — the report applies its own numbering.
                - reportDate must be ISO yyyy-MM-dd. Thai dates are usually Buddhist-era:
                  subtract 543 from the year (2569 -> 2026). Thai month names map to
                  มกราคม=01 กุมภาพันธ์=02 มีนาคม=03 เมษายน=04 พฤษภาคม=05 มิถุนายน=06
                  กรกฎาคม=07 สิงหาคม=08 กันยายน=09 ตุลาคม=10 พฤศจิกายน=11 ธันวาคม=12.
                """;
    }

    private AiPromptTemplates() {
    }
}
