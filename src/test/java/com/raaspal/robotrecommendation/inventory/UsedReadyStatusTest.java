package com.raaspal.robotrecommendation.inventory;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.service.InventoryService;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Used (Ready to Use)", added at the warehouse's request in October 2026: a used robot
 * that has been checked over and can go out again. It sits on a shelf like the other
 * store-room states, but unlike them it is available, so the dashboard counts it as
 * stock. The fleet's own statuses are untouched.
 */
@SpringBootTest
@Transactional
class UsedReadyStatusTest {

    @Autowired private RobotStockService stock;
    @Autowired private RobotStockEntryRepository repository;
    @Autowired private InventoryService inventory;

    @Test
    void itIsAShelfStateThatIsAvailableButNeverAFleetState() {
        assertThat(RobotUnitStatus.USED_READY.isStockRoomStatus()).isTrue();
        assertThat(RobotUnitStatus.USED_READY.isAvailable()).isTrue();
        assertThat(RobotUnitStatus.USED_READY.isWarehouseVisible()).isFalse();

        // Nothing else moved: only new stock was available before, and still is.
        assertThat(RobotUnitStatus.IN_STOCK.isAvailable()).isTrue();
        assertThat(RobotUnitStatus.DEMO.isAvailable()).isFalse();
        assertThat(RobotUnitStatus.UNDER_REPAIR.isAvailable()).isFalse();
        assertThat(RobotUnitStatus.RETURNED_FROM_CUSTOMER.isAvailable()).isFalse();
    }

    @Test
    void theWarehouseCanRecordAShelfOfUsedReadyRobots() {
        RobotStockEntryResponse saved = stock.create(request("Gausium", "M50", 3, RobotUnitStatus.USED_READY), null);

        assertThat(saved.status()).isEqualTo(RobotUnitStatus.USED_READY);
        assertThat(repository.findById(saved.id())).get()
                .extracting(RobotStockEntry::getStatus).isEqualTo(RobotUnitStatus.USED_READY);
    }

    @Test
    void theRefusalNamesTheNewStatusAmongTheChoices() {
        assertThatThrownBy(() -> stock.create(request("Gausium", "M50", 1, RobotUnitStatus.RENT), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Used (Ready to Use)");
    }

    @Test
    void robotsInStockCountsNewAndUsedReadyButNotTheOtherShelves() {
        long before = inventory.getSummary().robotsInStock();
        long demoBefore = inventory.getSummary().robotsOnDemo();

        stock.create(request("Gausium", "M75", 2, RobotUnitStatus.IN_STOCK), null);
        stock.create(request("Gausium", "M75", 3, RobotUnitStatus.USED_READY), null);
        stock.create(request("Gausium", "M75", 4, RobotUnitStatus.RETURNED_FROM_CUSTOMER), null);
        stock.create(request("Gausium", "M75", 5, RobotUnitStatus.UNDER_REPAIR), null);
        stock.create(request("Gausium", "M75", 6, RobotUnitStatus.DEMO), null);

        assertThat(inventory.getSummary().robotsInStock()).isEqualTo(before + 2 + 3);
        assertThat(inventory.getSummary().robotsOnDemo()).isEqualTo(demoBefore + 6);
    }

    private static RobotStockEntryRequest request(String brand, String model, int quantity, RobotUnitStatus status) {
        return new RobotStockEntryRequest(RobotType.CLEANING, brand, model, null, null, quantity, status, null, null, null);
    }
}
