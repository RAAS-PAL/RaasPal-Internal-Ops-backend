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

    /**
     * The Solution line of a pending-case report.
     *
     * <p>The style rules below are transcribed from the RE team's own workbook, not
     * invented: the 09 September 2026 file was compared line by line against the
     * comment threads it was written from. Each report line paraphrases one comment,
     * keeps its date, and uses a small fixed vocabulary for in-progress states.
     */
    public static String caseSolutionSystemPrompt() {
        return """
                You write the "Solution" column of RAASPAL's daily pending-case report for a
                robot service ticket, from the ticket's comment thread.

                The column is a short dated log of what has happened, in Thai, in the
                RE team's house style. Examples of real lines from the report:
                  17-Aug อยู่ระหว่างตรวจสอบและประเมินอาการหุ่นยนต์
                  18-Aug อยู่ระหว่าง MK Approve รายการแบตเตอรี่
                  24-Aug MK Approved ใบเสนอราคา อยู่ระหว่างจัดส่งอะไหล่
                  25-26 Aug รออะไหล่แบตเตอรี่
                  04-Sep อยู่ระหว่างจัดส่งอะไหล่ 11-Sep เจ้าหน้าที่เข้าซ่อม

                Rules:
                - Output ONE line: the entries joined by single spaces, in date order,
                  oldest first. No bullets, no line breaks, no heading, no quotes.
                - Each entry starts with the date as DD-Mon (e.g. 17-Aug, 03-Sep), then a
                  short Thai phrase of roughly 3 to 10 words. English product and company
                  names stay in English (MK, Yayoi, QO, LiDAR, Control Board).
                - Paraphrase into the house vocabulary. Do NOT copy comments verbatim.
                  In-progress states begin with อยู่ระหว่าง (e.g. อยู่ระหว่างตรวจสอบ,
                  อยู่ระหว่างจัดส่งอะไหล่, อยู่ระหว่าง MK อนุมัติใบเสนอราคา). Waiting states
                  begin with รอ (e.g. รออะไหล่, รอลูกค้ายืนยัน). A scheduled visit is
                  เจ้าหน้าที่เข้าซ่อม or เจ้าหน้าที่เข้าดำเนินการ.
                - One entry per meaningful step. Skip pleasantries, file names, phone
                  numbers, and internal chatter. Merge comments from the same day that
                  describe the same step.
                - When the same state continues across consecutive days with no new
                  step, write a range as DD-DD Mon: 25-26 Aug รออะไหล่แบตเตอรี่. A range
                  must stay inside one month. Never write one across two months such as
                  25-11-Sep; if a state runs from August into September, end the range at
                  the last day of August (25-31 Aug) and continue with a dated entry in
                  September (01-Sep or the actual next date).
                - The first comment on a ticket is usually the contact centre's intake
                  form (it begins with "Ticket ID"). It is not a step; do not log it.
                - Never invent a step that is not supported by a comment or the current
                  status. If the thread is empty or says nothing useful, output an
                  empty line rather than a guess.
                - If the current board status describes a state the last comment does
                  not, finish with that state, dated from the last comment to the
                  report date.
                - A date that belongs inside a phrase — a planned visit, a promised
                  delivery — is written in words, not as a date token: อยู่ระหว่างแพลนเข้า
                  ดำเนินการอีกครั้งวันที่ 17, never "...อีกครั้ง 17-Sep". A date token
                  always begins an entry.
                - Do not add commentary, explanation, or anything after the line.
                """;
    }

    /**
     * The RAW_AOTGA sheet's part-tracking cells, read out of a ticket's comment thread.
     * The reply is one JSON object; {@code CasePartsSummary.parse} reads it leniently.
     */
    public static String casePartsSystemPrompt() {
        return """
                You fill four cells of RAASPAL's daily spare-parts report for an airport
                (AOTGA) robot service ticket, from the ticket's comment thread. The report
                tracks the part, not the repair: what was ordered, who it is waited on,
                and when it arrived.

                Reply with ONE JSON object and nothing else - no code fence, no prose:
                  {"required_part": ..., "waiting": ..., "waiting_from": ..., "part_received": ...}

                Fields:
                - required_part: the spare part this case needs, named the way the team
                  names it. Keep English part names in English. Examples from real rows:
                  "Potentiometer", "Front wheel motor + Brake assembly", "4G Module",
                  "Box Control", "Side Brush Motor", "Cover ด้านหลัง", "ท่อน้ำเสีย",
                  "Potentiometer และ Encoder". If several parts, join with " และ " or " + ".
                - waiting: what the case is waiting on right now, one short Thai phrase in
                  the team's wording, beginning with รอ or อยู่ระหว่าง. Examples:
                  "รออะไหล่เสียคืนจาก AOTGA", "รออะไหล่มือ 1 จาก Supplier RAASPAL",
                  "อยู่ระหว่าง RAASPAL ตรวจสอบอะไหล่", "รออะไหล่ Supplier RAASPAL 20/09/26".
                  Include a promised date if the thread gives one.
                - waiting_from: exactly one of "AOTGA", "Supplier RAASPAL", "RAASPAL" -
                  whose action the case is waiting on. AOTGA when the airport must return
                  the faulty part or approve something; Supplier RAASPAL when a supplier
                  is shipping to RAASPAL; RAASPAL when RAASPAL itself is checking, testing
                  or fitting.
                - part_received: the date the replacement part reached RAASPAL or the site,
                  as YYYY-MM-DD, taken from a comment that says it arrived. If it has not
                  arrived, or the thread does not say, null.

                Rules:
                - Use null for any field the thread does not settle. Never guess a part
                  name, a date, or who is waited on. A blank cell is correct; a wrong one
                  sends a technician to the wrong shelf.
                - Comment dates are given; a comment saying a part arrived "today" or
                  "วันนี้" means that comment's date.
                - The first comment on a ticket is usually the contact centre's intake
                  form (it begins with "Ticket ID"). It describes the request, not the
                  part; do not take a part name from it unless a later comment confirms.
                - The current board status may describe the present state better than the
                  last comment; use it for waiting / waiting_from when the thread trails off.
                """;
    }
}
