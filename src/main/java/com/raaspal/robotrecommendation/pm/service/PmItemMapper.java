package com.raaspal.robotrecommendation.pm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.entity.PmContract;
import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns monday rows into {@link PmContract} and {@link PmVisit} rows. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmItemMapper {

    /** "PM4 : IFS : Siam Paragon" -> 4. Also matches MA, which older rows use. */
    private static final Pattern PM_SEQUENCE = Pattern.compile("^\\s*(?:PM|MA)\\s*0*(\\d{1,3})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Legal-form words a Thai company name opens with. Skipped when deriving a
     * company, because they identify a kind of business, not a business.
     */
    private static final List<String> GENERIC_NAME_PREFIXES = List.of(
            "บริษัท", "บมจ.", "บจก.", "บจ.", "หจก.", "ห้างหุ้นส่วนจำกัด", "ห้างหุ้นส่วน");

    private final ObjectMapper objectMapper;
    private final ProvinceResolver provinceResolver;

    /** Builds a contract row. {@code existing} is reused so its id and first-seen survive. */
    public PmContract toContract(MondayItem item, PmMondayProperties.Board board, PmContract existing,
                                 OffsetDateTime now) {
        PmMondayProperties.Columns columns = board.getColumns();

        PmContract contract = existing != null ? existing : PmContract.builder()
                .id(UUID.randomUUID())
                .firstSeenAt(now)
                .build();

        contract.setSourceBoardId(board.getId());
        contract.setSourceItemId(item.id());
        contract.setServiceLine(board.getServiceLine());
        contract.setItemName(item.name());
        contract.setGroupTitle(item.groupTitle());
        contract.setProjectRaw(text(item, columns.getProject()));
        contract.setCustomerNameRaw(text(item, columns.getCustomerName()));
        contract.setDistrictRaw(text(item, columns.getDistrict()));
        contract.setRegionRaw(text(item, columns.getRegion()));
        contract.setContractType(text(item, columns.getContractType()));
        contract.setCompany(deriveCompany(item.name()));
        contract.setRobotModel(text(item, columns.getRobotModel()));
        contract.setRobotCount(toInteger(text(item, columns.getRobotCount())));
        contract.setRobotSerials(joinSerials(item, columns.serialColumnIds()));

        // Province drives region and zone. monday's own region cell is read into
        // regionRaw for diagnosis only - see ProvinceResolver for why.
        String provinceRaw = text(item, columns.getProvince());
        contract.setProvinceRaw(provinceRaw);
        ProvinceResolver.ResolvedProvince resolved = provinceResolver.resolve(provinceRaw);
        contract.setProvinceResolved(resolved.province());
        contract.setRegionResolved(resolved.region());
        contract.setZoneResolved(resolved.zone());

        applyLocation(contract, item, columns.getLocation());
        applyWarranty(contract, item, columns.getWarrantyTimeline());

        contract.setRawColumns(rawColumns(item));
        contract.setSourceUpdatedAt(item.updatedAt());
        contract.setLastSyncedAt(now);
        contract.setPresent(true);
        return contract;
    }

    /** Builds a visit row under an already-persisted contract. */
    public PmVisit toVisit(MondayItem item, PmMondayProperties.Board board, UUID contractId, PmVisit existing,
                           OffsetDateTime now) {
        PmMondayProperties.SubitemColumns columns = board.getSubitemColumns();

        PmVisit visit = existing != null ? existing : PmVisit.builder()
                .id(UUID.randomUUID())
                .firstSeenAt(now)
                .build();

        visit.setPmContractId(contractId);
        visit.setSourceBoardId(board.getSubitemBoardId());
        visit.setSourceItemId(item.id());
        visit.setVisitName(item.name());
        visit.setPmSequence(pmSequence(item.name()));
        visit.setPlanDate(toDate(text(item, columns.getPlanDate())));
        visit.setActionDate(toDate(text(item, columns.getActionDate())));
        visit.setTimeText(text(item, columns.getTime()));

        String statusRaw = text(item, columns.getStatus());
        visit.setStatusRaw(statusRaw);
        visit.setStatusBucket(PmStatusBucket.fromRaw(statusRaw));

        visit.setOwnerNames(text(item, columns.getOwner()));
        visit.setRawColumns(rawColumns(item));
        visit.setSourceUpdatedAt(item.updatedAt());
        visit.setLastSyncedAt(now);
        visit.setPresent(true);
        return visit;
    }

    /**
     * The chain a site belongs to, from its name.
     *
     * <p>Two shapes cover the boards: "PCS : Makro อุตรดิตถ์", where everything before
     * the colon is the customer, and "BBQ Gateway บางซื่อ", where it is the first word.
     *
     * <p>The wrinkle is Thai. Company names routinely open with a legal-form word -
     * บริษัท, หจก. - so taking the first word blindly files 32 unrelated sites under
     * "บริษัท", a bucket that means "company". Those prefixes are skipped, but only
     * when followed by a space: "ร้านขนมบ้านยายกรณ์" is one word and stays whole rather
     * than being truncated to a shop that does not exist.
     *
     * <p>This is a heuristic over names people typed, so it will be wrong somewhere.
     * That is why it is stored in a column - a bad grouping can be seen, and later
     * corrected, instead of silently shaping every query.
     */
    public static String deriveCompany(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return null;
        }
        String name = itemName.trim().replaceAll("\\s+", " ");

        int colon = name.indexOf(':');
        if (colon > 0) {
            String prefix = name.substring(0, colon).trim();
            if (!prefix.isEmpty()) {
                return truncate(prefix);
            }
        }

        // A name opening with the separator has no prefix to take, and the word
        // fallback would otherwise return the separator itself.
        name = name.replaceFirst("^[:\\-\\s]+", "");
        if (name.isEmpty()) {
            return null;
        }

        for (String prefix : GENERIC_NAME_PREFIXES) {
            if (name.startsWith(prefix + " ")) {
                name = name.substring(prefix.length() + 1).trim();
                break;
            }
        }

        int space = name.indexOf(' ');
        String first = space > 0 ? name.substring(0, space) : name;
        return first.isEmpty() ? null : truncate(first);
    }

    /** Matches the company column's width; a longer name is a naming accident, not a chain. */
    private static String truncate(String value) {
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    /** The leading number in "PM4 ...", or null when the name carries none. */
    Integer pmSequence(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = PM_SEQUENCE.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads coordinates out of a location cell.
     *
     * <p>From {@code value}, not {@code text}: monday renders a location's text as
     * a postal address and drops the coordinates entirely.
     */
    private void applyLocation(PmContract contract, MondayItem item, String columnId) {
        if (columnId == null || columnId.isBlank()) {
            return;
        }
        String raw = item.columnRawValue(columnId);
        if (raw == null) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            contract.setLat(toDecimal(node.path("lat").asText(null)));
            contract.setLng(toDecimal(node.path("lng").asText(null)));
        } catch (Exception e) {
            log.debug("Unparseable location on item {}: {}", item.id(), e.getMessage());
        }
    }

    /**
     * Reads the warranty period from a timeline cell.
     *
     * <p>Again from {@code value}: a timeline's text is two dates joined by a dash,
     * which is ambiguous the moment a board uses a different separator.
     */
    private void applyWarranty(PmContract contract, MondayItem item, String columnId) {
        if (columnId == null || columnId.isBlank()) {
            return;
        }
        contract.setWarrantyText(item.columnText(columnId));
        String raw = item.columnRawValue(columnId);
        if (raw == null) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            contract.setWarrantyStart(toDate(node.path("from").asText(null)));
            contract.setWarrantyEnd(toDate(node.path("to").asText(null)));
        } catch (Exception e) {
            log.debug("Unparseable timeline on item {}: {}", item.id(), e.getMessage());
        }
    }

    private String joinSerials(MondayItem item, List<String> columnIds) {
        List<String> serials = new ArrayList<>();
        for (String columnId : columnIds) {
            String value = text(item, columnId);
            if (value != null && !serials.contains(value)) {
                serials.add(value);
            }
        }
        return serials.isEmpty() ? null : String.join(", ", serials);
    }

    /** Every requested cell as a JSON object, so an unmapped field is still recoverable. */
    private String rawColumns(MondayItem item) {
        if (item.columnValues() == null || item.columnValues().isEmpty()) {
            return null;
        }
        Map<String, String> cells = new LinkedHashMap<>();
        for (MondayColumnValue value : item.columnValues()) {
            if (value.text() != null && !value.text().isBlank()) {
                cells.put(value.id(), value.text());
            }
        }
        try {
            return cells.isEmpty() ? null : objectMapper.writeValueAsString(cells);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(MondayItem item, String columnId) {
        if (columnId == null || columnId.isBlank()) {
            return null;
        }
        String value = item.columnText(columnId);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDate toDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        // monday date cells arrive as yyyy-MM-dd, occasionally with a time appended.
        if (trimmed.length() > 10) {
            trimmed = trimmed.substring(0, 10);
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toInteger(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
