package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order screens, seen from each role, through the real filter chain.
 *
 * <p>WHY THIS EXISTS. Splitting production orders into PRODUCTION_ORDER_VIEW and
 * _MANAGE was done with URL rules, and a URL rule is easy to get subtly wrong in
 * a way no unit test notices: {@code POST /search-all} is a READ — it carries the
 * paging and filter payload — and it landed under the WRITE rule. The result was
 * that the entire order list answered 403 to the supervisor, which is exactly the
 * person the split was introduced for. The matrix test could not catch it,
 * because the matrix was right; the mapping was wrong.
 *
 * <p>These go through MockMvc rather than the service layer on purpose. What is
 * being tested is SecurityConfig, and the service knows nothing about it.
 */
class OrderIsReadByTheFloorAndWrittenByCommercialIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /*
     * Built by hand rather than with @AutoConfigureMockMvc: this project does not
     * carry spring-boot-test-autoconfigure. springSecurity() is what matters —
     * without it the filter chain under test would not be in the request path at
     * all, and every one of these assertions would pass for the wrong reason.
     */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static final String EMPTY_SEARCH = "{}";

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the supervisor can list orders — the list is a POST, and it is a read")
    void supervisorListsOrders() throws Exception {
        mvc.perform(post("/api/production-orders/search-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_SEARCH))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("and can open one")
    void supervisorOpensAnOrder() throws Exception {
        /*
         * 404, not 200: there is no such order in an empty database. That is the
         * point — a 404 is the HANDLER answering, which means the request got
         * past authorization. A 403 here would be the filter chain answering.
         */
        mvc.perform(get("/api/production-orders/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("and is refused when writing one — with 403, not 401")
    void supervisorCannotCreateAnOrder() throws Exception {
        /*
         * The status matters as much as the refusal. The client treats 401 as
         * "who are you" and signs the person out of the whole application; 403 is
         * "not you" and leaves the session alone. A refusal that arrived as 401
         * would log somebody out for opening the wrong screen.
         */
        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_SEARCH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("commercial staff can list orders too")
    void commercialListsOrders() throws Exception {
        mvc.perform(post("/api/production-orders/search-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_SEARCH))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "production_coordinator")
    @DisplayName("the production coordinator is refused the orders entirely")
    void coordinatorSeesNoOrders() throws Exception {
        mvc.perform(post("/api/production-orders/search-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_SEARCH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("commercial staff are refused a worker's card — the other half of the split")
    void commercialSeesNoRecords() throws Exception {
        mvc.perform(get("/api/employee-records"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and can still read the catalogue, which is everybody's")
    void commercialReadsTheCatalogue() throws Exception {
        mvc.perform(post("/api/products/search-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_SEARCH))
                .andExpect(status().isOk());
    }
}
