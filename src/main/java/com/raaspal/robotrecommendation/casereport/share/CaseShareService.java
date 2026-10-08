package com.raaspal.robotrecommendation.casereport.share;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.aotsheet.AotgaTracker;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import com.raaspal.robotrecommendation.casereport.view.CaseViews;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.pm.service.PmPublicService;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public links to pending cases, shared by the RE team and opened by anyone who has the
 * link, with no sign-in. A link opens one of:
 * <ul>
 *   <li><b>SHEET</b> - a pending sheet's details page: its latest stored copy.</li>
 *   <li><b>VIEW</b> - a pending tab for the period picked beside its calendar: the cases
 *       opened in it, live while the period runs and as at its last day once it is over
 *       (user, 2026-10-07); all time is every open case.</li>
 *   <li><b>CASE</b> - one case: live while it is on an open list, then its last state,
 *       marked closed with the day it left (user, 2026-10-07).</li>
 *   <li><b>VISIT</b> - one PM visit, and <b>SITE</b> - one PM site's schedule: read from the
 *       plan as it is now; once gone from the plan, said so. Never the site's contact
 *       (user, 2026-10-08).</li>
 * </ul>
 *
 * <p>Nothing is ever generated for a visitor: links read the stored sheets and AOT's kept
 * copies only, and the robot list as already read. An outsider opening a link must not
 * read monday, start a sync of AOT's sheet, or freeze a day's sheet before the team does.
 *
 * <p>Where a page or a tab mixes customers, a link names whose cases it shows, so nobody
 * is sent another customer's. Each link lasts a set number of days (30 unless chosen
 * otherwise), can be moved on, and can be stopped at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseShareService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    public static final String SHEET = "SHEET";
    public static final String VIEW = "VIEW";
    public static final String CASE = "CASE";
    public static final String VISIT = "VISIT";
    public static final String SITE = "SITE";

    /** The "sheet" a PM link is stored under. */
    public static final String PM = "pm";

    /** AOT's tracker: a CASE link's sheet, and (as {@link #AOT_VIEW}) a tab. */
    public static final String AOTGA = "aotga";
    public static final String AOT_VIEW = "aot";

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int MAX_CUSTOMERS = 50;

    /** How far back a closed case is looked for: four months of daily sheets. */
    static final int HISTORY = 120;

    static final Set<String> CADENCES = Set.of("DAILY", "WEEKLY", "MONTHLY", "ALL");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CaseShareLinkRepository links;
    private final CaseReportRunService runs;
    private final CaseViews views;
    private final AotgaTracker aotga;
    private final PmPublicService pm;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    // ── What the console sends and sees ─────────────────────────────────────────────

    /**
     * @param kind      SHEET, VIEW, CASE, VISIT or SITE
     * @param sheet     SHEET: the details page. CASE: the case's sheet, or aotga
     * @param view      VIEW: the tab - internal, pcs, makro, ifs, mk, on-hold or aot
     * @param scope     VIEW on a tab of both boards: BOTH (default), CLEANING or DELIVERY
     * @param customers SHEET, and VIEW on a tab that mixes customers: whose cases; empty or
     *                  null for every case
     * @param cadence   VIEW: DAILY, WEEKLY, MONTHLY or ALL, as picked
     * @param from      VIEW: the period's first day; none for ALL. SITE: the first day of the
     *                  visits shown (a year), with {@code to}; neither for every visit
     * @param caseKey   CASE: the monday item id, or the AOT ticket number. VISIT: the visit's
     *                  monday subitem id. SITE: the site's monday item id
     * @param days      how long the link works; 30 when not given, 1 to 365
     * @param title     what it shows, in the dialog's words, for the links page; CASE also
     *                  keeps {@code view}, the tab it was shared from, when given
     */
    public record CreateRequest(String kind, String sheet, String view, String scope, List<String> customers,
                                String cadence, LocalDate from, LocalDate to, String caseKey, Integer days,
                                String title) {

        @JsonCreator
        public CreateRequest {
        }

        public CreateRequest(String kind, String sheet, String view, String scope, List<String> customers,
                             String cadence, LocalDate from, LocalDate to, String caseKey, Integer days) {
            this(kind, sheet, view, scope, customers, cadence, from, to, caseKey, days, null);
        }
    }

    /** Whether a link still works, and if not, why. */
    public enum LinkStatus { ACTIVE, EXPIRED, STOPPED }

    static final int MAX_TITLE = 300;

    public record ExpiryRequest(Integer days) {
    }

    public record LinkView(UUID id,
                           String token,
                           String kind,
                           String sheet,
                           String view,
                           String scope,
                           List<String> customers,
                           String cadence,
                           LocalDate from,
                           LocalDate to,
                           String caseKey,
                           String createdBy,
                           OffsetDateTime createdAt,
                           OffsetDateTime expiresAt,
                           int viewCount,
                           OffsetDateTime lastViewedAt,
                           String title,
                           LinkStatus status,
                           OffsetDateTime revokedAt) {
    }

    // ── What a link's visitor sees ──────────────────────────────────────────────────

    public enum Status { OK, EXPIRED, STOPPED }

    public enum CaseStatus { OPEN, CLOSED }

    /**
     * One case of a pending sheet, without what is only the team's (edit marks). The Case ID
     * is monday's, as the boards show it, so a customer can name the case to the team.
     */
    public record PublicRow(int no,
                            String caseId,
                            String project,
                            String branch,
                            String robot,
                            String serialNumber,
                            String problem,
                            String solution,
                            LocalDate openDate,
                            LocalDate reOnSite,
                            Integer days,
                            SlaStatus sla,
                            String slaLabel,
                            String board,
                            String heldBy) {
    }

    /** One AOTGA case, without the sheet's colours, the claim note and who claimed it. */
    public record PublicAotCase(String ticketNo,
                                String site,
                                String robot,
                                String serial,
                                String problem,
                                LocalDate issueDate,
                                Integer days,
                                AotgaTracker.Stage stage,
                                String requestedPart,
                                LocalDate partSentOn,
                                LocalDate oldPartBackOn,
                                LocalDate claimedOn,
                                boolean sentFromSheet) {
    }

    /**
     * @param status     OK, or why the link no longer opens; nothing else is filled then
     * @param endedAt    when an expired or stopped link stopped working
     * @param asOf       the day the copy shown describes; null when there is no copy yet
     * @param updatedAt  when that copy was last brought up to date
     * @param from       VIEW: the period's first day; null for all time
     * @param rows       a pending sheet's or tab's cases; a CASE link's one case
     * @param cases      AOT's cases; an AOT CASE link's one case
     * @param caseStatus CASE: open, or closed - no longer on any open list
     * @param lastSeen   CASE, closed: the last day it was on one
     * @param closedOn   CASE, closed: the first day it was not, when known
     * @param pm         VISIT and SITE: the site and its visit (or visits); null once gone from the plan
     */
    public record PublicView(Status status,
                             String kind,
                             String sheet,
                             String view,
                             String scope,
                             List<String> customers,
                             String cadence,
                             LocalDate from,
                             LocalDate to,
                             OffsetDateTime expiresAt,
                             OffsetDateTime endedAt,
                             LocalDate asOf,
                             OffsetDateTime updatedAt,
                             List<PublicRow> rows,
                             List<PublicAotCase> cases,
                             CaseStatus caseStatus,
                             LocalDate lastSeen,
                             LocalDate closedOn,
                             PmPublicService.Shown pm) {
    }

    // ── The team's side ─────────────────────────────────────────────────────────────

    @Transactional
    public LinkView create(CreateRequest request, UUID userId) {
        String kind = kind(request.kind());
        CaseShareLink.CaseShareLinkBuilder link = CaseShareLink.builder().token(newToken()).kind(kind).createdBy(userId);
        switch (kind) {
            case SHEET -> link.sheet(CaseViews.requireSheet(request.sheet()))
                    .customers(write(customers(request.customers())));
            case VIEW -> {
                String view = requireView(request.view());
                CaseViews.View tab = AOT_VIEW.equals(view) ? null : CaseViews.View.of(view);
                String cadence = cadence(request.cadence());
                boolean all = "ALL".equals(cadence);
                LocalDate from = all ? null : request.from();
                LocalDate to = all ? null : request.to();
                if (!all && from == null) throw new BadRequestException("A period needs its first and last day.");
                CaseViews.requirePeriod(from, to);
                link.view(view)
                        .scope(tab != null ? CaseViews.scope(request.scope()) : null)
                        .customers(tab != null && tab.mixed() ? write(customers(request.customers())) : null)
                        .cadence(cadence)
                        .periodFrom(from)
                        .periodTo(to);
            }
            case VISIT, SITE -> {
                String key = request.caseKey() == null ? "" : request.caseKey().trim();
                boolean found = !key.isEmpty() && key.length() <= 64
                        && (VISIT.equals(kind) ? pm.visit(key) : pm.site(key)).isPresent();
                if (!found) throw new BadRequestException(VISIT.equals(kind) ? "Which visit?" : "Which site?");
                link.sheet(PM).caseKey(key);
                if (SITE.equals(kind) && (request.from() != null || request.to() != null)) {
                    CaseViews.requirePeriod(request.from(), request.to());
                    link.periodFrom(request.from()).periodTo(request.to());
                }
            }
            default -> {
                String sheet = AOTGA.equals(request.sheet()) ? AOTGA : CaseViews.requireSheet(request.sheet());
                String key = request.caseKey() == null ? "" : request.caseKey().trim();
                if (key.isEmpty() || key.length() > 64) throw new BadRequestException("Which case?");
                link.sheet(sheet).caseKey(key);
                // The tab it was shared from, so the links page lists it there: a PCS case
                // is stored by its sheet (cleaning, say), which alone would file it under Internal.
                if (request.view() != null && !request.view().isBlank()) link.view(requireView(request.view().trim()));
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        CaseShareLink saved = links.save(link.title(title(request.title()))
                .createdAt(now).expiresAt(now.plusDays(days(request.days()))).build());
        log.info("Shared a {} link ({}{}) until {}", kind, saved.getSheet() != null ? saved.getSheet() : saved.getView(),
                saved.getCustomers() == null ? "" : ", named customers", saved.getExpiresAt());
        return views(List.of(saved)).get(0);
    }

    /** The links still working for one page, tab or case, newest first. */
    @Transactional(readOnly = true)
    public List<LinkView> active(String kind, String sheet, String view, String caseKey) {
        return views(links.findActive(kind(kind), OffsetDateTime.now()).stream()
                .filter(l -> sheet == null || sheet.equals(l.getSheet()))
                .filter(l -> view == null || view.equals(l.getView()))
                .filter(l -> caseKey == null || caseKey.equals(l.getCaseKey()))
                .toList());
    }

    /**
     * Every link ever made, newest first, for the links page: those still working, those
     * that ran out and those stopped - or only one of the three.
     *
     * @param status ACTIVE, EXPIRED or STOPPED; null or ALL for every link
     */
    @Transactional(readOnly = true)
    public List<LinkView> all(String status) {
        LinkStatus wanted = linkStatus(status);
        OffsetDateTime now = OffsetDateTime.now();
        return views(links.findAllByOrderByCreatedAtDesc().stream()
                .filter(l -> wanted == null || status(l, now) == wanted)
                .toList());
    }

    /** Moves a link's end to the given number of days from now. A stopped link stays stopped. */
    @Transactional
    public LinkView extend(UUID id, Integer days) {
        CaseShareLink link = require(id);
        if (link.getRevokedAt() != null) {
            throw new BadRequestException("This link was stopped. Share a new one instead.");
        }
        link.setExpiresAt(OffsetDateTime.now().plusDays(days(days)));
        return views(List.of(link)).get(0);
    }

    /** Ends a link at once. Stopping one already stopped changes nothing. */
    @Transactional
    public void stop(UUID id) {
        CaseShareLink link = require(id);
        if (link.getRevokedAt() == null) {
            link.setRevokedAt(OffsetDateTime.now());
            log.info("Stopped sharing a {} link", link.getKind());
        }
    }

    // ── The visitor's side ──────────────────────────────────────────────────────────

    /** What a link shows now. Unknown links are a 404; expired and stopped ones say so. */
    @Transactional
    public PublicView open(String token) {
        CaseShareLink link = links.findByToken(token == null ? "" : token)
                .orElseThrow(() -> new ResourceNotFoundException("This link does not exist."));
        OffsetDateTime now = OffsetDateTime.now();
        if (link.getRevokedAt() != null) return ended(link, Status.STOPPED, link.getRevokedAt());
        if (!link.getExpiresAt().isAfter(now)) return ended(link, Status.EXPIRED, link.getExpiresAt());
        links.countView(link.getId(), now);

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return switch (link.getKind()) {
            case SHEET -> openSheet(link, today);
            case VIEW -> AOT_VIEW.equals(link.getView()) ? openAotView(link, today) : openView(link, today);
            case CASE -> AOTGA.equals(link.getSheet()) ? openAotCase(link, today) : openCase(link, today);
            case VISIT, SITE -> openPm(link, today);
            default -> throw new IllegalStateException("A share link of unknown kind " + link.getKind());
        };
    }

    private PublicView openSheet(CaseShareLink link, LocalDate today) {
        CaseReportRunService.Stored stored = runs.latestStored(CaseViews.SHEETS.get(link.getSheet()), today).orElse(null);
        return shown(link, stored == null ? null : stored.runDate(), stored == null ? null : at(stored.generatedAt()),
                stored == null ? List.of() : rows(stored.rows(), read(link.getCustomers())), List.of());
    }

    private PublicView openView(CaseShareLink link, LocalDate today) {
        CaseViews.Listing listing = views.list(CaseViews.View.of(link.getView()),
                link.getScope() == null ? "BOTH" : link.getScope(), link.getPeriodFrom(), link.getPeriodTo(), true);
        return shown(link, listing.asOf(), at(listing.updatedAt()),
                rows(CaseViews.rows(listing.entries()), read(link.getCustomers())), List.of());
    }

    private PublicView openAotView(CaseShareLink link, LocalDate today) {
        LocalDate to = link.getPeriodTo();
        AotgaTracker.View copy = aotga.latestCopy(to == null || !to.isBefore(today) ? today : to).orElse(null);
        return shown(link, copy == null ? null : copy.asOf(), copy == null ? null : at(copy.lastSyncedAt()), List.of(),
                copy == null ? List.of() : aotCases(copy.cases(), new Period(link.getPeriodFrom(), to)));
    }

    /**
     * One case, wherever it is now: a case put on hold moves from its sheet to On Hold, so
     * every sheet is looked at before it is called closed. Then its last state, from the
     * last copy of its own sheet or On Hold that had it.
     */
    private PublicView openCase(CaseShareLink link, LocalDate today) {
        String key = link.getCaseKey();
        List<String> order = new ArrayList<>(List.of(link.getSheet()));
        CaseViews.SHEETS.keySet().stream().sorted().filter(s -> !s.equals(link.getSheet())).forEach(order::add);
        for (String sheet : order) {
            CaseReportRunService.Stored stored = runs.latestStored(CaseViews.SHEETS.get(sheet), today).orElse(null);
            CaseReportRow row = stored == null ? null : find(stored.rows(), key);
            if (row != null) {
                return caseShown(link, stored.runDate(), at(stored.generatedAt()), List.of(publicRow(row, row.no())),
                        List.of(), CaseStatus.OPEN, null, null);
            }
        }
        LastSeen<CaseReportRow> last = null;
        for (String sheet : new LinkedHashSet<>(List.of(link.getSheet(), "on-hold"))) {
            List<CaseReportRunService.Stored> recent = runs.recentStored(CaseViews.SHEETS.get(sheet), today, HISTORY);
            LastSeen<CaseReportRow> found = lastSeen(recent.stream().map(s -> new Copy<>(s.runDate(), find(s.rows(), key))).toList());
            if (found != null && (last == null || found.day().isAfter(last.day()))) last = found;
        }
        return caseShown(link, last == null ? null : last.day(), null,
                last == null ? List.of() : List.of(publicRow(last.item(), last.item().no())), List.of(),
                CaseStatus.CLOSED, last == null ? null : last.day(), last == null ? null : last.gone());
    }

    /** One AOTGA case: on AOT's sheet (whatever its stage), or its last state in the copies kept. */
    private PublicView openAotCase(CaseShareLink link, LocalDate today) {
        AotgaTracker.View copy = aotga.latestCopy(today).orElse(null);
        AotgaTracker.Case c = copy == null ? null : findAot(copy.cases(), link.getCaseKey());
        if (c != null) {
            return caseShown(link, copy.asOf(), at(copy.lastSyncedAt()), List.of(), List.of(publicAot(c)),
                    CaseStatus.OPEN, null, null);
        }
        LastSeen<AotgaTracker.Case> last = lastSeen(aotga.recentCopies(today).stream()
                .map(v -> new Copy<>(v.asOf(), findAot(v.cases(), link.getCaseKey()))).toList());
        return caseShown(link, last == null ? null : last.day(), null, List.of(),
                last == null ? List.of() : List.of(publicAot(last.item())),
                CaseStatus.CLOSED, last == null ? null : last.day(), last == null ? null : last.gone());
    }

    /** A PM visit or site as the plan has it now; closed, with nothing shown, once it is gone. */
    private PublicView openPm(CaseShareLink link, LocalDate today) {
        PmPublicService.Shown shown = (VISIT.equals(link.getKind()) ? pm.visit(link.getCaseKey())
                : pm.site(link.getCaseKey(), link.getPeriodFrom(), link.getPeriodTo())).orElse(null);
        PublicView view = caseShown(link, today, null, List.of(), List.of(),
                shown != null ? CaseStatus.OPEN : CaseStatus.CLOSED, null, null);
        return withPm(view, shown);
    }

    // ── The rules, kept apart so they can be tested without a database ──────────────

    /** A dated copy of a list, and the case in it - null when it was not there. */
    record Copy<T>(LocalDate day, T item) {
    }

    /** The last copy a case was in, and the day of the next one, which did not have it. */
    record LastSeen<T>(LocalDate day, T item, LocalDate gone) {
    }

    /** @param copies newest first */
    static <T> LastSeen<T> lastSeen(List<Copy<T>> copies) {
        for (int i = 0; i < copies.size(); i++) {
            if (copies.get(i).item() != null) {
                return new LastSeen<>(copies.get(i).day(), copies.get(i).item(), i > 0 ? copies.get(i - 1).day() : null);
            }
        }
        return null;
    }

    /** A live row of a sheet by its monday item id. */
    static CaseReportRow find(List<CaseReportRow> rows, String key) {
        for (CaseReportRow r : rows) {
            if (!r.removed() && key.equals(r.sourceItemId())) return r;
        }
        return null;
    }

    static AotgaTracker.Case findAot(List<AotgaTracker.Case> cases, String ticket) {
        String want = key(ticket);
        for (AotgaTracker.Case c : cases) {
            if (c.ticketNo() != null && key(c.ticketNo()).equals(want)) return c;
        }
        return null;
    }

    /**
     * The live rows for the link's customers, numbered again from 1 so the numbers run
     * without gaps. A row the team removed is not shown.
     *
     * @param customers null for every row
     */
    static List<PublicRow> rows(List<CaseReportRow> rows, List<String> customers) {
        Set<String> wanted = customers == null ? null
                : customers.stream().map(CaseShareService::key).collect(Collectors.toSet());
        List<PublicRow> out = new ArrayList<>();
        for (CaseReportRow r : rows) {
            if (r.removed()) continue;
            if (wanted != null && !wanted.contains(key(r.project()))) continue;
            out.add(publicRow(r, out.size() + 1));
        }
        return out;
    }

    static PublicRow publicRow(CaseReportRow r, int no) {
        return new PublicRow(no, CaseReportRow.caseIdOf(r), r.project(), r.branch(), r.robot(), r.serialNumber(), r.problem(), r.solution(),
                r.openDate(), r.reOnSite(), r.days(), r.sla(), r.slaLabel(), r.board(), r.heldBy());
    }

    static PublicAotCase publicAot(AotgaTracker.Case c) {
        return new PublicAotCase(c.ticketNo(), c.site(), c.robot(), c.serial(), c.problem(), c.issueDate(), c.days(),
                c.stage(), c.requestedPart(), c.partSentOn(), c.oldPartBackOn(), c.claimedOn(), c.sentFromSheet());
    }

    /** A period's first and last day; both null for all time. */
    record Period(LocalDate from, LocalDate to) {
        boolean contains(LocalDate day) {
            if (from == null) return true;
            return day != null && !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /** The cases issued in the period, newest first; a case with no issue date only in all time. */
    static List<PublicAotCase> aotCases(List<AotgaTracker.Case> cases, Period period) {
        return cases.stream()
                .filter(c -> period.contains(c.issueDate()))
                .sorted(Comparator.comparing(AotgaTracker.Case::issueDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(CaseShareService::publicAot)
                .toList();
    }

    /** The customers a link is for: trimmed, each once; none means every customer. */
    static List<String> customers(List<String> given) {
        if (given == null) return null;
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String c : given) {
            if (c == null || c.isBlank()) continue;
            String name = c.trim();
            if (name.length() > 200) throw new BadRequestException("A customer name is too long.");
            if (seen.add(key(name))) out.add(name);
        }
        if (out.size() > MAX_CUSTOMERS) {
            throw new BadRequestException("A link can name at most " + MAX_CUSTOMERS + " customers.");
        }
        return out.isEmpty() ? null : out;
    }

    static int days(Integer days) {
        if (days == null) return DEFAULT_DAYS;
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("A link can last from 1 to " + MAX_DAYS + " days.");
        }
        return days;
    }

    static String cadence(String cadence) {
        if (cadence == null || cadence.isBlank()) return "ALL";
        String c = cadence.trim().toUpperCase(Locale.ROOT);
        if (!CADENCES.contains(c)) throw new BadRequestException("Unknown period: " + cadence);
        return c;
    }

    static String kind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (k.equals(SHEET) || k.equals(VIEW) || k.equals(CASE) || k.equals(VISIT) || k.equals(SITE)) return k;
        throw new BadRequestException("Unknown kind of link: " + kind);
    }

    static LinkStatus status(CaseShareLink link, OffsetDateTime now) {
        if (link.getRevokedAt() != null) return LinkStatus.STOPPED;
        return link.getExpiresAt().isAfter(now) ? LinkStatus.ACTIVE : LinkStatus.EXPIRED;
    }

    /** ACTIVE, EXPIRED or STOPPED; null for ALL or none. */
    static LinkStatus linkStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) return null;
        try {
            return LinkStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown link status: " + status);
        }
    }

    /** The dialog's words for what a link shows, trimmed; too long is cut rather than refused. */
    static String title(String title) {
        if (title == null || title.isBlank()) return null;
        String t = title.trim();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    static String requireView(String view) {
        if (AOT_VIEW.equals(view)) return view;
        return CaseViews.View.of(view).slug;
    }

    /** Names compare without case or surrounding spaces: "MK " is "mk". */
    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────

    private PublicView ended(CaseShareLink link, Status status, OffsetDateTime endedAt) {
        return new PublicView(status, link.getKind(), link.getSheet(), link.getView(), null, null, null, null, null,
                null, endedAt, null, null, List.of(), List.of(), null, null, null, null);
    }

    private PublicView shown(CaseShareLink link, LocalDate asOf, OffsetDateTime updatedAt,
                             List<PublicRow> rows, List<PublicAotCase> cases) {
        return caseShown(link, asOf, updatedAt, rows, cases, null, null, null);
    }

    private PublicView caseShown(CaseShareLink link, LocalDate asOf, OffsetDateTime updatedAt, List<PublicRow> rows,
                                 List<PublicAotCase> cases, CaseStatus caseStatus, LocalDate lastSeen,
                                 LocalDate closedOn) {
        return new PublicView(Status.OK, link.getKind(), link.getSheet(), link.getView(), link.getScope(),
                read(link.getCustomers()), link.getCadence(), link.getPeriodFrom(), link.getPeriodTo(),
                link.getExpiresAt(), null, asOf, updatedAt, rows, cases, caseStatus, lastSeen, closedOn, null);
    }

    private static PublicView withPm(PublicView v, PmPublicService.Shown shown) {
        return new PublicView(v.status(), v.kind(), v.sheet(), v.view(), v.scope(), v.customers(), v.cadence(),
                v.from(), v.to(), v.expiresAt(), v.endedAt(), v.asOf(), v.updatedAt(), v.rows(), v.cases(),
                v.caseStatus(), v.lastSeen(), v.closedOn(), shown);
    }

    private List<LinkView> views(List<CaseShareLink> list) {
        Map<UUID, String> names = users.findAllById(list.stream()
                        .map(CaseShareLink::getCreatedBy).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId,
                        u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getEmail(),
                        (a, b) -> a));
        OffsetDateTime now = OffsetDateTime.now();
        return list.stream().map(l -> new LinkView(l.getId(), l.getToken(), l.getKind(), l.getSheet(), l.getView(),
                l.getScope(), read(l.getCustomers()), l.getCadence(), l.getPeriodFrom(), l.getPeriodTo(),
                l.getCaseKey(), names.get(l.getCreatedBy()), l.getCreatedAt(), l.getExpiresAt(), l.getViewCount(),
                l.getLastViewedAt(), l.getTitle(), status(l, now), l.getRevokedAt())).toList();
    }

    private CaseShareLink require(UUID id) {
        return links.findById(id).orElseThrow(() -> new ResourceNotFoundException("CaseShareLink", "id", id));
    }

    /** 24 random bytes: 32 URL-safe characters that cannot be guessed. */
    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored times are the server's local time; say which zone they were in. */
    private static OffsetDateTime at(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private String write(List<String> customers) {
        if (customers == null) return null;
        try {
            return objectMapper.writeValueAsString(customers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the link's customers", e);
        }
    }

    private List<String> read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A share link's customers could not be read", e);
        }
    }
}
