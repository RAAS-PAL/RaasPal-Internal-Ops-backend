package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A PM visit, and a PM site's schedule, as a public link shows them: when, where, which
 * robots and where each visit stands. Never the site's contact - name, phone or email
 * stay with the team (user, 2026-10-08). Read from the mirrored plan as it is now, so a
 * link follows the plan as visits are moved or done.
 */
@Service
@RequiredArgsConstructor
public class PmPublicService {

    /**
     * One visit.
     *
     * @param jobNo       the monday subitem id, as the board's Item ID column shows it
     * @param status      the planner's bucket, OVERDUE when a plan date has passed on work not done
     * @param daysOverdue days past the plan date; null when nothing is owed
     * @param engineer    who is assigned, as the board names them; null for nobody yet
     */
    public record Visit(String jobNo,
                        String visitName,
                        Integer pmSequence,
                        LocalDate planDate,
                        LocalDate actionDate,
                        String timeText,
                        String status,
                        Integer daysOverdue,
                        String engineer) {
    }

    /** Where the visits are, and what is serviced there. */
    public record Site(String name,
                       String serviceLine,
                       String district,
                       String province,
                       String region,
                       String robotModel,
                       Integer robotCount,
                       boolean contractEnded) {
    }

    /** @param visits one for a visit's link; for a site's, all of them - dated by date, then undated */
    public record Shown(Site site, List<Visit> visits) {
    }

    private final PmVisitRepository visits;

    /** One visit by its monday subitem id; empty once it is no longer on the plan. */
    @Transactional(readOnly = true)
    public Optional<Shown> visit(String itemId) {
        return shown(visits.findByVisitItemId(itemId), LocalDate.now());
    }

    /** A site's visits by the site's monday item id; empty once the site is no longer on the plan. */
    @Transactional(readOnly = true)
    public Optional<Shown> site(String siteItemId) {
        return site(siteItemId, null, null);
    }

    /**
     * A site's visits planned between two days - a year, as the 52-week view it was shared
     * from - and those not dated yet, which are still owed; both days null for every visit.
     */
    @Transactional(readOnly = true)
    public Optional<Shown> site(String siteItemId, LocalDate from, LocalDate to) {
        return shown(visits.findBySiteItemId(siteItemId), LocalDate.now(), from, to);
    }

    static Optional<Shown> shown(List<PmVisitRepository.VisitRow> rows, LocalDate today) {
        return shown(rows, today, null, null);
    }

    /**
     * The rows as shown: the site from the first, a visit for each row planned in the period
     * (or not dated yet). The site is shown even when none falls in the period. No I/O, so it
     * can be tested as it is.
     */
    static Optional<Shown> shown(List<PmVisitRepository.VisitRow> rows, LocalDate today, LocalDate from, LocalDate to) {
        if (rows.isEmpty()) return Optional.empty();
        PmVisitRepository.VisitRow first = rows.get(0);
        Site site = new Site(first.getItemName(), first.getServiceLine(), first.getDistrict(), first.getProvince(),
                first.getRegion(), first.getRobotModel(), first.getRobotCount(),
                PmPlanningService.contractEnded(first.getContractGroup()));
        return Optional.of(new Shown(site, rows.stream()
                .filter(r -> from == null || r.getPlanDate() == null
                        || (!r.getPlanDate().isBefore(from) && !r.getPlanDate().isAfter(to)))
                .map(r -> visit(r, today))
                .toList()));
    }

    private static Visit visit(PmVisitRepository.VisitRow r, LocalDate today) {
        Integer overdue = PmPlanningService.daysOverdue(r.getPlanDate(), r.getActionDate(), r.getStatusBucket(), today);
        return new Visit(r.getItemId(), r.getVisitName(), r.getPmSequence(), r.getPlanDate(), r.getActionDate(),
                r.getTimeText(), overdue != null ? "OVERDUE" : r.getStatusBucket(), overdue, blankToNull(r.getOwnerNames()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
