package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shift-type lookup the shell's ShiftRing reads, seen from a role that only
 * ever reads it.
 *
 * <p>WHY THIS EXISTS. The shell shows the ShiftRing to EVERY signed-in user, and
 * the ring fetches {@code /api/shifts/active-shifts} on mount. That path sat under
 * the WORK_RECORD_VIEW rule, which commercial (and production_coordinator and
 * accountant) do not hold — so the shared shell's own request was refused for
 * them, a 403 the client can read as a lost session and bounce to login.
 *
 * <p>Exactly the shape of the {@code active-work-code-categories} mistake this
 * project already made once: the matrix was right and the URL mapping was wrong,
 * and no unit test could see it. So this goes through MockMvc — what is under test
 * is SecurityConfig, which the services know nothing about.
 */
class ShiftRingIsSeenByTheWholeShellIT extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("commercial staff can load the ShiftRing lookup — the shell shows it to them")
    void commercialReadsActiveShifts() throws Exception {
        mvc.perform(get("/api/shifts/active-shifts")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "production_coordinator")
    @DisplayName("and so can the production coordinator")
    void coordinatorReadsActiveShifts() throws Exception {
        mvc.perform(get("/api/shifts/active-shifts")).andExpect(status().isOk());
    }

    /**
     * Reading the shift-type list is not reading anybody's shifts. Everything else
     * under that path stays behind WORK_RECORD_VIEW — refused for commercial with
     * 403, not 401.
     */
    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("but the rest of /api/shifts stays behind WORK_RECORD_VIEW")
    void commercialCannotReadOtherShiftEndpoints() throws Exception {
        mvc.perform(get("/api/shifts/something-else")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the supervisor still reads it, as before")
    void supervisorReadsActiveShifts() throws Exception {
        mvc.perform(get("/api/shifts/active-shifts")).andExpect(status().isOk());
    }
}
