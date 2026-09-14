package com.raaspal.robotrecommendation.casereport.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The four part-tracking cells of one RAW_AOTGA row, as read out of a ticket's comment
 * thread.
 *
 * <p>The cleaning board has columns for these, but the RE team does not fill them —
 * checked 2026-09-14: zero of the twelve open airport tickets had a value in any of them,
 * while every one had a comment thread. So, like the Solution column on the other sheets,
 * these are written from the thread by the model. Any field the thread does not settle is
 * null, and the sheet prints a dash; a guess in a parts column would send a technician to
 * the wrong shelf.
 *
 * @param requiredPart the part ordered for this case, as the team names it
 *                     ("Potentiometer", "Front wheel motor + Brake assembly")
 * @param waiting      what the case is waiting on now, in the team's words
 * @param waitingFrom  whose court it is in: {@code AOTGA}, {@code Supplier RAASPAL} or
 *                     {@code RAASPAL}
 * @param partReceived the date the part arrived, or null while it is still on its way
 */
public record CasePartsSummary(
        String requiredPart,
        String waiting,
        String waitingFrom,
        LocalDate partReceived
) {

    /** Nothing known — every cell blank. What the services return rather than null. */
    public static final CasePartsSummary EMPTY = new CasePartsSummary(null, null, null, null);

    public boolean isEmpty() {
        return requiredPart == null && waiting == null && waitingFrom == null && partReceived == null;
    }

    /**
     * Reads the model's reply, which was asked to be one JSON object with these four keys.
     *
     * <p>Lenient on purpose: a code fence around the object, prose before or after it, a
     * missing key, an empty string, a {@code "null"} literal or an unparseable date all
     * degrade to a null field rather than an exception — an empty cell is reviewable, a
     * failed report is not. Returns {@link #EMPTY} when no object can be found at all.
     */
    public static CasePartsSummary parse(String modelText, ObjectMapper mapper) {
        if (modelText == null) return EMPTY;
        int open = modelText.indexOf('{');
        int close = modelText.lastIndexOf('}');
        if (open < 0 || close <= open) return EMPTY;

        JsonNode node;
        try {
            node = mapper.readTree(modelText.substring(open, close + 1));
        } catch (Exception e) {
            return EMPTY;
        }
        if (node == null || !node.isObject()) return EMPTY;

        return new CasePartsSummary(
                text(node, "required_part"),
                text(node, "waiting"),
                text(node, "waiting_from"),
                date(node, "part_received"));
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() || s.equalsIgnoreCase("null") || s.equals("-") ? null : s;
    }

    private static LocalDate date(JsonNode node, String key) {
        String s = text(node, key);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
