package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalogue screens, seen from a role that only READS them.
 *
 * <p>WHY THIS EXISTS. "Products and operations are the whole company's" is a
 * rule about SCREENS, and a screen is more than the one endpoint it is named
 * after. The operations page loads the operations, the products behind them and
 * the list of work-code categories its filter offers — and that last one lived
 * under the work-record rule, so the page answered 403 to commercial staff on a
 * page that is deliberately theirs to read.
 *
 * <p>Exactly the shape of the `search-all` mistake this project has already made
 * once: the matrix was right and the URL mapping was wrong, and no unit test
 * could see it. So these go through MockMvc — what is under test is
 * SecurityConfig, which the services know nothing about.
 */
class CatalogueIsReadByTheWholeCompanyIT extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("commercial staff can list operations — the list is a POST, and it is a read")
    void commercialListsOperations() throws Exception {
        mvc.perform(post("/api/operations/search-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and the products behind them")
    void commercialListsProducts() throws Exception {
        mvc.perform(get("/api/products/active-products")).andExpect(status().isOk());
    }

    /**
     * The one this test was written for. The category is a COLUMN of the
     * operations table and the filter above it, so a reader who may open the
     * page must be able to load the list.
     */
    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and the categories the operations screen filters by")
    void commercialListsWorkCodeCategories() throws Exception {
        mvc.perform(get("/api/work-code-categories/active-work-code-categories"))
                .andExpect(status().isOk());
    }

    /**
     * Reading the LIST is not reading anybody's work. Everything else under that
     * path stays where it was.
     */
    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("but not the work records those categories describe")
    void commercialCannotReadWorkRecords() throws Exception {
        mvc.perform(get("/api/work-code-categories")).andExpect(status().isForbidden());
        mvc.perform(get("/api/work-logs")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and writing to the catalogue is still refused — with 403, not 401")
    void commercialCannotWriteTheCatalogue() throws Exception {
        mvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * The other half of the same rule: whoever owns the work records keeps
     * everything they had.
     */
    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the supervisor still reads the categories and the records both")
    void supervisorKeepsBoth() throws Exception {
        mvc.perform(get("/api/work-code-categories/active-work-code-categories"))
                .andExpect(status().isOk());

        /*
         * NOT refused, rather than 200: /api/work-logs needs parameters this
         * request does not carry, so the handler answers with a failure of its
         * own. That is the point — any answer from the HANDLER means the request
         * got past the filter chain, which is the only thing this file is about.
         */
        int status = mvc.perform(get("/api/work-logs")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }
}
