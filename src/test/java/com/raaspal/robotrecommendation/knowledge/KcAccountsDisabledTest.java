package com.raaspal.robotrecommendation.knowledge;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No property override: the production default and service fallback must be disabled. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class KcAccountsDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean KcCodeMailer mail;
    @MockitoBean KcAuthService accounts;

    @ParameterizedTest
    @ValueSource(strings = {"send-code", "verify-code", "signup", "reset"})
    void allPublicAccountEndpointsAreUnavailableBeforeBodyParsing(String endpoint) throws Exception {
        for (String body : new String[]{"{}", "{malformed", ""}) {
            mvc.perform(post("/api/v1/kc/auth/" + endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("unavailable"));
        }
        verifyNoInteractions(accounts, mail);
    }
}
