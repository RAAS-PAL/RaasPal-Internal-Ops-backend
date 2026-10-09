package com.raaspal.robotrecommendation.inventory.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockMoveRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockUnitsRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockModelRequest;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The warehouse's own robot list — {@code robot_inventory_temp}.
 *
 * <p>Touches nothing else. No repository here reaches into {@code robot_units} or
 * {@code robots}: that separation is the point of the table, and it is easiest to
 * keep by never wiring the dependency in the first place.
 */
@Service
@RequiredArgsConstructor
public class RobotStockService {

    private final RobotStockEntryRepository repository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<RobotStockEntryResponse> list(String keyword, RobotUnitStatus status) {
        // "%%" matches everything, so an absent search term needs no special case and
        // no null ever reaches the database — see the note in the repository about
        // lower(bytea).
        String term = blankToNull(keyword);
        String pattern = term == null ? "%%" : "%" + term.toLowerCase() + "%";

        return status == null
                ? repository.search(pattern)
                : repository.searchByStatus(pattern, status);
    }

    @Transactional(readOnly = true)
    public RobotStockEntryResponse getById(UUID id) {
        return RobotStockEntryResponse.from(require(id));
    }

    /** Robots held, split by status — demo units are on the premises but not sellable. */
    @Transactional(readOnly = true)
    public long countByStatus(RobotUnitStatus status) {
        return repository.sumQuantityByStatus(status);
    }

    @Transactional
    public RobotStockEntryResponse create(RobotStockEntryRequest request, UUID actorId) {
        RobotUnitStatus status = stockRoomStatus(request.status());
        String brand = required(request.brand(), "Brand");
        String model = required(request.model(), "Model");
        String version = blankToNull(request.version());

        // Caught here rather than left to the unique index, so the operator gets
        // "you already have this — edit it" instead of a constraint violation.
        Optional<RobotStockEntry> existing = repository.findIdentity(brand, model, version, status);
        if (existing.isPresent()) {
            throw new BadRequestException(
                    "There is already a " + status + " entry for " + brand + " " + model
                            + (version == null ? "" : " " + version) + ". Edit that one instead.");
        }

        RobotStockEntry entry = RobotStockEntry.builder()
                .robotType(request.robotType() != null ? request.robotType() : RobotType.CLEANING)
                .brand(brand)
                .model(model)
                .version(version)
                .imageUrl(validateImage(request.imageUrl()))
                .quantity(request.quantity() == null ? 0 : request.quantity())
                .status(status)
                .packaging(request.packaging())
                .location(blankToNull(request.location()))
                .note(blankToNull(request.note()))
                .updatedBy(actorId)
                .build();

        return RobotStockEntryResponse.from(repository.save(entry));
    }

    @Transactional
    public RobotStockEntryResponse update(UUID id, RobotStockEntryRequest request, UUID actorId) {
        RobotStockEntry entry = source(lockModel(id), id);

        RobotUnitStatus status = stockRoomStatus(request.status());
        String brand = required(request.brand(), "Brand");
        String model = required(request.model(), "Model");
        String version = blankToNull(request.version());

        // Renaming a row onto another row's identity would trip the unique index.
        repository.findIdentity(brand, model, version, status)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BadRequestException(
                            "Another " + status + " entry already covers " + brand + " " + model
                                    + (version == null ? "" : " " + version) + ".");
                });

        entry.setRobotType(request.robotType() != null ? request.robotType() : entry.getRobotType());
        entry.setBrand(brand);
        entry.setModel(model);
        entry.setVersion(version);
        entry.setStatus(status);
        entry.setPackaging(request.packaging());
        entry.setLocation(blankToNull(request.location()));
        entry.setNote(blankToNull(request.note()));

        // Capture the old count only when it genuinely moves. Saving an edit to the
        // location with the quantity untouched must not overwrite the backup with
        // the current number — that would quietly destroy the value worth keeping.
        if (request.quantity() != null) changeQuantity(entry, request.quantity(), actorId);

        // Absent leaves the photo alone; "" removes it. A form with no picker must
        // not wipe an existing image just by not mentioning it.
        if (request.imageUrl() != null) {
            entry.setImageUrl(request.imageUrl().isBlank() ? null : validateImage(request.imageUrl()));
        }

        entry.setUpdatedBy(actorId);
        return RobotStockEntryResponse.from(repository.save(entry));
    }

    /**
     * Deleting is allowed here, unlike everywhere else in the platform.
     *
     * <p>No telemetry or reports reference these rows, but spare-part links do:
     * deletion cascades those links. Moves must keep a zero-count source instead.
     */
    @Transactional
    public void delete(UUID id) {
        repository.delete(source(lockModel(id), id));
    }

    /**
     * Removes every status row of a model — the robot page's "Delete this model".
     *
     * <p>Under the same model lock as a move, so a move arriving at the same moment
     * either commits first and is deleted with the rest, or waits and then gets the
     * retry code. Part links are removed explicitly, not left to the foreign key's
     * cascade: the parts stay in inventory, unlinked, on any database.
     *
     * @return how many status rows were removed
     */
    @Transactional
    public int deleteModel(UUID id) {
        List<RobotStockEntry> rows = lockModel(id);
        repository.deletePartLinks(rows.stream().map(RobotStockEntry::getId).toList());
        repository.deleteAll(rows);
        return rows.size();
    }

    /** Both counts and newly copied part links commit together, or none of them do. */
    @Transactional
    public List<RobotStockEntryResponse> move(UUID id, RobotStockMoveRequest request, UUID actorId) {
        RobotUnitStatus status = requiredStockRoomStatus(request.toStatus());
        int quantity = positiveQuantity(request.quantity());
        RobotStockEntry source = source(lockModel(id), id);
        if (source.getStatus() == status) throw new BadRequestException("robot_stock.same_status");
        if (quantity > source.getQuantity()) throw new BadRequestException("robot_stock.insufficient_units");
        RobotStockEntry target = target(source, status);
        int total = addedQuantity(target, quantity);
        changeQuantity(source, source.getQuantity() - quantity, actorId);
        changeQuantity(target, total, actorId);
        return savedModel(source);
    }

    @Transactional
    public List<RobotStockEntryResponse> addUnits(UUID id, RobotStockUnitsRequest request, UUID actorId) {
        RobotUnitStatus status = requiredStockRoomStatus(request.status());
        int quantity = positiveQuantity(request.quantity());
        RobotStockEntry source = source(lockModel(id), id);
        RobotStockEntry target = target(source, status);
        changeQuantity(target, addedQuantity(target, quantity), actorId);
        return savedModel(source);
    }

    @Transactional
    public List<RobotStockEntryResponse> updateModel(UUID id, RobotStockModelRequest request, UUID actorId) {
        String brand = required(request.brand(), "Brand");
        String model = required(request.model(), "Model");
        String version = blankToNull(request.version());
        if (request.robotType() == null) throw new BadRequestException("robot_stock.type_required");
        String image = validateImage(request.imageUrl());
        List<RobotStockEntry> rows = lockModel(id);
        var ids = rows.stream().map(RobotStockEntry::getId).toList();
        // Check the entire identity, not just matching statuses: disjoint shelves
        // still belong to a different model and must never silently merge.
        if (repository.findModel(brand, model, versionKey(version)).stream()
                .anyMatch(row -> !ids.contains(row.getId()))) {
            throw new BadRequestException("robot_stock.model_exists");
        }
        for (RobotStockEntry row : rows) {
            row.setBrand(brand);
            row.setModel(model);
            row.setVersion(version);
            row.setRobotType(request.robotType());
            // Omitted preserves each row's photo; empty explicitly removes all.
            if (request.imageUrl() != null) row.setImageUrl(image);
            row.setUpdatedBy(actorId);
        }
        return savedModel(rows.getFirst());
    }

    private List<RobotStockEntry> lockModel(UUID id) {
        RobotStockEntry entry = require(id);
        String brand = entry.getBrand();
        String model = entry.getModel();
        String version = versionKey(entry.getVersion());
        List<RobotStockEntry> rows = repository.lockModel(brand, model, version);
        if (rows.isEmpty()) throw changed();
        // A waiter may have started its SELECT before another transaction inserted
        // a new status. Re-read after acquiring the shared model rows so renames
        // include that newly committed shelf as well.
        rows = repository.lockModel(brand, model, version);
        if (rows.isEmpty()) throw changed();
        for (RobotStockEntry row : rows) {
            // require(id) may already have cached a pre-lock count in the persistence
            // context. A lock alone does not refresh it after waiting for a writer.
            entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            if (!brand.trim().equalsIgnoreCase(row.getBrand().trim())
                    || !model.trim().equalsIgnoreCase(row.getModel().trim())
                    || !version.equalsIgnoreCase(versionKey(row.getVersion()))) throw changed();
        }
        source(rows, id);
        return rows;
    }

    private static RobotStockEntry source(List<RobotStockEntry> rows, UUID id) {
        return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow(RobotStockService::changed);
    }

    private RobotStockEntry target(RobotStockEntry source, RobotUnitStatus status) {
        var existing = repository.findIdentity(source.getBrand(), source.getModel(), source.getVersion(), status);
        if (existing.isPresent()) {
            entityManager.refresh(existing.get(), LockModeType.PESSIMISTIC_WRITE);
            return existing.get();
        }
        RobotStockEntry target = RobotStockEntry.builder()
                .robotType(source.getRobotType()).brand(source.getBrand()).model(source.getModel())
                .version(source.getVersion()).imageUrl(source.getImageUrl()).status(status).quantity(0).build();
        try {
            // Flush here, not at transaction exit: concurrent legacy creates can
            // still win the unique index, and need a retry response rather than 500.
            repository.saveAndFlush(target);
            repository.copyPartLinks(source.getId(), target.getId());
        } catch (DataIntegrityViolationException ex) {
            throw changed();
        }
        return target;
    }

    private List<RobotStockEntryResponse> savedModel(RobotStockEntry entry) {
        try {
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw changed();
        }
        return repository.findModel(entry.getBrand(), entry.getModel(), versionKey(entry.getVersion()))
                .stream().map(RobotStockEntryResponse::from).toList();
    }

    private static void changeQuantity(RobotStockEntry entry, int quantity, UUID actorId) {
        if (quantity < 0) throw new BadRequestException("robot_stock.invalid_quantity");
        if (quantity != entry.getQuantity()) {
            entry.setPreviousQuantity(entry.getQuantity());
            entry.setPreviousQuantityAt(LocalDateTime.now());
            entry.setQuantity(quantity);
            entry.setUpdatedBy(actorId);
        }
    }

    private static int addedQuantity(RobotStockEntry entry, int quantity) {
        if (quantity > Integer.MAX_VALUE - entry.getQuantity()) {
            throw new BadRequestException("robot_stock.quantity_too_large");
        }
        return entry.getQuantity() + quantity;
    }

    private static int positiveQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) throw new BadRequestException("robot_stock.invalid_quantity");
        return quantity;
    }

    private static RobotUnitStatus requiredStockRoomStatus(RobotUnitStatus status) {
        if (status == null || !status.isStockRoomStatus()) throw new BadRequestException("robot_stock.invalid_status");
        return status;
    }

    private static String versionKey(String version) {
        return version == null ? "" : version;
    }

    private static IllegalStateException changed() {
        // UI translates this code to "Someone changed this robot, try again."
        return new IllegalStateException("robot_stock.changed_retry");
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    /** The robot's photo as real image bytes. 404 when it has none. */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> getImage(UUID id) {
        return StoredImage.serve(require(id).getImageUrl(), "RobotStockEntry image", id);
    }

    private RobotStockEntry require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RobotStockEntry", "id", id));
    }

    /**
     * The five store-room states, and only those. RENT and SOLD describe a robot at
     * a customer under an agreement: that is the fleet record, and this table
     * deliberately knows nothing about it.
     *
     * <p>Checks {@link RobotUnitStatus#isStockRoomStatus} rather than
     * {@code isWarehouseVisible} — the latter guards the fleet own endpoints, and
     * the two sets stopped being the same when the repair states arrived in V37.
     */
    private static RobotUnitStatus stockRoomStatus(RobotUnitStatus status) {
        RobotUnitStatus resolved = status == null ? RobotUnitStatus.IN_STOCK : status;
        if (!resolved.isStockRoomStatus()) {
            throw new BadRequestException(
                    resolved + " describes a robot at a customer and cannot be "
                            + "recorded here. Use New Stock, Demo Unit, Under Repair, "
                            + "Returned from Customer or Used (Ready to Use).");
        }
        return resolved;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        return value.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Accepts an http(s) URL or a base64 {@code data:} image, and caps the size.
     *
     * <p>Checked on the server, not only in the browser: the client downscale is a
     * convenience. Without this a full-resolution phone photo lands in a Postgres row
     * and, across a catalogue, eats a Supabase quota measured in hundreds of megabytes.
     */
    private static String validateImage(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.length() > 2048) throw new BadRequestException("Image URL is too long");
            return trimmed;
        }
        if (!trimmed.startsWith("data:image/")) {
            throw new BadRequestException("Image must be an http(s) URL or a base64 data:image/... URI");
        }
        if (trimmed.length() > MAX_IMAGE_CHARS) {
            throw new BadRequestException(
                    "Image is too large. Resize it to under " + (MAX_IMAGE_BYTES / 1024) + " KB.");
        }
        return trimmed;
    }

    /** ~1 MB of image; base64 inflates by about a third, hence the char budget. */
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_CHARS = (int) (MAX_IMAGE_BYTES * 1.4);
}
