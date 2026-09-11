package com.raaspal.robotrecommendation.pm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PM planner exposes the whole customer estate - every site, its robots and
 * when an engineer will be there - so it is staff-only, and this pins that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PmApiSecurityTest {

    private static final String BASE = "/api/v1/pm";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void rejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get(BASE + "/year")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/month")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/filters")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/monday/sync").with(csrf())).andExpect(status().isUnauthorized());
    }

    /** A customer login must not see other customers' schedules. */
    @Test
    @WithMockUser(roles = "CUSTOMER")
    void rejectsCustomerRole() throws Exception {
        mockMvc.perform(get(BASE + "/year")).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/filters")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void rejectsUnrelatedStaffRole() throws Exception {
        mockMvc.perform(get(BASE + "/year")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void allowsTheReTeam() throws Exception {
        mockMvc.perform(get(BASE + "/filters")).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/year")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void allowsAdmin() throws Exception {
        mockMvc.perform(get(BASE + "/year")).andExpect(status().isOk());
    }

    /** A year outside the supported range is a client error, not an empty grid. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAnImpossibleYear() throws Exception {
        mockMvc.perform(get(BASE + "/year").param("year", "1899")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAHalfSuppliedDateRange() throws Exception {
        mockMvc.perform(get(BASE + "/month").param("from", "2026-09-01")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/month").param("month", "September")).andExpect(status().isBadRequest());
    }
}
