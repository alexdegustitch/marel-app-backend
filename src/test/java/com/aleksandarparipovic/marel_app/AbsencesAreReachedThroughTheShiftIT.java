package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceCategoryCodes;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceLogWriter;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The absence endpoints, through the door they actually sit behind.
 *
 * <p>WHY THIS EXISTS, and not only the service tests beside it. What is under
 * test here is {@code SecurityConfig} and the URL mapping, which the services
 * know nothing about: {@code /api/absences/**} was added to the work-record rule
 * by hand, and getting that matcher wrong answers 403 on a screen that is
 * deliberately the shop floor's. This project has already made exactly that
 * mistake twice — see {@code CatalogueIsReadByTheWholeCompanyIT} — and no unit
 * test could see either one.
 *
 * <p>The refusals are here for the same reason. A guard that throws is only half
 * of a rule; the other half is the status it reaches the client as, and a
 * ConflictException that came back as 500 would look like a bug in the app
 * rather than a sentence the user has to read.
 */
@Transactional
class AbsencesAreReachedThroughTheShiftIT extends AbstractIntegrationTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 19);
    private static final int FULL_SHIFT = 480;

    @Autowired private WebApplicationContext context;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private AbsenceRecordRepository absenceRepository;
    @Autowired private AbsenceLogWriter absenceLogWriter;

    private MockMvc mvc;
    private WorkShift shift;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Employee employee = fixture.scenario().period(YearMonth.of(2026, 8)).build().employee();
        shift = fixture.workShift(employee, WORK_DATE, 6, FULL_SHIFT);
    }

    // ── The door ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the shop floor may read a shift's absences, its gaps and its bank")
    void theFloorMayReadTheScreen() throws Exception {
        mvc.perform(get("/api/absences/shift/" + shift.getId())).andExpect(status().isOk());
        mvc.perform(get("/api/absences/shift/" + shift.getId() + "/suggestions")).andExpect(status().isOk());
        mvc.perform(get("/api/absences/shift/" + shift.getId() + "/overtime-bank")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and a role without the work records does not — the absence is one of them")
    void commercialIsRefused() throws Exception {
        mvc.perform(get("/api/absences/shift/" + shift.getId())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("signed out, nothing at all")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/absences/shift/" + shift.getId())).andExpect(status().isUnauthorized());
    }

    // ── What may be chosen ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the only absence offered is NO — godišnji odmor is not this screen's business")
    void onlyUnpaidAbsenceIsOffered() throws Exception {
        mvc.perform(get("/api/absences/categories").param("workDate", WORK_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].categoryNo").value(AbsenceCategoryCodes.UNPAID_ABSENCE));
    }

    // ── Recording one ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("two missing hours are recorded, and land in absence_records")
    void recordsAGap() throws Exception {
        mvc.perform(post("/api/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(unpaidCategoryId(), 360, 480)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absenceMinutes").value(120))
                // Nothing decides the outcome at this point: that is the
                // allocation's answer, on the next pass.
                .andExpect(jsonPath("$.outcome").doesNotExist());

        assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the same hours twice come back as a conflict, not a second row")
    void refusesAnOverlap() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                .content(body(unpaidCategoryId(), 360, 480))).andExpect(status().isOk());

        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(unpaidCategoryId(), 420, 480)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("preklapa")));

        assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("outside the shift is a conflict")
    void refusesOutsideTheShift() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(unpaidCategoryId(), 360, 600)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("unutar smene")));
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("ND posted directly is refused — it is never something anybody types")
    void refusesNonWorkingDay() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(categoryId(AbsenceCategoryCodes.NON_WORKING_DAY), 0, 480)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ND")));
    }

    /**
     * The dropdown offers only NO, but a dropdown is a convenience and nothing
     * stops a client posting an id it never saw offered.
     */
    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("a paid absence posted directly is refused too, however it was obtained")
    void refusesAPaidAbsence() throws Exception {
        WorkCodeCategory paidLeave = categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-GO").categoryName("Godišnji odmor").type("ABSENCE")
                .isPaid(true).normMultiplier(1d).isActive(true).fixedHourlyRate(false)
                .affectsMealAllowance(false).allowsParallelWork(false)
                .displayOrder(95).baseCategory(false).build());

        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(paidLeave.getId(), 360, 480)))
                .andExpect(status().isConflict());
    }

    /**
     * Both doors lead to the same state. A whole day recorded here draws itself
     * on the karton exactly as one entered in the work form does — otherwise the
     * shift would look empty, indistinguishable from one nobody has filled in.
     */
    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("a whole shift recorded here also draws the NO log the work form would have")
    void aWholeDayWritesItsLogToo() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(unpaidCategoryId(), 0, FULL_SHIFT)))
                .andExpect(status().isOk());

        assertThat(absenceLogWriter.findLog(shift, AbsenceCategoryCodes.UNPAID_ABSENCE)).isPresent();
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("but part of one draws nothing — the rest of the day is still standing")
    void aGapDrawsNoLog() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                        .content(body(unpaidCategoryId(), 360, FULL_SHIFT)))
                .andExpect(status().isOk());

        assertThat(absenceLogWriter.findLog(shift, AbsenceCategoryCodes.UNPAID_ABSENCE)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("and withdrawing the whole day takes its log back out")
    void withdrawingAWholeDayRemovesTheLog() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                .content(body(unpaidCategoryId(), 0, FULL_SHIFT))).andExpect(status().isOk());
        Long id = absenceRepository.findActiveForShift(shift.getId()).get(0).getId();

        mvc.perform(delete("/api/absences/" + id)).andExpect(status().isNoContent());

        assertThat(absenceLogWriter.findLog(shift, AbsenceCategoryCodes.UNPAID_ABSENCE)).isEmpty();
    }

    // ── The month ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("the karton's own view returns the month's absences beside the bank")
    void theMonthIsReadable() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                .content(body(unpaidCategoryId(), 360, 480))).andExpect(status().isOk());

        mvc.perform(get("/api/absences/employee/" + shift.getEmployee().getId())
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absences.length()").value(1))
                .andExpect(jsonPath("$.absences[0].workDate").value(WORK_DATE.toString()))
                .andExpect(jsonPath("$.bank.remainingMinutes").value(0));
    }

    @Test
    @WithMockUser(roles = "commercial")
    @DisplayName("and it sits behind the same door as everything else here")
    void theMonthIsBehindTheSameDoor() throws Exception {
        mvc.perform(get("/api/absences/employee/" + shift.getEmployee().getId())
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden());
    }

    // ── Withdrawing one ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "supervisor")
    @DisplayName("withdrawing answers 204 and takes it out of the shift")
    void withdrawsIt() throws Exception {
        mvc.perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON)
                .content(body(unpaidCategoryId(), 360, 480))).andExpect(status().isOk());
        Long id = absenceRepository.findActiveForShift(shift.getId()).get(0).getId();

        mvc.perform(delete("/api/absences/" + id)).andExpect(status().isNoContent());

        assertThat(absenceRepository.findActiveForShift(shift.getId())).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long unpaidCategoryId() {
        return categoryId(AbsenceCategoryCodes.UNPAID_ABSENCE);
    }

    private Long categoryId(String categoryNo) {
        return categoryRepository.findInForceByCategoryNo(categoryNo, WORK_DATE)
                .orElseThrow(() -> new IllegalStateException("Category " + categoryNo + " is not seeded"))
                .getId();
    }

    /** Minutes measured from the shift's own start, so the times are always inside it. */
    private String body(Long categoryId, int fromMinute, int toMinute) {
        OffsetDateTime start = shift.getStartAt().plusMinutes(fromMinute);
        OffsetDateTime end = shift.getStartAt().plusMinutes(toMinute);
        return """
                {"workShiftId":%d,"workCodeCategoryId":%d,"startAt":"%s","endAt":"%s"}
                """.formatted(shift.getId(), categoryId, start, end);
    }
}
