package com.raaspal.robotrecommendation.inventory;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.inventory.dto.*;
import com.raaspal.robotrecommendation.inventory.entity.InventoryItem;
import com.raaspal.robotrecommendation.inventory.entity.Packaging;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.InventoryItemRepository;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Model operations keep shelf rows and part links intact; no serials or schema change. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class RobotStockModelTest {
    private static final String API = "/api/v1/inventory/robot-stock/";
    private static final UUID ACTOR = UUID.randomUUID();
    @Autowired private RobotStockService stock;
    @Autowired private RobotStockEntryRepository robots;
    @Autowired private InventoryItemRepository parts;
    @Autowired private EntityManager em;
    @Autowired private MockMvc mvc;

    @Test
    void moveUpdatesBothCountsAndUndoWithoutChangingShelfMetadata() {
        var source = shelf(IN_STOCK, 4);
        var target = shelf(DEMO, 2);
        target.setPackaging(Packaging.UNBOX);
        target.setLocation("Demo shelf");
        target.setNote("Keep this note");
        var rows = stock.move(source.getId(), new RobotStockMoveRequest(DEMO, 3), ACTOR);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().mapToInt(RobotStockEntryResponse::quantity).sum()).isEqualTo(6);
        assertCount(source.getId(), 1, 4);
        assertCount(target.getId(), 5, 2);
        var saved = robots.findById(target.getId()).orElseThrow();
        assertThat(saved.getPackaging()).isEqualTo(Packaging.UNBOX);
        assertThat(saved.getLocation()).isEqualTo("Demo shelf");
        assertThat(saved.getNote()).isEqualTo("Keep this note");
    }

    @Test
    void moveCreatesStatusCopiesPhotoAndLinksAndKeepsEmptySource() {
        var source = shelf(IN_STOCK, 2);
        source.setImageUrl("https://example.test/robot.png");
        source.setPackaging(Packaging.BOX);
        source.setLocation("Original shelf");
        source.setNote("Original note");
        UUID part = linkedPart(source.getId());
        var rows = stock.move(source.getId(), new RobotStockMoveRequest(USED_READY, 2), ACTOR);
        UUID target = rows.stream().filter(r -> r.status() == USED_READY).findFirst().orElseThrow().id();
        assertCount(source.getId(), 0, 2);
        assertCount(target, 2, 0);
        em.clear();
        var saved = robots.findById(target).orElseThrow();
        assertThat(saved.getRobotType()).isEqualTo(RobotType.DELIVERY);
        assertThat(saved.getBrand()).isEqualTo("Autoxing");
        assertThat(saved.getModel()).isEqualTo("AX-1612");
        assertThat(saved.getImageUrl()).isEqualTo("https://example.test/robot.png");
        assertThat(saved.getPackaging()).isNull();
        assertThat(saved.getLocation()).isNull();
        assertThat(saved.getNote()).isNull();
        assertThat(parts.findById(part).orElseThrow().getRobotStockIds()).containsExactlyInAnyOrder(source.getId(), target);
    }

    @Test
    void moveBeyondAvailableIsRejectedBeforeCreatingAnything() {
        var source = shelf(IN_STOCK, 1);
        assertThatThrownBy(() -> stock.move(source.getId(), new RobotStockMoveRequest(DEMO, 2), ACTOR))
                .isInstanceOf(BadRequestException.class).hasMessage("robot_stock.insufficient_units");
        assertThat(robots.findModel("Autoxing", "AX-1612", "")).hasSize(1);
        assertThat(source.getQuantity()).isEqualTo(1);
        assertThat(source.getPreviousQuantity()).isNull();
    }

    @Test
    void moveToSameStatusIsRejected() {
        var source = shelf(IN_STOCK, 1);
        assertThatThrownBy(() -> stock.move(source.getId(), new RobotStockMoveRequest(IN_STOCK, 1), ACTOR))
                .hasMessage("robot_stock.same_status");
    }

    @Test
    void addUnitsCreatesWithLinksThenIncrementsTheSameRow() {
        var source = shelf(IN_STOCK, 1);
        UUID part = linkedPart(source.getId());
        stock.addUnits(source.getId(), new RobotStockUnitsRequest(UNDER_REPAIR, 2), ACTOR);
        var rows = stock.addUnits(source.getId(), new RobotStockUnitsRequest(UNDER_REPAIR, 3), ACTOR);
        UUID target = rows.stream().filter(r -> r.status() == UNDER_REPAIR).findFirst().orElseThrow().id();
        assertCount(target, 5, 2);
        assertThat(rows).hasSize(2);
        em.clear();
        assertThat(parts.findById(part).orElseThrow().getRobotStockIds()).containsExactlyInAnyOrder(source.getId(), target);
        assertThat(robots.findById(source.getId()).orElseThrow().getQuantity()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = RobotUnitStatus.class, names = {"RENT", "SOLD"})
    void customerStatusesAreRejectedForBothOperations(RobotUnitStatus status) throws Exception {
        var source = shelf(IN_STOCK, 2);
        mvc.perform(post(API + source.getId() + "/move").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"" + status + "\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + source.getId() + "/units").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null"})
    void invalidAmountsNeverReachTheService(String quantity) throws Exception {
        var source = shelf(IN_STOCK, 2);
        mvc.perform(post(API + source.getId() + "/move").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"DEMO\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + source.getId() + "/units").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEMO\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isBadRequest());
        assertThat(source.getQuantity()).isEqualTo(2);
    }

    @Test
    void renameAllRowsIncludingZeroAndPreserveCountsUndoAndLinks() {
        var source = shelf(IN_STOCK, 2);
        var empty = shelf(DEMO, 0);
        // Identity is case-insensitive and ignores brand/model edge whitespace.
        empty.setBrand(" autoxing ");
        empty.setModel("ax-1612 ");
        empty.setVersion("");
        source.setPreviousQuantity(9);
        UUID part = linkedPart(source.getId());
        var rows = stock.updateModel(empty.getId(), new RobotStockModelRequest(
                RobotType.CLEANING, " New brand ", "New model", "v2", "https://example.test/new.png"), ACTOR);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.brand()).isEqualTo("New brand");
            assertThat(row.model()).isEqualTo("New model");
            assertThat(row.version()).isEqualTo("v2");
            assertThat(row.robotType()).isEqualTo(RobotType.CLEANING);
            assertThat(row.hasImage()).isTrue();
        });
        em.clear();
        assertThat(robots.findById(source.getId()).orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(robots.findById(source.getId()).orElseThrow().getPreviousQuantity()).isEqualTo(9);
        assertThat(robots.findById(empty.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(parts.findById(part).orElseThrow().getRobotStockIds()).containsExactly(source.getId());
    }

    @Test
    void renameRejectsDifferentModelEvenIfItHasOnlyAnotherStatus() {
        var source = shelf(IN_STOCK, 1);
        robots.saveAndFlush(RobotStockEntry.builder().brand("Other").model("Model").status(DEMO).build());
        assertThatThrownBy(() -> stock.updateModel(source.getId(), new RobotStockModelRequest(
                RobotType.DELIVERY, " other ", "MODEL", null, null), ACTOR))
                .hasMessage("robot_stock.model_exists");
        assertThat(source.getBrand()).isEqualTo("Autoxing");
    }

    @Test
    void omittedImageKeepsEachPhotoAndEmptyRemovesEveryPhoto() {
        var source = shelf(IN_STOCK, 1);
        var demo = shelf(DEMO, 1);
        source.setImageUrl("https://example.test/first.png");
        demo.setImageUrl("https://example.test/second.png");
        stock.updateModel(source.getId(), modelRequest(null), ACTOR);
        assertThat(source.getImageUrl()).isEqualTo("https://example.test/first.png");
        assertThat(demo.getImageUrl()).isEqualTo("https://example.test/second.png");
        stock.updateModel(demo.getId(), modelRequest(""), ACTOR);
        em.clear();
        assertThat(robots.findById(source.getId()).orElseThrow().getImageUrl()).isNull();
        assertThat(robots.findById(demo.getId()).orElseThrow().getImageUrl()).isNull();
    }

    @Test
    void invalidImageAndOverflowCannotChangeTheModel() {
        var source = shelf(IN_STOCK, Integer.MAX_VALUE);
        assertThatThrownBy(() -> stock.updateModel(source.getId(), modelRequest("file:///private"), ACTOR))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> stock.addUnits(source.getId(), new RobotStockUnitsRequest(IN_STOCK, 1), ACTOR))
                .hasMessage("robot_stock.quantity_too_large");
        assertThat(source.getQuantity()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void legacyQuantityEditStillSupportsUndoAndLeavesItAloneOnMetadataOnlySave() {
        var source = shelf(IN_STOCK, 4);
        stock.addUnits(source.getId(), new RobotStockUnitsRequest(IN_STOCK, 2), ACTOR);
        stock.update(source.getId(), new RobotStockEntryRequest(RobotType.DELIVERY, "Autoxing", "AX-1612",
                null, null, 6, IN_STOCK, Packaging.BOX, "Shelf B", null), ACTOR);
        assertCount(source.getId(), 6, 4);
        stock.update(source.getId(), new RobotStockEntryRequest(RobotType.DELIVERY, "Autoxing", "AX-1612",
                null, null, 4, IN_STOCK, Packaging.BOX, "Shelf B", null), ACTOR);
        assertCount(source.getId(), 4, 6);
    }

    @Test
    void deleteModelRemovesEveryStatusRowAndUnlinksPartsButLeavesOtherModels() {
        var stockRow = shelf(IN_STOCK, 2);
        var demoRow = shelf(DEMO, 0);
        var other = robots.saveAndFlush(RobotStockEntry.builder().robotType(RobotType.DELIVERY)
                .brand("Autoxing").model("AX-9999").status(IN_STOCK).quantity(1).build());
        UUID part = linkedPart(demoRow.getId());

        assertThat(stock.deleteModel(stockRow.getId())).isEqualTo(2);
        em.flush();
        em.clear();

        assertThat(robots.findById(stockRow.getId())).isEmpty();
        assertThat(robots.findById(demoRow.getId())).isEmpty();
        assertThat(robots.findById(other.getId())).isPresent();
        assertThat(parts.findById(part).orElseThrow().getRobotStockIds()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void warehouseStaffCanUseAllThreeEndpointsAndReceiveTheWholeModel() throws Exception {
        var source = shelf(IN_STOCK, 2);
        mvc.perform(post(API + source.getId() + "/move").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"DEMO\",\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(post(API + source.getId() + "/units").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"USED_READY\",\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(put(API + source.getId() + "/model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"robotType\":\"DELIVERY\",\"brand\":\"Autoxing\",\"model\":\"Renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(delete(API + source.getId() + "/model")).andExpect(status().isOk());
        assertThat(robots.findById(source.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void readOnlyStaffCanReadButCannotUseAnyModelWrite() throws Exception {
        var source = shelf(IN_STOCK, 2);
        mvc.perform(get(API + source.getId())).andExpect(status().isOk());
        mvc.perform(post(API + source.getId() + "/move").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"DEMO\",\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(API + source.getId() + "/units").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEMO\",\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(API + source.getId() + "/model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"robotType\":\"DELIVERY\",\"brand\":\"Autoxing\",\"model\":\"Renamed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(API + source.getId() + "/model")).andExpect(status().isForbidden());
        assertThat(robots.findById(source.getId())).isPresent();
    }

    private RobotStockEntry shelf(RobotUnitStatus status, int quantity) {
        return robots.saveAndFlush(RobotStockEntry.builder().robotType(RobotType.DELIVERY)
                .brand("Autoxing").model("AX-1612").status(status).quantity(quantity).build());
    }

    private UUID linkedPart(UUID source) {
        return parts.saveAndFlush(InventoryItem.builder().sku("TST-" + UUID.randomUUID()).name("Test part")
                .category("FILTER").robotStockIds(new HashSet<>(Set.of(source))).build()).getId();
    }

    private void assertCount(UUID id, int quantity, int previous) {
        var row = robots.findById(id).orElseThrow();
        assertThat(row.getQuantity()).isEqualTo(quantity);
        assertThat(row.getPreviousQuantity()).isEqualTo(previous);
        assertThat(row.getPreviousQuantityAt()).isNotNull();
        assertThat(row.getUpdatedBy()).isEqualTo(ACTOR);
    }

    private RobotStockModelRequest modelRequest(String image) {
        return new RobotStockModelRequest(RobotType.DELIVERY, "Autoxing", "AX-1612", null, image);
    }
}
