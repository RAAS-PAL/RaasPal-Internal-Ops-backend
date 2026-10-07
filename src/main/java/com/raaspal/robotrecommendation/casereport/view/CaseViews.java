package com.raaspal.robotrecommendation.casereport.view;

import com.raaspal.robotrecommendation.casereport.customer.RobotCustomers;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The pending tabs' lists, built as the console builds them (frontend
 * {@code lib/caseCustomerViews.ts}, {@code compose}): the six frozen sheets recombined
 * so each case counts once, narrowed to a board and to the cases opened in a period.
 * What a tab's Excel and its public link both list.
 *
 * <p>Kept line for line with the frontend, so the file, the link and the screen agree:
 * <ul>
 *   <li><b>Internal</b> = Delivery (MK + Delivery + On Hold's delivery rows not on MK) and
 *       Cleaning (Cleaning + Makro + On Hold's cleaning rows).</li>
 *   <li><b>Makro</b> = Makro + On Hold's Makro rows + any other case that is Makro's.</li>
 *   <li><b>PCS</b> = Makro + any other case that is PCS's or Makro's.</li>
 *   <li><b>IFS</b> = any case that is IFS's.</li>
 *   <li><b>MK</b> = the MK sheet + any other case that is MK's; <b>On Hold</b> = the On
 *       Hold sheet, by board.</li>
 * </ul>
 * Every tab spans both boards, whether a customer has cases on both or not, and can be
 * narrowed to one. Whose a case is comes from its robot on the robot list, and only when
 * the robot is not on it from the words on the ticket.
 */
@Service
@RequiredArgsConstructor
public class CaseViews {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    public enum View {
        INTERNAL("internal"), PCS("pcs"), MAKRO("makro"), IFS("ifs"), MK("mk"), ON_HOLD("on-hold");

        public final String slug;

        View(String slug) {
            this.slug = slug;
        }

        public static View of(String slug) {
            for (View v : values()) if (v.slug.equals(slug)) return v;
            throw new BadRequestException("There is no pending tab called " + slug + ".");
        }

        /** Tabs that hold several customers' cases, so a link names whose it shows. */
        public boolean mixed() {
            return this == INTERNAL || this == ON_HOLD;
        }
    }

    /** The sheets, by slug, and the definitions they are stored under. */
    public static final Map<String, String> SHEETS = Map.of(
            "mk", CaseReportDefinition.MK_PENDING,
            "cleaning", CaseReportDefinition.CLEANING_PENDING,
            "makro", CaseReportDefinition.MAKRO_PENDING,
            "delivery", CaseReportDefinition.DELIVERY_PENDING,
            "on-hold", CaseReportDefinition.ON_HOLD_PENDING);

    /** The sheets each tab is made of, so no other is read. */
    static final Map<View, List<String>> NEEDS = Map.of(
            View.INTERNAL, List.of("mk", "delivery", "cleaning", "makro", "on-hold"),
            View.PCS, List.of("mk", "delivery", "cleaning", "makro", "on-hold"),
            View.MAKRO, List.of("mk", "delivery", "cleaning", "makro", "on-hold"),
            View.IFS, List.of("mk", "delivery", "cleaning", "on-hold"),
            View.MK, List.of("mk", "delivery", "cleaning", "on-hold"),
            View.ON_HOLD, List.of("on-hold"));

    public static final String CLEANING = CaseReportRow.BOARD_CLEANING;
    public static final String DELIVERY = CaseReportRow.BOARD_DELIVERY;

    /** A name standing alone, not inside another word: "PCS : Makro" yes, "PCSX" no. */
    private static final Pattern PCS = Pattern.compile("(^|[^a-z])pcs([^a-z]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFS = Pattern.compile("(^|[^a-z])ifs([^a-z]|$)", Pattern.CASE_INSENSITIVE);
    /** The board writes "Makro", "MaKro", "Makroหาดใหญ่": a part of a word is enough. */
    private static final Pattern MAKRO_WORDS = Pattern.compile("makro", Pattern.CASE_INSENSITIVE);
    /** MK Restaurant Group runs Yayoi and Bonus Suki too: one customer. */
    private static final Pattern MK_WORDS =
            Pattern.compile("(^|[^a-z])(mk|yayoi|bonus ?suki)([^a-z]|$)", Pattern.CASE_INSENSITIVE);

    /** Where a tab's rows came from: which sheet, and which board. */
    public record Part(String sheet, String board, List<CaseReportRow> rows) {
    }

    /** A case on a tab, with the sheet and board it came from. */
    public record Entry(String sheet, String board, CaseReportRow row) {
    }

    /** Whose a row is by its robot on the robot list; null when its robot is not on it. */
    @FunctionalInterface
    public interface RobotLookup {
        String customerOf(CaseReportRow row, String sheet);
    }

    /**
     * A tab's cases for a period, and the copy they come from.
     *
     * @param asOf      the oldest of the sheets' days read: the list is no fresher than that
     * @param updatedAt when the freshest of them was generated
     */
    public record Listing(LocalDate asOf, LocalDateTime updatedAt, List<Entry> entries) {
    }

    private final CaseReportRunService runs;
    private final RobotCustomers robots;

    /**
     * A tab's cases opened in a period, as they stood at its end - or today, while it runs -
     * from the stored sheets only: nothing is generated.
     *
     * @param scope      BOTH, CLEANING or DELIVERY
     * @param from       the period's first day; null for all time
     * @param to         its last day; null for all time
     * @param loadedOnly use the robot list only as already read (a public link)
     */
    public Listing list(View view, String scope, LocalDate from, LocalDate to, boolean loadedOnly) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate day = to == null || !to.isBefore(today) ? today : to;
        Map<String, List<CaseReportRow>> sheets = new HashMap<>();
        LocalDate asOf = null;
        LocalDateTime updatedAt = null;
        for (String sheet : NEEDS.get(view)) {
            CaseReportRunService.Stored stored = runs.latestStored(SHEETS.get(sheet), day).orElse(null);
            if (stored == null) continue;
            sheets.put(sheet, stored.rows());
            if (asOf == null || stored.runDate().isBefore(asOf)) asOf = stored.runDate();
            if (stored.generatedAt() != null && (updatedAt == null || stored.generatedAt().isAfter(updatedAt))) {
                updatedAt = stored.generatedAt();
            }
        }
        RobotLookup lookup = null;
        if (view != View.INTERNAL && view != View.ON_HOLD) {
            Function<RobotCustomers.CaseRef, RobotCustomers.Match> match = robots.matcher(loadedOnly);
            lookup = (row, sheet) -> {
                RobotCustomers.Match m = match.apply(new RobotCustomers.CaseRef(
                        sheet + ":" + row.sourceItemId(), row.serialNumber(), row.project(), row.branch()));
                return m == null ? null : m.customer();
            };
        }
        return new Listing(asOf, updatedAt, entries(compose(view, sheets, lookup), scope, from, to));
    }

    // ── The rules, without a database ──────────────────────────────────────────────

    /** The sheets recombined into a tab's parts, each case once. Mirrors the frontend's compose. */
    static List<Part> compose(View view, Map<String, List<CaseReportRow>> sheets, RobotLookup robots) {
        // Every MK ticket, removed ones included: a held MK case a reviewer took off the MK
        // sheet must not come back through On Hold.
        Set<String> mkTickets = new HashSet<>();
        for (CaseReportRow r : sheets.getOrDefault("mk", List.of())) mkTickets.add(r.sourceItemId());
        List<CaseReportRow> onHold = live(sheets.get("on-hold"));
        Predicate<CaseReportRow> isMakro = r -> r.project() != null && r.project().toLowerCase(Locale.ROOT).contains("makro");
        List<CaseReportRow> cleaningHeldAll = onHold.stream().filter(r -> CLEANING.equals(r.board())).toList();

        List<CaseReportRow> mk = live(sheets.get("mk"));
        List<CaseReportRow> deliveryHeld = onHold.stream()
                .filter(r -> DELIVERY.equals(r.board()) && !mkTickets.contains(r.sourceItemId())).toList();

        List<Part> delivery = List.of(
                new Part("mk", DELIVERY, mk),
                new Part("delivery", DELIVERY, live(sheets.get("delivery"))),
                new Part("on-hold", DELIVERY, deliveryHeld));
        List<Part> otherCleaning = List.of(
                new Part("cleaning", CLEANING, live(sheets.get("cleaning"))),
                new Part("on-hold", CLEANING, cleaningHeldAll.stream().filter(isMakro.negate()).toList()));
        List<Part> makro = List.of(
                new Part("makro", CLEANING, live(sheets.get("makro"))),
                new Part("on-hold", CLEANING, cleaningHeldAll.stream().filter(isMakro).toList()));

        return switch (view) {
            case INTERNAL -> concat(delivery, otherCleaning, makro);
            case MAKRO -> {
                Function<String, Predicate<CaseReportRow>> isMakroCase = belongsTo(Set.of("Makro"), MAKRO_WORDS, robots);
                yield concat(makro, only(otherCleaning, isMakroCase), only(delivery, isMakroCase));
            }
            case PCS -> {
                Function<String, Predicate<CaseReportRow>> isPcs = belongsTo(Set.of("PCS", "Makro"), PCS, robots);
                yield concat(makro, only(otherCleaning, isPcs), only(delivery, isPcs));
            }
            case IFS -> {
                Function<String, Predicate<CaseReportRow>> isIfs = belongsTo(Set.of("IFS"), IFS, robots);
                yield concat(only(otherCleaning, isIfs), only(delivery, isIfs));
            }
            case MK -> {
                // The MK sheet is MK's already; the rest of Delivery and Cleaning by the robot first.
                Function<String, Predicate<CaseReportRow>> isMk = belongsTo(Set.of("MK"), MK_WORDS, robots);
                yield concat(List.of(delivery.get(0)), only(delivery.subList(1, delivery.size()), isMk),
                        only(otherCleaning, isMk));
            }
            case ON_HOLD -> {
                List<CaseReportRow> held = live(sheets.get("on-hold"));
                yield List.of(
                        new Part("on-hold", DELIVERY, held.stream().filter(r -> DELIVERY.equals(r.board())).toList()),
                        new Part("on-hold", CLEANING, held.stream().filter(r -> CLEANING.equals(r.board())).toList()));
            }
        };
    }

    /**
     * The parts' cases on one board (or both) and opened in the period, oldest first, as
     * the tab lists them. A case with no open date belongs to all time only.
     */
    static List<Entry> entries(List<Part> parts, String scope, LocalDate from, LocalDate to) {
        List<Entry> out = new ArrayList<>();
        for (Part part : parts) {
            if (scope != null && !"BOTH".equals(scope) && !scope.equals(part.board())) continue;
            for (CaseReportRow r : part.rows()) {
                if (from != null && (r.openDate() == null || r.openDate().isBefore(from) || r.openDate().isAfter(to))) {
                    continue;
                }
                out.add(new Entry(part.sheet(), part.board(), r));
            }
        }
        out.sort(Comparator.comparing((Entry e) -> e.row().openDate(), Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static Function<String, Predicate<CaseReportRow>> belongsTo(Set<String> customers, Pattern words,
                                                                        RobotLookup robots) {
        return sheet -> r -> {
            String robot = robots == null ? null : robots.customerOf(r, sheet);
            if (robot != null) return customers.contains(robot);
            return (r.project() != null && words.matcher(r.project()).find())
                    || (r.branch() != null && words.matcher(r.branch()).find());
        };
    }

    private static List<Part> only(List<Part> parts, Function<String, Predicate<CaseReportRow>> keep) {
        return parts.stream()
                .map(p -> new Part(p.sheet(), p.board(), p.rows().stream().filter(keep.apply(p.sheet())).toList()))
                .toList();
    }

    @SafeVarargs
    private static List<Part> concat(List<Part>... lists) {
        List<Part> out = new ArrayList<>();
        for (List<Part> l : lists) out.addAll(l);
        return out;
    }

    private static List<CaseReportRow> live(List<CaseReportRow> rows) {
        return rows == null ? List.of() : rows.stream().filter(r -> !r.removed()).toList();
    }

    /**
     * The entries as rows to print or show: numbered from 1, each stamped with its board so
     * a list of both boards can say which.
     */
    public static List<CaseReportRow> rows(List<Entry> entries) {
        List<CaseReportRow> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.row().withBoard(e.board()).withNo(out.size() + 1));
        return out;
    }

    /** BOTH, CLEANING or DELIVERY; anything else is a 400. */
    public static String scope(String scope) {
        String s = scope == null || scope.isBlank() ? "BOTH" : scope.trim().toUpperCase(Locale.ROOT);
        if (s.equals("BOTH") || s.equals(CLEANING) || s.equals(DELIVERY)) return s;
        throw new BadRequestException("Unknown board: " + scope);
    }

    /** A period is both its days or neither (all time). */
    public static void requirePeriod(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null) || (from != null && to.isBefore(from))) {
            throw new BadRequestException("A period needs its first and last day, in that order.");
        }
    }

    /** The sheet a slug names, for a link or an export; a typo is a 400, not an empty list. */
    public static String requireSheet(String sheet) {
        if (sheet != null && SHEETS.containsKey(sheet)) return sheet;
        throw new BadRequestException("There is no pending sheet called " + sheet + ".");
    }
}
