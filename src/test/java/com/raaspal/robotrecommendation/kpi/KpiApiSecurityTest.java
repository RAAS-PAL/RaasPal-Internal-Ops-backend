package com.raaspal.robotrecommendation.kpi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The KPI surface is internal: ADMIN and RAASPAL_TEAM only. The deck it
 * replaces is a board document, so a CUSTOMER or INVENTORY_STAFF token must
 * get 403 on every endpoint, not just the sync trigger.
 *
 * <p>Also pins the unconfigured behaviour: no monday token in the test profile,
 * so the sync trigger is a 400 with the env var named, and a live board read
 * is a 502 — never a bare 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:kpi-test.properties")
class KpiApiSecurityTest {

    private static final String BASE = "/api/v1/kpi";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void anonymousIsUnauthorised() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/csat")).andExpect(status().isUnauthorized());
        // The exports carry the same figures in a file; same door.
        mockMvc.perform(get(BASE + "/cm-cases/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/csat/export")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerIsForbidden() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/monday/config")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/monday/sync")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/csat")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/csat/reload")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/cm-cases/export")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/csat/export")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void inventoryStaffIsForbidden() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void teamCanReadAnExplicitPeriod() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases").param("from", "2026-01").param("to", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.from").value("2026-01"))
                .andExpect(jsonPath("$.data.to").value("2026-06"))
                .andExpect(jsonPath("$.data.months.length()").value(6))
                .andExpect(jsonPath("$.data.months[0].month").value("2026-01"))
                .andExpect(jsonPath("$.data.months[5].month").value("2026-06"))
                .andExpect(jsonPath("$.data.provisional").value(true))
                .andExpect(jsonPath("$.data.repeatWindowDays").value(14))
                .andExpect(jsonPath("$.data.installFollowUpDays").value(30))
                .andExpect(jsonPath("$.data.months[0].cleaning.installation").exists())
                .andExpect(jsonPath("$.data.months[0].delivery.cm").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void defaultPeriodIsSixCompleteMonths() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.months.length()").value(6));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void badPeriodsAre400() throws Exception {
        mockMvc.perform(get(BASE + "/cm-cases").param("from", "2026-06").param("to", "2026-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/cm-cases").param("from", "June 2026").param("to", "2026-06"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("YYYY-MM")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void syncWithoutATokenIsA400ThatNamesTheEnvVar() throws Exception {
        mockMvc.perform(post(BASE + "/monday/sync"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("MONDAY_API_TOKEN")));
    }

    /**
     * Nothing uploaded and no folder set: CSAT says what to do about it rather
     * than 500. This is the first thing a new deployment sees.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void csatWithNoWorkbooksIsA400ThatSaysToUploadThem() throws Exception {
        mockMvc.perform(get(BASE + "/csat").param("from", "2026-01").param("to", "2026-06"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("have been uploaded")));
        mockMvc.perform(post(BASE + "/csat/reload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("have been uploaded")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void configNeverLeaksTheTokenAndListsTheBoards() throws Exception {
        mockMvc.perform(get(BASE + "/monday/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenConfigured").value(false))
                .andExpect(jsonPath("$.data.schedulerEnabled").value(false))
                .andExpect(jsonPath("$.data.repeatWindowDays").value(14))
                .andExpect(jsonPath("$.data.boards.length()").value(3))
                .andExpect(jsonPath("$.data.boards[0].serviceLine").value("CLEANING"))
                .andExpect(jsonPath("$.data.boards[1].columns.openDate").value("date5"))
                .andExpect(jsonPath("$.data.boards[1].columns.serial").value("tags42"))
                .andExpect(jsonPath("$.data.boards[2].ticketType").value("INSTALLATION"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusAndRunsAreReadable() throws Exception {
        mockMvc.perform(get(BASE + "/monday/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.configured").value(false));
        mockMvc.perform(get(BASE + "/monday/sync/runs").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void liveBoardReadWithoutATokenIsA502NotA500() throws Exception {
        mockMvc.perform(get(BASE + "/monday/boards/3451717331"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("token")));
    }
}
