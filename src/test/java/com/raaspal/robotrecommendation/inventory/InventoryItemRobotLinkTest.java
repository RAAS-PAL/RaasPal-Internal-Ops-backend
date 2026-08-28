package com.raaspal.robotrecommendation.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.InventoryItemRepository;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Linking spare parts to the warehouse robots they fit.
 *
 * <p>The relationship is many-to-many — one filter fits both the M50 and the
 * M75 — and it targets {@code robot_inventory_temp} (what the warehouse actually
 * stocks), not the {@code robots} catalogue: only 15% of warehouse models exist
 * there, so a catalogue link would leave most parts unlinkable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class InventoryItemRobotLinkTest {

    private static final String ITEMS = "/api/v1/inventory/items";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RobotStockEntryRepository robotStockRepository;
    @Autowired private InventoryItemRepository itemRepository;

    private RobotStockEntry m50;
    private RobotStockEntry m75;

    @BeforeEach
    void setUp() {
        itemRepository.deleteAll();
        m50 = robotStockRepository.save(RobotStockEntry.builder()
                .brand("Gausium").model("M50").version("v1.2").quantity(3).build());
        m75 = robotStockRepository.save(RobotStockEntry.builder()
                .brand("Gausium").model("M75").quantity(1).build());
    }

    private String createItem(String name, List<UUID> robotStockIds) throws Exception {
        String body = mockMvc.perform(post(ITEMS)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Explicit SKU: auto-generation reads a Postgres sequence from
                        // V28, and Flyway is off under H2 so it does not exist here.
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", "TST-" + name.replaceAll("\\s", "-"),
                                "name", name,
                                "category", "FILTER",
                                "robotStockIds", robotStockIds))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    /** The point of the feature: one part, several robots. */
    @Test
    void aPartCanBeLinkedToSeveralRobotsAndNamesThemInTheResponse() throws Exception {
        String id = createItem("HEPA Filter", List.of(m50.getId(), m75.getId()));

        mockMvc.perform(get(ITEMS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.robots.length()").value(2))
                // Sorted by display name, so the order is stable for the UI.
                .andExpect(jsonPath("$.data.robots[0].displayName").value("Gausium M50 v1.2"))
                .andExpect(jsonPath("$.data.robots[1].displayName").value("Gausium M75"));
    }

    /** The robot detail page's query: only parts linked to this robot. */
    @Test
    void filteringByRobotReturnsOnlyThatRobotsParts() throws Exception {
        createItem("M50 Brush", List.of(m50.getId()));
        createItem("Shared Filter", List.of(m50.getId(), m75.getId()));
        createItem("Universal Detergent", List.of());

        mockMvc.perform(get(ITEMS).param("robotStockId", m75.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("Shared Filter"));

        mockMvc.perform(get(ITEMS).param("robotStockId", m50.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    /** No filter still returns everything, universal items included. */
    @Test
    void theUnfilteredListIncludesUniversalItems() throws Exception {
        createItem("Universal Detergent", List.of());

        mockMvc.perform(get(ITEMS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].robots.length()").value(0));
    }

    /**
     * The form submits what is ticked, so a save replaces the whole set —
     * unticking a robot removes the link, and the removal survives a reload.
     */
    @Test
    void updatingReplacesTheWholeLinkSet() throws Exception {
        String id = createItem("Filter", List.of(m50.getId(), m75.getId()));

        mockMvc.perform(put(ITEMS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Filter",
                                "category", "FILTER",
                                "robotStockIds", List.of(m75.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.robots.length()").value(1))
                .andExpect(jsonPath("$.data.robots[0].displayName").value("Gausium M75"));

        mockMvc.perform(get(ITEMS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.robots.length()").value(1));
    }

    /** One bad id fails the save whole, naming the id — no half-written link set. */
    @Test
    void linkingToAnUnknownRobotIsRejected() throws Exception {
        UUID ghost = UUID.randomUUID();
        mockMvc.perform(post(ITEMS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", "TST-GHOST",
                                "name", "Brush",
                                "category", "BRUSH",
                                "robotStockIds", List.of(m50.getId(), ghost)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown robot: " + ghost));
    }

    /** View-only accounts (RAASPAL_TEAM) can read parts but not create them. */
    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void viewOnlyAccountsCannotWriteButCanRead() throws Exception {
        mockMvc.perform(get(ITEMS)).andExpect(status().isOk());

        mockMvc.perform(post(ITEMS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", "TST-RO", "name", "Brush", "category", "BRUSH"))))
                .andExpect(status().isForbidden());
    }
}
