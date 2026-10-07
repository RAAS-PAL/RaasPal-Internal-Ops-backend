package com.raaspal.robotrecommendation.casereport.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sharing a details page reaches customers, so only the RE team and admins may do it;
 * opening a shared link needs no account at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseShareSecurityTest {

    private static final String LINKS = "/api/v1/case-reports/share-links";
    private static final String PUBLIC = "/api/v1/reports/public/cases/";
    private static final String BODY = "{\"kind\":\"SHEET\",\"sheet\":\"cleaning\",\"customers\":[\"Acme Foods\"],\"days\":7}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithAnonymousUser
    void anonymousCallersCannotShareButCanOpenALink() throws Exception {
        mockMvc.perform(get(LINKS).param("kind", "SHEET").param("sheet", "mk")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(LINKS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        // Public, so an unknown link is "not found" rather than "sign in".
        mockMvc.perform(get(PUBLIC + "no-such-link")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void warehouseLoginsCannotShare() throws Exception {
        mockMvc.perform(get(LINKS).param("kind", "SHEET").param("sheet", "mk")).andExpect(status().isForbidden());
        mockMvc.perform(post(LINKS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerLoginsCannotShare() throws Exception {
        mockMvc.perform(post(LINKS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    /** A tab for a period: shared by the team, opened with no account; its Excel is the team's. */
    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void theReTeamSharesATabForAPeriodAndDownloadsItsExcel() throws Exception {
        String body = "{\"kind\":\"VIEW\",\"view\":\"mk\",\"cadence\":\"MONTHLY\","
                + "\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"}";
        String created = mockMvc.perform(post(LINKS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(created).path("data").path("token").asText();

        mockMvc.perform(get(PUBLIC + token).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("VIEW"))
                .andExpect(jsonPath("$.data.view").value("mk"))
                .andExpect(jsonPath("$.data.from").value("2026-10-01"));

        mockMvc.perform(get("/api/v1/case-reports/views/mk/export").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"mk-cases-2026-10-01-to-2026-10-31.xlsx\""));
        mockMvc.perform(get("/api/v1/case-reports/views/payroll/export")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/case-reports/views/mk/export").param("from", "2026-10-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    void aTabsExcelNeedsAnAccount() throws Exception {
        mockMvc.perform(get("/api/v1/case-reports/views/mk/export")).andExpect(status().isUnauthorized());
    }

    /** Share, open it with no account, stop it, and it no longer opens. */
    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void theReTeamSharesAndStopsALink() throws Exception {
        String created = mockMvc.perform(post(LINKS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode link = objectMapper.readTree(created).path("data");

        mockMvc.perform(get(LINKS).param("kind", "SHEET").param("sheet", "cleaning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].customers[0]").value("Acme Foods"));

        mockMvc.perform(get(PUBLIC + link.path("token").asText()).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.customers[0]").value("Acme Foods"));

        mockMvc.perform(delete(LINKS + "/" + link.path("id").asText()).with(csrf())).andExpect(status().isOk());

        mockMvc.perform(get(PUBLIC + link.path("token").asText()).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.rows").isEmpty());
    }
}
