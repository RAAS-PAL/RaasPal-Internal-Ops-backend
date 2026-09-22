package com.raaspal.robotrecommendation.reassignment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Decides, for every open CM ticket, who should take it - or why nobody should be
 * suggested automatically.
 *
 * <p>Pure: no database, no monday, no clock except the {@code today}/{@code now} passed
 * in. The same inputs always give the same queue, so the rules are tested with plain
 * objects and the approver can be shown exactly why a name came out on top.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Scope.</b> Only a ticket whose "Type of Robot" label is MAPPED to a matrix model
 *       is suggested. Unmapped, UNCONFIRMED and MANUAL labels stay manual, with the reason.
 *       Non-CM case types (Request, Training...) are manual too.</li>
 *   <li><b>Qualification (hard).</b> The Issue Level sets a required level - by default
 *       L1-Easy→L2, L2-Mid→L3, L3-Hard→L4. An engineer qualifies only when <em>both</em>
 *       their level on that model and their CM-Cleaning level meet it. "-" never qualifies.
 *       A blank Issue Level assumes L2-Mid and flags the suggestion.</li>
 *   <li><b>Availability (hard).</b> Active, not on leave today, and the new ticket fits
 *       under their load limit. Load is the status-weighted sum of the open tickets they
 *       hold on monday plus approvals not yet on monday.</li>
 *   <li><b>Score (soft), higher wins:</b>
 *       <pre>S = 100 - 8·M - 30·U + 2·E + 3·F + 4·D - 20·R</pre>
 *       M = levels above the requirement (model + CM), so an L4 is not spent on easy work;
 *       U = projected load / limit; E = the expertise level matching the issue keywords
 *       (electrical / mechanical / software, else troubleshooting); F = days since their
 *       last approved assignment, capped at 14, as a share of 14 (rotation);
 *       D = 1 for an easy ticket that an exactly-L2 engineer can grow on;
 *       R = 1 for an easy ticket when this engineer is one of at most two available who
 *       could take hard work on the model (keep scarce experts free).</li>
 * </ol>
 * Tickets are processed most urgent first; each suggestion adds provisional load to the
 * suggested engineer, so one person is not suggested for the whole queue.
 */
public final class ReAssignmentEvaluator {

    public static final String CM_SKILL = "CM_CLEANING";

    /** Skill codes of the Cleaning expertise columns, by issue category. */
    static final Map<String, String> EXPERTISE_SKILL = Map.of(
            "ELECTRICAL", "ELECTRICAL_CLEANING",
            "MECHANICAL", "MECHANICAL_CLEANING",
            "SOFTWARE", "SOFTWARE_CLEANING",
            "TROUBLESHOOTING", "TROUBLESHOOTING_CLEANING");

    /** Thai and English keywords, matched case-insensitively in the issue text and ticket name. */
    static final Map<String, List<String>> KEYWORDS = Map.of(
            "ELECTRICAL", List.of("battery", "แบต", "charg", "ชาร์จ", "power", "ไฟ", "volt", "fuse", "ฟิวส์",
                    "สายไฟ", "dock", "board", "บอร์ด", "เปิดไม่ติด"),
            "MECHANICAL", List.of("wheel", "ล้อ", "brush", "แปรง", "motor", "มอเตอร์", "squeegee", "ยางปาด",
                    "bumper", "belt", "สายพาน", "pump", "ปั๊ม", "vacuum", "ดูด", "รั่ว", "leak", "น้ำ"),
            "SOFTWARE", List.of("map", "แผนที่", "app", "แอป", "network", "wifi", "wi-fi", "internet", "เน็ต",
                    "software", "ซอฟต์แวร์", "update", "อัพเดท", "cloud", "server", "location", "โลเคชั่น",
                    "navigation", "นำทาง", "login", "ระบบ", "error"));

    private ReAssignmentEvaluator() {
    }

    /* ─── Inputs ──────────────────────────────────────────────────────────── */

    public record Person(String id, String name) {
    }

    public record Ticket(String itemId, String name, String group, String status, String subStatus,
                         String modelLabel, String issueLevel, String caseType, String serviceMode,
                         String serial, String customer, String branch, String mainIssue,
                         LocalDate openDate, LocalDate actionDate, List<Person> people, Instant firstSeenAt) {
    }

    public record Engineer(UUID id, String name, boolean active, String mondayUserId, boolean hasEmail,
                           BigDecimal maxLoad, Map<String, Integer> levels, boolean onLeave,
                           Instant lastApprovedAt) {
    }

    /** A board label's mapping: disposition MAPPED / MANUAL / UNCONFIRMED, and the model skill when MAPPED. */
    public record Mapping(String disposition, String skillCode, String modelName) {
    }

    /** An approval already recorded for a ticket (APPROVED or CONFIRMED, still current). */
    public record Existing(UUID assignmentId, UUID engineerId, String status) {
    }

    public record Policy(Set<String> activeGroups, Set<String> closedStatuses, Set<String> nonCmCaseTypes,
                         List<String> urgencyOrder, Map<String, BigDecimal> statusLoad, BigDecimal defaultLoad,
                         BigDecimal newTicketLoad, Map<String, Integer> requiredLevels, String assumedIssueLevel) {
    }

    public record Input(List<Ticket> tickets, List<Engineer> engineers, Map<String, Mapping> mappings,
                        Map<String, Existing> existing, Set<String> held, Policy policy,
                        LocalDate today, Instant now) {
    }

    /* ─── Outputs ─────────────────────────────────────────────────────────── */

    public enum Outcome {
        /** A qualified, available engineer is proposed. */
        SUGGESTED,
        /** Approved in the console, waiting for the engineer to appear on monday. */
        APPROVED,
        /** The board's RE column already has someone. */
        ASSIGNED,
        /** Outside the matrix, not CM, or no model - the Senior RE assigns by hand. */
        MANUAL,
        /** The Senior RE rejected auto-suggestion for this ticket. */
        HELD,
        /** Nobody in the matrix has the levels this ticket needs. */
        NO_QUALIFIED,
        /** People qualify, but every one of them is at their limit or on leave. */
        ALL_BUSY
    }

    public record Candidate(UUID engineerId, String name, Integer modelLevel, Integer cmLevel,
                            Integer expertiseLevel, BigDecimal currentLoad, BigDecimal projectedLoad,
                            BigDecimal maxLoad, BigDecimal score, Map<String, BigDecimal> components,
                            boolean hasEmail) {
    }

    public record Exclusion(UUID engineerId, String name, String reason, Integer modelLevel, Integer cmLevel) {
    }

    public record Evaluation(Ticket ticket, Outcome outcome, String reason, String modelSkill, String modelName,
                             Integer requiredLevel, boolean assumedDifficulty, String issueCategory,
                             Candidate suggested, List<Candidate> alternatives, List<Exclusion> excluded,
                             Existing existing) {
    }

    public record Workload(BigDecimal load, int openTickets) {
    }

    /* ─── Evaluation ──────────────────────────────────────────────────────── */

    public static List<Evaluation> evaluate(Input in) {
        Policy p = in.policy();

        // Current load of every engineer, from the open tickets they hold on monday.
        Map<UUID, Workload> workload = workload(in.tickets(), in.engineers(), p);
        Map<UUID, BigDecimal> load = new HashMap<>();
        workload.forEach((id, w) -> load.put(id, w.load()));

        // Approvals not yet visible on monday are work too.
        Map<String, Ticket> byItem = new HashMap<>();
        in.tickets().forEach(t -> byItem.put(t.itemId(), t));
        Map<UUID, Engineer> engineers = new LinkedHashMap<>();
        in.engineers().forEach(e -> engineers.put(e.id(), e));
        in.existing().forEach((itemId, ex) -> {
            Engineer e = engineers.get(ex.engineerId());
            Ticket t = byItem.get(itemId);
            boolean visible = e != null && t != null && e.mondayUserId() != null
                    && t.people().stream().anyMatch(pp -> e.mondayUserId().equals(pp.id()));
            if (!visible) load.merge(ex.engineerId(), p.newTicketLoad(), BigDecimal::add);
        });

        List<Ticket> queue = in.tickets().stream()
                .filter(t -> inActiveGroup(t, p) && !closed(t, p))
                .sorted(queueOrder(p))
                .toList();

        List<Evaluation> out = new ArrayList<>();
        for (Ticket t : queue) {
            out.add(evaluateOne(t, in, engineers, load));
        }
        return out;
    }

    private static Evaluation evaluateOne(Ticket t, Input in, Map<UUID, Engineer> engineers, Map<UUID, BigDecimal> load) {
        Policy p = in.policy();
        Existing ex = in.existing().get(t.itemId());

        Mapping mapping = t.modelLabel() == null ? null : in.mappings().get(t.modelLabel().trim());
        String modelSkill = mapping != null && "MAPPED".equals(mapping.disposition()) ? mapping.skillCode() : null;
        String modelName = mapping == null ? null : mapping.modelName();

        if (!t.people().isEmpty()) {
            return result(t, Outcome.ASSIGNED, "RE already set on monday", modelSkill, modelName, null, false, null, ex);
        }
        if (ex != null) {
            return result(t, Outcome.APPROVED, "Approved - waiting for the RE to be set on monday",
                    modelSkill, modelName, null, false, null, ex);
        }
        if (in.held().contains(t.itemId())) {
            return result(t, Outcome.HELD, "Taken out of auto-suggestion by the Senior RE", modelSkill, modelName,
                    null, false, null, null);
        }
        if (t.caseType() != null && p.nonCmCaseTypes().contains(norm(t.caseType()))) {
            return result(t, Outcome.MANUAL, "Not a CM case (" + t.caseType() + ")", modelSkill, modelName,
                    null, false, null, null);
        }
        if (t.modelLabel() == null || t.modelLabel().isBlank()) {
            return result(t, Outcome.MANUAL, "Type of Robot is blank", null, null, null, false, null, null);
        }
        if (mapping == null) {
            return result(t, Outcome.MANUAL, "\"" + t.modelLabel() + "\" is not mapped to a skill-matrix model",
                    null, null, null, false, null, null);
        }
        if (modelSkill == null) {
            String why = "UNCONFIRMED".equals(mapping.disposition())
                    ? "\"" + t.modelLabel() + "\" mapping awaits confirmation"
                    : "\"" + t.modelLabel() + "\" is not in the skill matrix";
            return result(t, Outcome.MANUAL, why, null, null, null, false, null, null);
        }

        boolean assumed = t.issueLevel() == null || t.issueLevel().isBlank()
                || !p.requiredLevels().containsKey(norm(t.issueLevel()));
        Integer required = p.requiredLevels().get(norm(assumed ? p.assumedIssueLevel() : t.issueLevel()));
        if (required == null) required = 3;
        String category = issueCategory(t);

        List<Candidate> eligible = new ArrayList<>();
        List<Exclusion> excluded = new ArrayList<>();
        List<Engineer> qualified = new ArrayList<>();

        for (Engineer e : engineers.values()) {
            Integer model = e.levels().get(modelSkill);
            Integer cm = e.levels().get(CM_SKILL);
            String skillGap = skillGap(model, cm, required);
            if (!e.active()) {
                excluded.add(new Exclusion(e.id(), e.name(), "Inactive", model, cm));
                continue;
            }
            if (skillGap != null) {
                excluded.add(new Exclusion(e.id(), e.name(), skillGap, model, cm));
                continue;
            }
            qualified.add(e);
            if (e.onLeave()) {
                excluded.add(new Exclusion(e.id(), e.name(), "On leave today", model, cm));
                continue;
            }
            BigDecimal current = load.getOrDefault(e.id(), BigDecimal.ZERO);
            BigDecimal projected = current.add(p.newTicketLoad());
            if (projected.compareTo(e.maxLoad()) > 0) {
                excluded.add(new Exclusion(e.id(), e.name(),
                        "At load limit (" + fmt(current) + " of " + fmt(e.maxLoad()) + ")", model, cm));
                continue;
            }
            eligible.add(new Candidate(e.id(), e.name(), model, cm, null, current, projected, e.maxLoad(),
                    BigDecimal.ZERO, Map.of(), e.hasEmail()));
        }

        if (qualified.isEmpty()) {
            return new Evaluation(t, Outcome.NO_QUALIFIED,
                    "Nobody has L" + required + " on " + modelName + " and on CM Cleaning",
                    modelSkill, modelName, required, assumed, category, null, List.of(), excluded, null);
        }
        if (eligible.isEmpty()) {
            return new Evaluation(t, Outcome.ALL_BUSY,
                    qualified.size() + " engineer(s) qualify but all are at their limit or on leave",
                    modelSkill, modelName, required, assumed, category, null, List.of(), excluded, null);
        }

        // How many available engineers could take HARD work on this model - for R.
        int hardCapable = (int) eligible.stream()
                .filter(c -> c.modelLevel() != null && c.cmLevel() != null && c.modelLevel() >= 4 && c.cmLevel() >= 4)
                .count();

        List<Candidate> scored = new ArrayList<>();
        for (Candidate c : eligible) {
            Engineer e = engineers.get(c.engineerId());
            scored.add(score(c, e, required, category, hardCapable, in.now()));
        }
        scored.sort(Comparator.comparing(Candidate::score).reversed()
                .thenComparing(Candidate::projectedLoad)
                .thenComparing(Candidate::name, Comparator.nullsLast(Comparator.naturalOrder())));

        Candidate best = scored.get(0);
        load.merge(best.engineerId(), p.newTicketLoad(), BigDecimal::add);

        String reason = best.name() + ": L" + best.modelLevel() + " on " + modelName + ", L" + best.cmLevel()
                + " CM, load " + fmt(best.currentLoad()) + " of " + fmt(best.maxLoad())
                + (assumed ? " - difficulty not set, assumed " + p.assumedIssueLevel() : "");
        return new Evaluation(t, Outcome.SUGGESTED, reason, modelSkill, modelName, required, assumed, category,
                best, scored.subList(1, Math.min(scored.size(), 5)), excluded, null);
    }

    static Candidate score(Candidate c, Engineer e, int required, String category, int hardCapable, Instant now) {
        int m = (c.modelLevel() - required) + (c.cmLevel() - required);
        BigDecimal u = c.projectedLoad().divide(c.maxLoad(), 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        Integer expertise = e.levels().get(EXPERTISE_SKILL.get(category));
        int ex = expertise == null ? 0 : expertise;
        BigDecimal f = e.lastApprovedAt() == null ? BigDecimal.ONE
                : BigDecimal.valueOf(Math.min(ChronoUnit.DAYS.between(e.lastApprovedAt(), now), 14))
                        .divide(BigDecimal.valueOf(14), 4, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        int d = required == 2 && c.modelLevel() == 2 && c.cmLevel() == 2 ? 1 : 0;
        int r = required == 2 && c.modelLevel() >= 4 && c.cmLevel() >= 4 && hardCapable <= 2 ? 1 : 0;

        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        parts.put("base", BigDecimal.valueOf(100));
        parts.put("overQualified", BigDecimal.valueOf(-8L * m));
        parts.put("load", u.multiply(BigDecimal.valueOf(-30)));
        parts.put("expertise", BigDecimal.valueOf(2L * ex));
        parts.put("rotation", f.multiply(BigDecimal.valueOf(3)));
        parts.put("development", BigDecimal.valueOf(4L * d));
        parts.put("keepExpertFree", BigDecimal.valueOf(-20L * r));
        BigDecimal s = parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        parts.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));
        return new Candidate(c.engineerId(), c.name(), c.modelLevel(), c.cmLevel(), expertise, c.currentLoad(),
                c.projectedLoad(), c.maxLoad(), s, parts, c.hasEmail());
    }

    /** Null when both levels meet the requirement, otherwise the reason they do not. */
    static String skillGap(Integer model, Integer cm, int required) {
        if (model == null) return "Not assessed on this model";
        if (cm == null) return "Not assessed on CM Cleaning";
        if (model < required) return "Model L" + model + " < L" + required;
        if (cm < required) return "CM L" + cm + " < L" + required;
        return null;
    }

    /**
     * Status-weighted load and open-ticket count per engineer, from the open tickets in the
     * active groups where their monday id is in the RE column. A ticket shared by several
     * people counts in full for each: splitting an unplanned job between them would
     * understate everyone's load.
     */
    public static Map<UUID, Workload> workload(List<Ticket> tickets, List<Engineer> engineers, Policy p) {
        Map<String, UUID> byMonday = new HashMap<>();
        for (Engineer e : engineers) {
            if (e.mondayUserId() != null && !e.mondayUserId().isBlank()) byMonday.put(e.mondayUserId(), e.id());
        }
        Map<UUID, BigDecimal> load = new HashMap<>();
        Map<UUID, Integer> count = new HashMap<>();
        for (Ticket t : tickets) {
            if (!inActiveGroup(t, p) || closed(t, p)) continue;
            BigDecimal w = p.statusLoad().getOrDefault(norm(t.status()), p.defaultLoad());
            for (Person person : t.people()) {
                UUID id = byMonday.get(person.id());
                if (id == null) continue;
                load.merge(id, w, BigDecimal::add);
                count.merge(id, 1, Integer::sum);
            }
        }
        Map<UUID, Workload> out = new HashMap<>();
        for (Engineer e : engineers) {
            out.put(e.id(), new Workload(load.getOrDefault(e.id(), BigDecimal.ZERO), count.getOrDefault(e.id(), 0)));
        }
        return out;
    }

    /** The first keyword category found in the issue text and ticket name, else TROUBLESHOOTING. */
    static String issueCategory(Ticket t) {
        String text = (Objects.toString(t.mainIssue(), "") + " " + Objects.toString(t.name(), "")).toLowerCase(Locale.ROOT);
        for (String cat : List.of("ELECTRICAL", "MECHANICAL", "SOFTWARE")) {
            for (String k : KEYWORDS.get(cat)) {
                if (text.contains(k)) return cat;
            }
        }
        return "TROUBLESHOOTING";
    }

    static Comparator<Ticket> queueOrder(Policy p) {
        return Comparator
                .comparingInt((Ticket t) -> urgencyRank(t, p))
                .thenComparing((Ticket t) -> -difficulty(t))
                .thenComparing(Ticket::openDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Ticket::itemId);
    }

    private static int urgencyRank(Ticket t, Policy p) {
        String type = norm(t.caseType());
        for (int i = 0; i < p.urgencyOrder().size(); i++) {
            if (norm(p.urgencyOrder().get(i)).equals(type)) return i;
        }
        return p.urgencyOrder().size();
    }

    private static int difficulty(Ticket t) {
        String l = norm(t.issueLevel());
        return l.startsWith("l3") ? 3 : l.startsWith("l2") ? 2 : l.startsWith("l1") ? 1 : 0;
    }

    private static boolean inActiveGroup(Ticket t, Policy p) {
        return t.group() != null && p.activeGroups().contains(norm(t.group()));
    }

    private static boolean closed(Ticket t, Policy p) {
        return t.status() != null && p.closedStatuses().contains(norm(t.status()));
    }

    private static Evaluation result(Ticket t, Outcome o, String reason, String modelSkill, String modelName,
                                     Integer required, boolean assumed, String category, Existing ex) {
        return new Evaluation(t, o, reason, modelSkill, modelName, required, assumed, category, null,
                List.of(), List.of(), ex);
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static String fmt(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
