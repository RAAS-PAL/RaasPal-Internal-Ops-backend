package com.raaspal.robotrecommendation.inventory.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.inventory.dto.*;
import com.raaspal.robotrecommendation.inventory.entity.InventoryItem;
import com.raaspal.robotrecommendation.inventory.entity.MovementType;
import com.raaspal.robotrecommendation.inventory.entity.StockMovement;
import com.raaspal.robotrecommendation.inventory.repository.InventoryItemRepository;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.repository.StockMovementRepository;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryService {

    /** Enough to fill a dashboard tile; the full list lives behind ?lowStock=true. */
    private static final int LOW_STOCK_PREVIEW = 10;

    private final InventoryItemRepository itemRepository;
    private final StockMovementRepository movementRepository;
    private final RobotStockEntryRepository robotStockRepository;
    private final RobotUnitRepository robotUnitRepository;

    /* ─── Reads ───────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Page<InventoryItemResponse> search(String keyword,
                                              String category,
                                              UUID robotStockId,
                                              boolean lowStock,
                                              boolean includeInactive,
                                              Pageable pageable) {
        Page<InventoryItem> page = itemRepository.search(
                blankToNull(keyword), blankToNull(category), robotStockId, lowStock, !includeInactive, pageable);
        Map<UUID, String> names = resolveRobotNames(page.getContent());
        return page.map(i -> InventoryItemResponse.from(i, linkedRobots(i, names)));
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse getById(UUID id) {
        InventoryItem item = require(id);
        Map<UUID, String> names = resolveRobotNames(List.of(item));
        return InventoryItemResponse.from(item, linkedRobots(item, names));
    }

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        return itemRepository.findCategories();
    }

    /** The RIMS dashboard header, including the low-stock alert. */
    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary() {
        List<InventoryItem> low = itemRepository.findLowStock();
        List<InventoryItem> preview = low.size() > LOW_STOCK_PREVIEW ? low.subList(0, LOW_STOCK_PREVIEW) : low;
        Map<UUID, String> names = resolveRobotNames(preview);

        BigDecimal value = itemRepository.sumStockValue();

        return new InventorySummaryResponse(
                itemRepository.countActive(),
                itemRepository.countLowStock(),
                value == null ? BigDecimal.ZERO : value,
                // From the warehouse's own list, not the fleet: robot_units counts
                // machines already at customers, which is a different question.
                robotStockRepository.sumQuantityByStatus(RobotUnitStatus.IN_STOCK),
                robotStockRepository.sumQuantityByStatus(RobotUnitStatus.DEMO),
                preview.stream().map(i -> InventoryItemResponse.from(i, linkedRobots(i, names))).toList());
    }


    @Transactional(readOnly = true)
    public Page<StockMovementResponse> getHistory(UUID itemId, Pageable pageable) {
        InventoryItem item = require(itemId);
        return movementRepository.findByInventoryItemIdOrderByCreatedAtDesc(itemId, pageable)
                .map(m -> StockMovementResponse.from(m, item.getName(), item.getSku(), null, null));
    }

    /**
     * Recent movements across every item — the activity feed.
     *
     * <p>Item names are resolved in one query rather than per row: a page of forty
     * movements would otherwise be forty-one round trips to a pooled database that
     * allows fifteen connections.
     */
    @Transactional(readOnly = true)
    public Page<StockMovementResponse> getRecentMovements(Pageable pageable) {
        Page<StockMovement> page = movementRepository.findAllByOrderByCreatedAtDesc(pageable);

        Map<UUID, InventoryItem> items = itemRepository
                .findAllById(page.getContent().stream()
                        .map(StockMovement::getInventoryItemId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(InventoryItem::getId, i -> i));

        return page.map(m -> {
            // A movement whose item was since deleted still belongs in the trail; it
            // is shown without a name rather than dropped from the history.
            InventoryItem item = items.get(m.getInventoryItemId());
            return StockMovementResponse.from(
                    m,
                    item == null ? null : item.getName(),
                    item == null ? null : item.getSku(),
                    null,
                    null);
        });
    }

    /* ─── Writes ──────────────────────────────────────────────────────────── */

    @Transactional
    public InventoryItemResponse create(InventoryItemRequest request) {
        String sku = (request.sku() == null || request.sku().isBlank())
                ? generateSku()
                : request.sku().trim();

        if (itemRepository.existsBySkuIgnoreCase(sku)) {
            throw new BadRequestException("An item with SKU '" + sku + "' already exists");
        }

        InventoryItem item = InventoryItem.builder()
                .sku(sku)
                .barcode(blankToNull(request.barcode()))
                .name(request.name().trim())
                .category(request.category().trim())
                .robotStockIds(validatedRobotStockIds(request.robotStockIds()))
                .quantityOnHand(0)      // stock only ever arrives through a movement
                .reorderPoint(request.reorderPoint() == null ? 10 : request.reorderPoint())
                .reorderQuantity(request.reorderQuantity() == null ? 0 : request.reorderQuantity())
                .isActive(request.isActive() == null || request.isActive())
                .build();

        InventoryItem saved = itemRepository.save(item);
        return InventoryItemResponse.from(saved, linkedRobots(saved, resolveRobotNames(List.of(saved))));
    }

    /**
     * Edit an item's details. Deliberately cannot touch {@code quantityOnHand} —
     * that only moves through {@link #recordMovement}.
     */
    @Transactional
    public InventoryItemResponse update(UUID id, InventoryItemRequest request) {
        InventoryItem item = require(id);

        if (request.sku() != null && !request.sku().isBlank()
                && !request.sku().equalsIgnoreCase(item.getSku())) {
            if (itemRepository.existsBySkuIgnoreCase(request.sku().trim())) {
                throw new BadRequestException("An item with SKU '" + request.sku().trim() + "' already exists");
            }
            item.setSku(request.sku().trim());
        }
        item.setBarcode(blankToNull(request.barcode()));
        item.setName(request.name().trim());
        item.setCategory(request.category().trim());
        // Replace the whole link set: the form submits what is ticked, and "what
        // is ticked" is the entire intent — patching would make an untick ambiguous.
        item.getRobotStockIds().clear();
        item.getRobotStockIds().addAll(validatedRobotStockIds(request.robotStockIds()));
        if (request.reorderPoint() != null)    item.setReorderPoint(request.reorderPoint());
        if (request.reorderQuantity() != null) item.setReorderQuantity(request.reorderQuantity());
        if (request.isActive() != null) item.setIsActive(request.isActive());

        InventoryItem saved = itemRepository.save(item);
        return InventoryItemResponse.from(saved, linkedRobots(saved, resolveRobotNames(List.of(saved))));
    }

    /**
     * The only place a stock level changes.
     *
     * <p>One transaction appends the ledger row and updates the cached balance, so
     * the two cannot drift. Nothing else in the codebase may write
     * {@code quantityOnHand} — if it does, the {@code balanceAfter} column stops
     * agreeing with the running sum and the discrepancy becomes visible.
     */
    @Transactional
    public StockMovementResponse recordMovement(UUID itemId, StockMovementRequest request, UUID actorId) {
        InventoryItem item = require(itemId);

        int change = request.quantityChange();
        if (change == 0) {
            throw new BadRequestException("A movement must change the quantity by a non-zero amount");
        }

        // Direction is carried by the sign, but a RECEIPT of -5 is almost certainly a
        // typo for +5, and silently accepting it would corrupt the count. Rejecting
        // costs the operator one correction; accepting costs a stocktake to find.
        boolean shouldBePositive = request.movementType() == MovementType.RECEIPT
                || request.movementType() == MovementType.RETURN;
        if (shouldBePositive && change < 0) {
            throw new BadRequestException(request.movementType() + " must be a positive quantity");
        }
        if (request.movementType() == MovementType.ISSUE && change > 0) {
            throw new BadRequestException("ISSUE must be a negative quantity");
        }

        int balanceAfter = item.getQuantityOnHand() + change;

        // ADJUSTMENT is exempt: a stocktake sometimes finds less than the ledger
        // claims, and blocking that would push staff into inventing a fake ISSUE.
        if (balanceAfter < 0 && request.movementType() != MovementType.ADJUSTMENT) {
            throw new BadRequestException(
                    "Not enough stock: " + item.getQuantityOnHand() + " on hand, tried to remove " + Math.abs(change));
        }
        if (request.robotUnitId() != null && !robotUnitRepository.existsById(request.robotUnitId())) {
            throw new BadRequestException("Unknown robot unit: " + request.robotUnitId());
        }

        StockMovement movement = movementRepository.save(StockMovement.builder()
                .inventoryItemId(itemId)
                .movementType(request.movementType())
                .quantityChange(change)
                .balanceAfter(balanceAfter)
                .robotUnitId(request.robotUnitId())
                .note(blankToNull(request.note()))
                .createdBy(actorId)
                .build());

        item.setQuantityOnHand(balanceAfter);
        itemRepository.save(item);

        return StockMovementResponse.from(movement, item.getName(), item.getSku(), null, null);
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private InventoryItem require(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", "id", id));
    }

    /** {@code INV-000001}. The sequence lives in the database so two operators
     *  creating items at once cannot be handed the same number. */
    private String generateSku() {
        return "INV-%06d".formatted(itemRepository.nextSkuNumber());
    }

    /**
     * Display names for every robot linked from the given items, in one query
     * rather than one per row. The items' link sets load via SUBSELECT (see the
     * entity), so a whole page costs two round trips, not 2N.
     */
    private Map<UUID, String> resolveRobotNames(List<InventoryItem> items) {
        List<UUID> ids = items.stream()
                .flatMap(i -> i.getRobotStockIds().stream())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        robotStockRepository.findAllById(ids).forEach(r -> out.put(r.getId(), r.displayName()));
        return out;
    }

    /**
     * The response's linked-robot list, sorted by name so chips render stably.
     * A link whose robot has since vanished is dropped rather than shown
     * nameless — CASCADE should prevent that, but a phantom chip helps nobody.
     */
    private static List<InventoryItemResponse.LinkedRobot> linkedRobots(InventoryItem item, Map<UUID, String> names) {
        return item.getRobotStockIds().stream()
                .filter(names::containsKey)
                .map(id -> new InventoryItemResponse.LinkedRobot(id, names.get(id)))
                .sorted(Comparator.comparing(InventoryItemResponse.LinkedRobot::displayName))
                .toList();
    }

    /**
     * Rejects links to robots that do not exist. Validated as a set up front —
     * one bad id fails the save with a message naming it, rather than a
     * half-written link set.
     */
    private Set<UUID> validatedRobotStockIds(List<UUID> requested) {
        if (requested == null || requested.isEmpty()) return new HashSet<>();
        Set<UUID> unique = new HashSet<>(requested);
        Set<UUID> known = robotStockRepository.findAllById(unique).stream()
                .map(com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry::getId)
                .collect(Collectors.toSet());
        for (UUID id : unique) {
            if (!known.contains(id)) {
                throw new BadRequestException("Unknown robot: " + id);
            }
        }
        return unique;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Convenience for callers that want the default page of history. */
    public static Pageable defaultHistoryPage() {
        return PageRequest.of(0, 50);
    }
}
