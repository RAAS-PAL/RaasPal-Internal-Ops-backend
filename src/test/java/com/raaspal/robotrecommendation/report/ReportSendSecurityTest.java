package com.raaspal.robotrecommendation.report;

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
 * Only the RE team and admins may email a customer. Every other signed-in role
 * holds a JWT that is just as valid, so this is the line that keeps a warehouse
 * login from starting the monthly delivery run.
 *
 * <p>No request here carries the params or body a send needs. That is the point:
 * the rule lives in the security filter chain, ahead of Spring MVC, so a denied
 * role sees 403 before anything is parsed, while an allowed role gets through to
 * MVC and is turned away with 400 for the missing params - which proves access
 * without ever sending mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportSendSecurityTest {

    private static final String[] SEND_ENDPOINTS = {
            "/api/v1/reports/delivery/run",
            "/api/v1/reports/delivery/send",
            "/api/v1/reports/email",
            "/api/v1/customers/announcements",
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void rejectsAnonymousCallers() throws Exception {
        for (String path : SEND_ENDPOINTS) {
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void rejectsInventoryStaff() throws Exception {
        for (String path : SEND_ENDPOINTS) {
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void rejectsCustomerRole() throws Exception {
        for (String path : SEND_ENDPOINTS) {
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
        }
    }

    /** Past the gate: the missing params are what stop it, not the role. */
    @Test
    @WithMockUser(roles = "RAASPAL_TEAM")
    void allowsTheReTeam() throws Exception {
        for (String path : SEND_ENDPOINTS) {
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isBadRequest());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void allowsAdmin() throws Exception {
        for (String path : SEND_ENDPOINTS) {
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isBadRequest());
        }
    }

    /** The gate is on sending. Reading delivery status stays open to any staff login. */
    @Test
    @WithMockUser(roles = "INVENTORY_STAFF")
    void leavesDeliveryReadsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/reports/delivery/status")).andExpect(status().isOk());
    }
}
