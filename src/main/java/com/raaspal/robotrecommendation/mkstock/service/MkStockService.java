package com.raaspal.robotrecommendation.mkstock.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.*;
import com.raaspal.robotrecommendation.mkstock.entity.MkSparePart;
import com.raaspal.robotrecommendation.mkstock.entity.MkStockMovement;
import com.raaspal.robotrecommendation.mkstock.repository.MkSparePartRepository;
import com.raaspal.robotrecommendation.mkstock.repository.MkStockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MK's spare parts: the parts list, every stock in and out, and the dashboard figures.
 *
 * <p>A count only ever changes by recording a movement - never by editing the total - and
 * the part row is locked while it happens, so two people booking the same part at once
 * cannot both start from the same balance. Stock can never go below zero, and every OUT or
 * ADJUST carries a written reason.
 *
 * <p>{@code viewer} on the reads means MK's PIN view: the same data without who recorded it.
 */
@Service
@RequiredArgsConstructor
public class MkStockService {

    public static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final MkSparePartRepository parts;
    private final MkStockMovementRepository movements;

    /* ─── Parts ──────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<PartView> listParts(boolean includeRetired) {
        Map<UUID, LocalDate> lastMoved = lastMovementDays();
        return parts.findAllByOrderByActiveDescPartNoAsc().stream()
                .filter(p -> includeRetired || p.isActive())
                .map(p -> view(p, lastMoved.get(p.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PartView getPart(UUID id) {
        MkSparePart p = parts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Part", "id", id));
        return view(p, lastMovementDays().get(id));
    }

    @Transactional
    public PartView createPart(PartRequest req, String actor) {
        String partNo = req.partNo().trim();
        parts.findByPartNoIgnoreCase(partNo).ifPresent(p -> {
            throw new BadRequestException("Part number " + partNo + " is already in the list");
        });
        MkSparePart p = parts.save(MkSparePart.builder()
                .partNo(partNo)
                .name(req.name().trim())
                .robotModel(blank(req.robotModel()))
                .unit(blank(req.unit()) == null ? "pcs" : req.unit().trim())
                .minLevel(req.minLevel() == null ? 0 : req.minLevel())
                .location(blank(req.location()))
                .note(blank(req.note()))
                .createdBy(actor)
                .build());
        if (req.openingQuantity() != null && req.openingQuantity() > 0) {
            recordMovement(p.getId(), new MovementRequest(MkStockMovement.IN, req.openingQuantity(),
                    "Opening stock", null, null), actor);
        }
        return getPart(p.getId());
    }

    /** Edits the details. The count is not editable here - it only moves through a movement. */
    @Transactional
    public PartView updatePart(UUID id, PartRequest req) {
        MkSparePart p = parts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Part", "id", id));
        String partNo = req.partNo().trim();
        parts.findByPartNoIgnoreCase(partNo).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new BadRequestException("Part number " + partNo + " is already in the list");
        });
        p.setPartNo(partNo);
        p.setName(req.name().trim());
        p.setRobotModel(blank(req.robotModel()));
        p.setUnit(blank(req.unit()) == null ? "pcs" : req.unit().trim());
        p.setMinLevel(req.minLevel() == null ? 0 : req.minLevel());
        p.setLocation(blank(req.location()));
        p.setNote(blank(req.note()));
        if (req.active() != null) p.setActive(req.active());
        p.setUpdatedAt(Instant.now());
        return view(parts.save(p), lastMovementDays().get(id));
    }

    /* ─── Movements ──────────────────────────────────────────────────────── */

    @Transactional
    public MovementView recordMovement(UUID partId, MovementRequest req, String actor) {
        MkSparePart p = parts.findForUpdate(partId).orElseThrow(() -> new ResourceNotFoundException("Part", "id", partId));
        if (!p.isActive()) throw new BadRequestException(p.getPartNo() + " is retired - reactivate it first");

        String type = req.type();
        int qty = req.quantity() == null ? 0 : req.quantity();
        if (qty == 0) throw new BadRequestException("Enter a quantity other than zero");
        if (!MkStockMovement.ADJUST.equals(type) && qty < 0) {
            throw new BadRequestException("Enter a positive quantity - " + type + " sets the direction");
        }
        int change = switch (type) {
            case MkStockMovement.IN -> qty;
            case MkStockMovement.OUT -> -qty;
            default -> qty;
        };
        String reason = blank(req.reason());
        if (!MkStockMovement.IN.equals(type) && reason == null) {
            throw new BadRequestException(MkStockMovement.OUT.equals(type)
                    ? "Write the reason for this stock out"
                    : "Write the reason for this correction");
        }
        int balance = p.getQuantityOnHand() + change;
        if (balance < 0) {
            throw new BadRequestException("Only " + p.getQuantityOnHand() + " " + p.getUnit() + " of " + p.getPartNo()
                    + " in stock - cannot take out " + Math.abs(change));
        }
        LocalDate today = LocalDate.now(BANGKOK);
        LocalDate movedOn = req.movedOn() == null ? today : req.movedOn();
        if (movedOn.isAfter(today)) throw new BadRequestException("The date cannot be in the future");

        MkStockMovement m = movements.save(MkStockMovement.builder()
                .partId(p.getId())
                .movementType(type)
                .quantityChange(change)
                .balanceAfter(balance)
                .reason(reason)
                .reference(blank(req.reference()))
                .movedOn(movedOn)
                .createdBy(actor)
                .build());
        p.setQuantityOnHand(balance);
        p.setUpdatedAt(Instant.now());
        parts.save(p);
        return view(m, p, false);
    }

    @Transactional(readOnly = true)
    public List<MovementView> partHistory(UUID partId, boolean viewer) {
        MkSparePart p = parts.findById(partId).orElseThrow(() -> new ResourceNotFoundException("Part", "id", partId));
        return movements.findTop200ByPartIdOrderByMovedOnDescCreatedAtDesc(partId).stream()
                .map(m -> view(m, p, viewer))
                .toList();
    }

    /** The latest movements across every part, newest first. */
    @Transactional(readOnly = true)
    public List<MovementView> recentMovements(boolean viewer) {
        Map<UUID, MkSparePart> byId = parts.findAll().stream().collect(Collectors.toMap(MkSparePart::getId, Function.identity()));
        return movements.findTop500ByOrderByMovedOnDescCreatedAtDesc().stream()
                .map(m -> view(m, byId.get(m.getPartId()), viewer))
                .toList();
    }

    /* ─── Dashboard ──────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate from, LocalDate to, boolean viewer) {
        LocalDate today = LocalDate.now(BANGKOK);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw new BadRequestException("The start date is after the end date");
        if (ChronoUnit.DAYS.between(start, end) > 731) throw new BadRequestException("Pick a period of two years or less");

        List<MkSparePart> all = parts.findAllByOrderByActiveDescPartNoAsc();
        List<MkSparePart> active = all.stream().filter(MkSparePart::isActive).toList();
        Map<UUID, MkSparePart> byId = all.stream().collect(Collectors.toMap(MkSparePart::getId, Function.identity()));
        Map<UUID, LocalDate> lastMoved = lastMovementDays();

        List<MkStockMovement> inPeriod = movements.findByMovedOnBetween(start, end);
        int unitsIn = inPeriod.stream().filter(m -> MkStockMovement.IN.equals(m.getMovementType()))
                .mapToInt(MkStockMovement::getQuantityChange).sum();
        int unitsOut = -inPeriod.stream().filter(m -> MkStockMovement.OUT.equals(m.getMovementType()))
                .mapToInt(MkStockMovement::getQuantityChange).sum();
        int adjustments = (int) inPeriod.stream().filter(m -> MkStockMovement.ADJUST.equals(m.getMovementType())).count();

        // In and out per day, week or month - whichever keeps the chart readable.
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        String granularity = days <= 45 ? "DAY" : days <= 200 ? "WEEK" : "MONTH";
        Map<LocalDate, int[]> buckets = new LinkedHashMap<>();
        for (LocalDate d = bucket(start, granularity); !d.isAfter(end); d = next(d, granularity)) {
            buckets.put(d, new int[2]);
        }
        for (MkStockMovement m : inPeriod) {
            int[] b = buckets.get(bucket(m.getMovedOn(), granularity));
            if (b == null) continue;
            if (MkStockMovement.IN.equals(m.getMovementType())) b[0] += m.getQuantityChange();
            if (MkStockMovement.OUT.equals(m.getMovementType())) b[1] += -m.getQuantityChange();
        }
        List<SeriesPoint> series = buckets.entrySet().stream()
                .map(e -> new SeriesPoint(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();

        // Most-used parts and the reasons stock went out.
        Map<UUID, Integer> outByPart = new HashMap<>();
        Map<String, int[]> outByReason = new LinkedHashMap<>();
        for (MkStockMovement m : inPeriod) {
            if (!MkStockMovement.OUT.equals(m.getMovementType())) continue;
            outByPart.merge(m.getPartId(), -m.getQuantityChange(), Integer::sum);
            String key = m.getReason() == null ? "" : m.getReason().trim();
            int[] r = outByReason.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new int[]{0, 0});
            r[0]++;
            r[1] += -m.getQuantityChange();
        }
        Map<String, String> reasonLabel = new HashMap<>();
        inPeriod.stream().filter(m -> MkStockMovement.OUT.equals(m.getMovementType()) && m.getReason() != null)
                .forEach(m -> reasonLabel.putIfAbsent(m.getReason().trim().toLowerCase(Locale.ROOT), m.getReason().trim()));
        List<PartUsage> topOut = outByPart.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(8)
                .map(e -> {
                    MkSparePart p = byId.get(e.getKey());
                    return new PartUsage(e.getKey(), p.getPartNo(), p.getName(), p.getUnit(), e.getValue());
                })
                .toList();
        List<ReasonCount> reasons = outByReason.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .limit(8)
                .map(e -> new ReasonCount(reasonLabel.getOrDefault(e.getKey(), e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();

        List<PartView> attention = active.stream()
                .filter(p -> !"OK".equals(p.stockStatus()))
                .sorted(Comparator.comparing(MkSparePart::getQuantityOnHand).thenComparing(MkSparePart::getPartNo))
                .map(p -> view(p, lastMoved.get(p.getId())))
                .toList();
        List<MovementView> recent = movements.findTop15ByOrderByMovedOnDescCreatedAtDesc().stream()
                .map(m -> view(m, byId.get(m.getPartId()), viewer))
                .toList();

        return new Dashboard(start, end, active.size(),
                active.stream().mapToInt(MkSparePart::getQuantityOnHand).sum(),
                (int) active.stream().filter(p -> "LOW".equals(p.stockStatus())).count(),
                (int) active.stream().filter(p -> "OUT".equals(p.stockStatus())).count(),
                unitsIn, unitsOut, adjustments, inPeriod.size(), granularity, series, topOut, reasons,
                attention, recent);
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    private Map<UUID, LocalDate> lastMovementDays() {
        Map<UUID, LocalDate> out = new HashMap<>();
        for (Object[] row : movements.lastMovedPerPart()) out.put((UUID) row[0], (LocalDate) row[1]);
        return out;
    }

    static LocalDate bucket(LocalDate day, String granularity) {
        return switch (granularity) {
            case "WEEK" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> day.withDayOfMonth(1);
            default -> day;
        };
    }

    private static LocalDate next(LocalDate day, String granularity) {
        return switch (granularity) {
            case "WEEK" -> day.plusWeeks(1);
            case "MONTH" -> day.plusMonths(1);
            default -> day.plusDays(1);
        };
    }

    static PartView view(MkSparePart p, LocalDate lastMovementOn) {
        return new PartView(p.getId(), p.getPartNo(), p.getName(), p.getRobotModel(), p.getUnit(), p.getMinLevel(),
                p.getLocation(), p.getNote(), p.getQuantityOnHand(), p.stockStatus(), p.isActive(), lastMovementOn,
                p.getUpdatedAt());
    }

    static MovementView view(MkStockMovement m, MkSparePart p, boolean viewer) {
        return new MovementView(m.getId(), m.getPartId(), p == null ? null : p.getPartNo(), p == null ? null : p.getName(),
                m.getMovementType(), m.getQuantityChange(), m.getBalanceAfter(), m.getReason(), m.getReference(),
                m.getMovedOn(), viewer ? null : m.getCreatedBy(), m.getCreatedAt());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
