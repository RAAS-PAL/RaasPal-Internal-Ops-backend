package com.raaspal.robotrecommendation.reassignment;

import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator;
import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ReAssignmentEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final Instant NOW = Instant.parse("2026-09-22T03:00:00Z");

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static final Policy POLICY = new Policy(
            Set.of("all case", "check", "aotga"), Set.of("done"),
            Set.of("request case", "traning"), List.of("Urgent", "Incident case", "Service case"),
            Map.of("new", BigDecimal.ONE, "working on it", BigDecimal.ONE, "manday", BigDecimal.ONE,
                    "pending", new BigDecimal("0.5"), "check", new BigDecimal("0.25")),
            new BigDecimal("0.5"), BigDecimal.ONE,
            Map.of("l1-easy", 2, "l2-mid", 3, "l3-hard", 4), "L2-Mid");

    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "Omnie", new Mapping("MAPPED", "OMNIE", "OMNIE"),
            "Phantas 1.3", new Mapping("MAPPED", "PHANTAS", "Phantas"),
            "M50", new Mapping("MANUAL", null, null),
            "Scrubber 75", new Mapping("UNCONFIRMED", null, null));

    private static Engineer eng(UUID id, String name, int model, int cm, String monday, double max) {
        return new Engineer(id, name, true, monday, true, BigDecimal.valueOf(max),
                Map.of("OMNIE", model, "PHANTAS", model, "CM_CLEANING", cm, "ELECTRICAL_CLEANING", 2), false, null);
    }

    private static Ticket ticket(String id, String model, String level, String type, String group, String status,
                                 List<Person> people) {
        return new Ticket(id, "ticket " + id, group, status, null, model, level, type, "Online", null, "Cust",
                "Branch", "battery not charging", LocalDate.of(2026, 9, 20), null, people, NOW);
    }

    private static Ticket open(String id, String model, String level) {
        return ticket(id, model, level, "Service case", "All Case", "New", List.of());
    }

    private static List<Evaluation> run(List<Ticket> tickets, List<Engineer> engineers,
                                        Map<String, Existing> existing, Set<String> held) {
        return ReAssignmentEvaluator.evaluate(new Input(tickets, engineers, MAPPINGS, existing, held, POLICY, TODAY, NOW));
    }

    private static Evaluation only(List<Evaluation> evs, String id) {
        return evs.stream().filter(e -> e.ticket().itemId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void easyTicketGoesToTheAvailableExactlyQualifiedEngineerNotTheExpert() {
        List<Engineer> team = List.of(
                eng(A, "A (L2)", 2, 2, "m-a", 6),
                eng(C, "C (L4)", 4, 4, "m-c", 6),
                eng(D, "D (CM L1)", 3, 1, "m-d", 6));

        Evaluation e = only(run(List.of(open("t1", "Omnie", "L1-Easy")), team, Map.of(), Set.of()), "t1");

        assertThat(e.outcome()).isEqualTo(Outcome.SUGGESTED);
        assertThat(e.requiredLevel()).isEqualTo(2);
        assertThat(e.suggested().engineerId()).isEqualTo(A);
        assertThat(e.alternatives()).extracting(Candidate::engineerId).containsExactly(C);
        assertThat(e.excluded()).extracting(Exclusion::reason).containsExactly("CM L1 < L2");
        assertThat(e.issueCategory()).isEqualTo("ELECTRICAL");
        assertThat(e.suggested().components()).containsKeys("overQualified", "load", "expertise", "rotation");
    }

    @Test
    void hardTicketNeedsL4OnBothAndOtherwiseSaysNobodyQualifies() {
        List<Engineer> team = List.of(eng(A, "A", 3, 3, "m-a", 6), eng(B, "B", 4, 3, "m-b", 6));
        Evaluation e = only(run(List.of(open("t1", "Omnie", "L3-Hard")), team, Map.of(), Set.of()), "t1");
        assertThat(e.outcome()).isEqualTo(Outcome.NO_QUALIFIED);
        assertThat(e.excluded()).extracting(Exclusion::reason).containsExactlyInAnyOrder("Model L3 < L4", "CM L3 < L4");
    }

    @Test
    void qualifiedButFullOrOnLeaveMeansAllBusy() {
        Engineer full = eng(A, "A", 4, 4, "m-a", 1.5);   // holds one active ticket: 1 + 1 > 1.5
        Engineer away = new Engineer(B, "B", true, "m-b", true, BigDecimal.valueOf(6),
                Map.of("OMNIE", 4, "CM_CLEANING", 4), true, null);
        Ticket busyWith = ticket("w1", "M50", "L1-Easy", "Service case", "All Case", "Working on it",
                List.of(new Person("m-a", "A")));

        Evaluation e = only(run(List.of(open("t1", "Omnie", "L3-Hard"), busyWith), List.of(full, away), Map.of(), Set.of()), "t1");

        assertThat(e.outcome()).isEqualTo(Outcome.ALL_BUSY);
        assertThat(e.excluded()).extracting(Exclusion::reason)
                .containsExactlyInAnyOrder("At load limit (1 of 1.5)", "On leave today");
    }

    @Test
    void ticketsOutsideTheMatrixOrNotCmStayManualWithTheReason() {
        List<Engineer> team = List.of(eng(A, "A", 4, 4, "m-a", 6));
        List<Evaluation> evs = run(List.of(
                open("m50", "M50", "L1-Easy"),
                open("unmapped", "X1", "L1-Easy"),
                open("unconfirmed", "Scrubber 75", "L1-Easy"),
                open("blank", null, "L1-Easy"),
                ticket("req", "Omnie", "L1-Easy", "Request case", "All Case", "New", List.of())), team, Map.of(), Set.of());

        assertThat(only(evs, "m50").reason()).contains("not in the skill matrix");
        assertThat(only(evs, "unmapped").reason()).contains("not mapped");
        assertThat(only(evs, "unconfirmed").reason()).contains("awaits confirmation");
        assertThat(only(evs, "blank").reason()).isEqualTo("Type of Robot is blank");
        assertThat(only(evs, "req").reason()).contains("Not a CM case");
        assertThat(evs).allMatch(e -> e.outcome() == Outcome.MANUAL);
    }

    @Test
    void alreadyOnMondayApprovedAndHeldAreNotSuggestedAgain() {
        List<Engineer> team = List.of(eng(A, "A", 4, 4, "m-a", 6));
        List<Evaluation> evs = run(List.of(
                ticket("onMonday", "Omnie", "L1-Easy", "Service case", "Check", "Pending", List.of(new Person("x", "X"))),
                open("approved", "Omnie", "L1-Easy"),
                open("held", "Omnie", "L1-Easy")), team,
                Map.of("approved", new Existing(UUID.randomUUID(), A, "APPROVED")), Set.of("held"));

        assertThat(only(evs, "onMonday").outcome()).isEqualTo(Outcome.ASSIGNED);
        assertThat(only(evs, "approved").outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(only(evs, "held").outcome()).isEqualTo(Outcome.HELD);
    }

    @Test
    void workloadIsStatusWeightedAndIgnoresFinishedGroups() {
        List<Engineer> team = List.of(eng(A, "A", 4, 4, "m-a", 6));
        List<Person> a = List.of(new Person("m-a", "A"));
        List<Ticket> tickets = List.of(
                ticket("1", "Omnie", null, null, "Check", "Check", a),                  // 0.25
                ticket("2", "Omnie", null, null, "Check", "Check", a),                  // 0.25
                ticket("3", "Omnie", null, null, "All Case", "Working on it", a),       // 1
                ticket("4", "Omnie", null, null, "Tickets Done 2023", "Pending", a),    // finished group: 0
                ticket("5", "Omnie", null, null, "All Case", "Done", a));               // closed: 0

        Workload w = ReAssignmentEvaluator.workload(tickets, team, POLICY).get(A);

        assertThat(w.load()).isEqualByComparingTo("1.5");
        assertThat(w.openTickets()).isEqualTo(3);
    }

    @Test
    void eachSuggestionAddsLoadSoOnePersonIsNotSuggestedForEverything() {
        // Equal limits: the first ticket goes to A (tie broken by name), and A's provisional
        // load then makes B the better choice for the second.
        List<Engineer> team = List.of(eng(A, "A", 2, 2, "m-a", 2), eng(B, "B", 2, 2, "m-b", 2));
        List<Evaluation> evs = run(List.of(open("t1", "Omnie", "L1-Easy"), open("t2", "Omnie", "L1-Easy")),
                team, Map.of(), Set.of());

        Set<UUID> suggested = new HashSet<>();
        evs.forEach(e -> suggested.add(e.suggested().engineerId()));
        assertThat(suggested).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void blankDifficultyAssumesMidAndSaysSo() {
        List<Engineer> team = List.of(eng(A, "A", 2, 2, "m-a", 6), eng(C, "C", 3, 3, "m-c", 6));
        Evaluation e = only(run(List.of(open("t1", "Omnie", null)), team, Map.of(), Set.of()), "t1");

        assertThat(e.assumedDifficulty()).isTrue();
        assertThat(e.requiredLevel()).isEqualTo(3);
        assertThat(e.suggested().engineerId()).isEqualTo(C);
        assertThat(e.reason()).contains("assumed L2-Mid");
    }

    @Test
    void urgentTicketsAreEvaluatedFirst() {
        List<Engineer> team = List.of(eng(A, "A", 4, 4, "m-a", 1));   // room for exactly one ticket
        List<Evaluation> evs = run(List.of(
                ticket("service", "Omnie", "L1-Easy", "Service case", "All Case", "New", List.of()),
                ticket("urgent", "Omnie", "L1-Easy", "Urgent", "All Case", "New", List.of())), team, Map.of(), Set.of());

        assertThat(evs.get(0).ticket().itemId()).isEqualTo("urgent");
        assertThat(only(evs, "urgent").outcome()).isEqualTo(Outcome.SUGGESTED);
        assertThat(only(evs, "service").outcome()).isEqualTo(Outcome.ALL_BUSY);
    }
}
