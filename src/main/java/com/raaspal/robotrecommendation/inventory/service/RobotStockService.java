package com.raaspal.robotrecommendation.inventory.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import lombok.RequiredArgsConstructor;
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

    @Transactional(readOnly = true)
    public List<RobotStockEntryResponse> list(String keyword, RobotUnitStatus status) {
        // "%%" matches everything, so an absent search term needs no special case and
        // no null ever reaches the database — see the note in the repository about
        // lower(bytea).
        String term = blankToNull(keyword);
        String pattern = term == null ? "%%" : "%" + term.toLowerCase() + "%";

        List<RobotStockEntry> found = status == null
                ? repository.search(pattern)
                : repository.searchByStatus(pattern, status);

        return found.stream().map(RobotStockEntryResponse::from).toList();
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
        RobotUnitStatus status = warehouseStatus(request.status());
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
                .location(blankToNull(request.location()))
                .note(blankToNull(request.note()))
                .updatedBy(actorId)
                .build();

        return RobotStockEntryResponse.from(repository.save(entry));
    }

    @Transactional
    public RobotStockEntryResponse update(UUID id, RobotStockEntryRequest request, UUID actorId) {
        RobotStockEntry entry = require(id);

        RobotUnitStatus status = warehouseStatus(request.status());
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
        entry.setLocation(blankToNull(request.location()));
        entry.setNote(blankToNull(request.note()));

        // Capture the old count only when it genuinely moves. Saving an edit to the
        // location with the quantity untouched must not overwrite the backup with
        // the current number — that would quietly destroy the value worth keeping.
        if (request.quantity() != null && !request.quantity().equals(entry.getQuantity())) {
            entry.setPreviousQuantity(entry.getQuantity());
            entry.setPreviousQuantityAt(LocalDateTime.now());
            entry.setQuantity(request.quantity());
        }

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
     * <p>Nothing references these rows — no telemetry, no reports, no audit trail to
     * hollow out. A row entered by mistake is just a mistake, and forcing a soft
     * delete would leave the warehouse list cluttered with rows that never existed
     * in the building.
     */
    @Transactional
    public void delete(UUID id) {
        repository.delete(require(id));
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private RobotStockEntry require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RobotStockEntry", "id", id));
    }

    /**
     * Only IN_STOCK and DEMO belong here. RENT and SOLD describe a robot at a
     * customer under an agreement — that is the fleet's record, and this table
     * deliberately knows nothing about it.
     */
    private static RobotUnitStatus warehouseStatus(RobotUnitStatus status) {
        RobotUnitStatus resolved = status == null ? RobotUnitStatus.IN_STOCK : status;
        if (!resolved.isWarehouseVisible()) {
            throw new BadRequestException(
                    "Only IN_STOCK and DEMO can be recorded here. " + resolved
                            + " describes a robot at a customer.");
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
