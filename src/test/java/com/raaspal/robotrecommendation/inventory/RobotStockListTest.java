package com.raaspal.robotrecommendation.inventory;

import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The robot-stock list, which RIMS calls on every page for the sidebar.
 *
 * <p>Since 2026-09-28 it is a projection that never selects the photo column, so what
 * the entity used to work out — whether there is a photo, the display name — is now
 * worked out by the query and the response's second constructor. These pin that the
 * list still says the same things it said when it loaded whole rows.
 */
@SpringBootTest
@Transactional
class RobotStockListTest {

    private static final String PHOTO = "data:image/png;base64,iVBORw0KGgo=";

    @Autowired private RobotStockService service;
    @Autowired private RobotStockEntryRepository repository;

    @BeforeEach
    void setUp() {
        repository.save(RobotStockEntry.builder()
                .brand("Gausium").model("Phantas").version("v1.3").quantity(2).imageUrl(PHOTO).build());
        repository.save(RobotStockEntry.builder()
                .brand("Gausium").model("M50").quantity(4).build());
        repository.save(RobotStockEntry.builder()
                .brand("Gausium").model("Omnie").version(" ").quantity(1).imageUrl(PHOTO)
                .status(RobotUnitStatus.DEMO).build());
    }

    @Test
    void theListSaysWhichRobotsHaveAPhotoWithoutLoadingIt() {
        List<RobotStockEntryResponse> rows = service.list(null, null);

        assertThat(rows).extracting(RobotStockEntryResponse::displayName)
                .containsExactly("Gausium M50", "Gausium Omnie", "Gausium Phantas v1.3");
        assertThat(rows).extracting(RobotStockEntryResponse::hasImage)
                .containsExactly(false, true, true);
        assertThat(rows).extracting(RobotStockEntryResponse::quantity).containsExactly(4, 1, 2);
    }

    @Test
    void theStatusFilterAndTheSearchStillNarrowTheList() {
        assertThat(service.list(null, RobotUnitStatus.DEMO))
                .extracting(RobotStockEntryResponse::displayName).containsExactly("Gausium Omnie");
        assertThat(service.list("phantas", null))
                .singleElement().satisfies(row -> {
                    assertThat(row.hasImage()).isTrue();
                    assertThat(row.version()).isEqualTo("v1.3");
                });
    }
}
