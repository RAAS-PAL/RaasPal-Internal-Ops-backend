package com.raaspal.robotrecommendation.casereport.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the model is given to write one ticket's Solution line.
 *
 * <p>The Solution column on the delivery report is not a board field — the board's
 * column for it is empty on seven tickets in eight. It is a human paraphrase of the
 * comment thread: one short dated line per update, in a house style, e.g.
 * {@code 17-Aug อยู่ระหว่างตรวจสอบและประเมินอาการหุ่นยนต์ 18-Aug อยู่ระหว่าง MK Approve รายการแบตเตอรี่}.
 * Verified against M575 โลตัสหนองจอก on the 09 September 2026 workbook: every line maps
 * to a comment by date, and none is quoted verbatim.
 *
 * <p>Comments are passed oldest first, which is the order the line is written in.
 *
 * @param currentStatus  the board's Status label now — the last line often has to state
 *                       this, because the thread trails off before the current state
 * @param asOf           the report date; the final entry runs up to it
 */
public record CaseProgressRequest(
        String branch,
        String problem,
        String currentStatus,
        String currentSupStatus,
        LocalDate asOf,
        List<Comment> comments
) {

    /**
     * @param author kept because the {@code *status*} marker convention is one person's
     *               habit, and a prompt can weight by author because of it
     */
    public record Comment(LocalDate postedOn, String author, String body) {
    }
}
