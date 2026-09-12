package com.aleksandarparipovic.marel_app.work_code;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.i18n.AppLocales;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.work_code.dto.ReorderWorkCodeCategoriesRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.UpsertWorkCodeCategoryRequest;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDetailDto;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategoryAdminDto;
import com.aleksandarparipovic.marel_app.work_code.dto.WorkCodeCategorySchemeRuleFormDto;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryTranslationRepository;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.WorkCodeCategoryMapping;
import com.aleksandarparipovic.marel_app.work_code_category_mappings.repository.WorkCodeCategoryMappingRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The šifarnik's write path for work-code categories: create, edit, reorder.
 *
 * <p><strong>Editing is versioning.</strong> A change to a calculation value
 * never overwrites the row — the current version is CLOSED
 * ({@code valid_until = validFrom - 1}) and a new row opens at the form's
 * "važi od". Old work logs keep pointing at the old row, so a recalculated old
 * month reads the values it was worked under; the date-aware picker
 * ({@code listAllowedCategories}) already offers the version in force on the
 * work date. Submitting the SAME "važi od" as the current version corrects that
 * version in place instead — a fix, not a change of policy.
 *
 * <p><strong>The bonus pairs are maintained, never edited.</strong> The
 * "vikend" checkbox stands for a derived category (code + "B",
 * name + " bonus") plus a {@code WEEKEND_BONUS} mapping; the "treća smena"
 * checkbox for code + "3", name + " noćna smena" and
 * {@code NIGHT_SHIFT_BONUS}. A pair mirrors its base — same values, same
 * scheme rules — and follows every re-version; switching a checkbox off closes
 * the pair and its mapping rather than deleting either.
 *
 * <p><strong>Scheme rules follow the doc's own discipline</strong>
 * (compensation-schemes-and-category-localization.md): an in-force rule is
 * never edited — it is closed and a new one inserted; an open scheme
 * (STANDARD) gets a row only when something deviates from its defaults; a
 * closed scheme always gets its decision written, so the data can tell a
 * decision from an oversight; and a pair gets the same rules as its base,
 * because the mapping's TARGET needs an answer under a closed scheme.
 */
@Service
@RequiredArgsConstructor
public class WorkCodeCategoryAdminService {

    public static final String MAPPING_WEEKEND = "WEEKEND_BONUS";
    public static final String MAPPING_NIGHT = "NIGHT_SHIFT_BONUS";
    private static final Set<String> PAIR_MAPPING_TYPES = Set.of(MAPPING_WEEKEND, MAPPING_NIGHT);

    private static final String WEEKEND_CODE_SUFFIX = "B";
    private static final String WEEKEND_NAME_SUFFIX = " bonus";
    private static final String NIGHT_CODE_SUFFIX = "3";
    private static final String NIGHT_NAME_SUFFIX = " noćna smena";

    private static final Set<String> TYPES = Set.of("WORK", "ABSENCE", "SICK_LEAVE");

    private final WorkCodeCategoryRepository categoryRepository;
    private final WorkCodeCategoryMappingRepository mappingRepository;
    private final WorkCodeCategorySchemeRuleRepository ruleRepository;
    private final CompensationSchemeRepository schemeRepository;
    private final WorkCodeCategoryTranslationRepository translationRepository;

    // ── reads ───────────────────────────────────────────────────────────────

    /**
     * Every category version still worth showing: the open version of each
     * chain, plus a closed version whose window has not yet run out (a
     * re-version dated in the future leaves both on screen, honestly).
     */
    @Transactional(readOnly = true)
    public List<WorkCodeCategoryAdminDto> listAll() {
        LocalDate today = LocalDate.now();
        PairIndex pairs = loadPairIndex();
        Map<Long, String> englishNames = englishNames();

        return categoryRepository.findByArchivedAtIsNullOrderByDisplayOrderAscIdAsc().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .filter(c -> c.getValidUntil() == null || !c.getValidUntil().isBefore(today))
                .map(c -> toAdminDto(c, pairs, englishNames, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkCodeCategoryAdminDetailDto detail(Long id) {
        WorkCodeCategory category = requireCategory(id);
        PairIndex pairs = loadPairIndex();
        LocalDate today = LocalDate.now();
        WorkCodeCategoryAdminDto dto = toAdminDto(category, pairs, englishNames(), today);

        // Rules are read at the date the version governs: today for a version
        // in force, its own start for one dated in the future.
        LocalDate refDate = category.getValidFrom() != null && category.getValidFrom().isAfter(today)
                ? category.getValidFrom()
                : today;
        Map<Long, WorkCodeCategorySchemeRule> rulesByScheme = new HashMap<>();
        for (WorkCodeCategorySchemeRule rule : ruleRepository.findActiveForSourceCategoryAt(id, refDate)) {
            rulesByScheme.put(rule.getCompensationScheme().getId(), rule);
        }

        List<WorkCodeCategorySchemeRuleFormDto> schemeRules = new ArrayList<>();
        for (CompensationScheme scheme : schemeRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc()) {
            WorkCodeCategorySchemeRule rule = rulesByScheme.get(scheme.getId());
            if (rule == null) {
                schemeRules.add(new WorkCodeCategorySchemeRuleFormDto(
                        scheme.getId(), scheme.getCode(), scheme.getName(),
                        scheme.getAllowUnmappedCategories(),
                        false, null, null, null,
                        Boolean.TRUE.equals(scheme.getAllowUnmappedCategories()),
                        true, null, null));
            } else {
                WorkCodeCategory effective = rule.getEffectiveCategory();
                schemeRules.add(new WorkCodeCategorySchemeRuleFormDto(
                        scheme.getId(), scheme.getCode(), scheme.getName(),
                        scheme.getAllowUnmappedCategories(),
                        true,
                        effective == null ? null : effective.getId(),
                        effective == null ? null : effective.getCategoryNo(),
                        rule.getCoefficientOverride(),
                        rule.getIsAllowed(),
                        rule.getIsSelectable(),
                        rule.getNote(),
                        rule.getValidFrom()));
            }
        }
        return new WorkCodeCategoryAdminDetailDto(dto, schemeRules);
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Transactional
    public WorkCodeCategoryAdminDetailDto create(UpsertWorkCodeCategoryRequest request) {
        Normalized form = normalize(request);
        requireCodeFree(form.categoryNo());
        if (form.weekendPair()) {
            requireCodeFree(form.categoryNo() + WEEKEND_CODE_SUFFIX);
        }
        if (form.nightPair()) {
            requireCodeFree(form.categoryNo() + NIGHT_CODE_SUFFIX);
        }

        int displayOrder = nextDisplayOrder();
        WorkCodeCategory base = categoryRepository.saveAndFlush(
                buildCategory(form, form.categoryNo(), form.categoryName(), form.validFrom(), displayOrder));

        Map<Long, CompensationScheme> schemes = activeSchemesById();
        List<RuleDecision> decisions = decideRules(form, base.getId(), schemes);
        insertRules(base, decisions, form.validFrom());

        if (form.weekendPair()) {
            createPair(base, WEEKEND_CODE_SUFFIX, WEEKEND_NAME_SUFFIX, MAPPING_WEEKEND, form.validFrom(), decisions);
        }
        if (form.nightPair()) {
            createPair(base, NIGHT_CODE_SUFFIX, NIGHT_NAME_SUFFIX, MAPPING_NIGHT, form.validFrom(), decisions);
        }
        return detail(base.getId());
    }

    // ── edit ────────────────────────────────────────────────────────────────

    @Transactional
    public WorkCodeCategoryAdminDetailDto update(Long id, UpsertWorkCodeCategoryRequest request) {
        WorkCodeCategory current = requireCategory(id);
        if (current.getValidUntil() != null) {
            throw new IllegalArgumentException(
                    "Ova verzija kategorije je zatvorena. Izmene se rade na važećoj verziji koda \""
                            + current.getCategoryNo() + "\".");
        }
        PairIndex pairs = loadPairIndex();
        if (pairs.derivedByTargetId.containsKey(id)) {
            WorkCodeCategoryMapping mapping = pairs.derivedByTargetId.get(id);
            throw new IllegalArgumentException(
                    "Kategorija \"" + current.getCategoryNo()
                            + "\" je izvedena bonus kategorija i uređuje se preko osnovne kategorije \""
                            + mapping.getSourceCategory().getCategoryNo() + "\".");
        }

        Normalized form = normalize(request);
        LocalDate effective = form.validFrom();
        LocalDate currentFrom = current.getValidFrom();

        WorkCodeCategoryMapping weekendMapping = pairs.openMapping(id, MAPPING_WEEKEND);
        WorkCodeCategoryMapping nightMapping = pairs.openMapping(id, MAPPING_NIGHT);

        Map<Long, CompensationScheme> schemes = activeSchemesById();
        List<RuleDecision> decisions = decideRules(form, id, schemes);

        boolean valuesChanged = versionedValuesDiffer(current, form);

        // Only a change to the CATEGORY's own calculation values re-versions
        // the category row. The pairs and the scheme rules carry their own
        // validity windows, so a toggle or a rule change is closed and opened
        // on its own table without minting an identical category version.
        boolean sameDay = Objects.equals(effective, currentFrom);
        if (valuesChanged && !sameDay) {
            if (currentFrom != null && effective.isBefore(currentFrom)) {
                throw new IllegalArgumentException(
                        "Datum \"važi od\" (" + effective + ") ne može biti pre početka važeće verzije ("
                                + currentFrom + "). Za ispravku tekuće verzije unesite isti datum.");
            }
            return newVersion(current, form, effective, weekendMapping, nightMapping, decisions);
        }
        return correctInPlace(current, form, weekendMapping, nightMapping, decisions);
    }

    /**
     * No calculation value moved (or the same "važi od" was submitted — a
     * correction of the version, not a change of policy): the row is fixed
     * where it stands, while pairs and rules still close/open by the given
     * date on their own tables.
     */
    private WorkCodeCategoryAdminDetailDto correctInPlace(WorkCodeCategory current,
                                                          Normalized form,
                                                          WorkCodeCategoryMapping weekendMapping,
                                                          WorkCodeCategoryMapping nightMapping,
                                                          List<RuleDecision> decisions) {
        LocalDate effective = form.validFrom();

        renameChainIfNeeded(current, form, weekendMapping, nightMapping);
        applyValues(current, form);
        current.setCategoryName(form.categoryName());
        current.setNote(form.note());
        // Cosmetic, like name and note — set in place, never a re-version.
        current.setColor(form.color());
        current.setPattern(form.pattern());
        categoryRepository.saveAndFlush(current);

        reconcileRules(current, decisions, effective);

        syncPair(current, weekendMapping, form.weekendPair(),
                WEEKEND_CODE_SUFFIX, WEEKEND_NAME_SUFFIX, MAPPING_WEEKEND, effective, decisions);
        syncPair(current, nightMapping, form.nightPair(),
                NIGHT_CODE_SUFFIX, NIGHT_NAME_SUFFIX, MAPPING_NIGHT, effective, decisions);

        return detail(current.getId());
    }

    /**
     * A later "važi od": close the current version and open a new one, carrying
     * the pairs, the mappings, the scheme rules and the translations across to
     * the new row — and re-pointing other categories' in-force rules whose
     * TARGET was the old row, so new work keeps landing on the version in
     * force.
     */
    private WorkCodeCategoryAdminDetailDto newVersion(WorkCodeCategory current,
                                                      Normalized form,
                                                      LocalDate effective,
                                                      WorkCodeCategoryMapping weekendMapping,
                                                      WorkCodeCategoryMapping nightMapping,
                                                      List<RuleDecision> decisions) {
        renameChainIfNeeded(current, form, weekendMapping, nightMapping);

        // Flush the close BEFORE the insert: within one flush Hibernate runs
        // inserts first, and ex_work_code_categories_no_overlap would refuse
        // the new open window while the old one is still open.
        current.setValidUntil(effective.minusDays(1));
        categoryRepository.saveAndFlush(current);

        WorkCodeCategory next = buildCategory(form, current.getCategoryNo(), form.categoryName(), effective,
                current.getDisplayOrder());
        carryUnformedFields(current, next);
        next = categoryRepository.saveAndFlush(next);
        copyTranslations(current, next);

        closeRulesForSource(current.getId(), effective);
        insertRules(next, decisions, effective);
        repointRulesTargeting(current, next, effective);

        reversionPair(current, next, weekendMapping, form.weekendPair(),
                WEEKEND_CODE_SUFFIX, WEEKEND_NAME_SUFFIX, MAPPING_WEEKEND, effective, decisions);
        reversionPair(current, next, nightMapping, form.nightPair(),
                NIGHT_CODE_SUFFIX, NIGHT_NAME_SUFFIX, MAPPING_NIGHT, effective, decisions);

        return detail(next.getId());
    }

    // ── reorder ─────────────────────────────────────────────────────────────

    /**
     * Drag order → {@code display_order} 5, 10, 15… — written onto every
     * version of each dragged code, so the chain cannot disagree with itself.
     */
    @Transactional
    public List<WorkCodeCategoryAdminDto> reorder(ReorderWorkCodeCategoriesRequest request) {
        if (request == null || request.orderedIds() == null || request.orderedIds().isEmpty()) {
            throw new IllegalArgumentException("Redosled je prazan.");
        }
        int order = 5;
        // During a transition window BOTH versions of a code are on screen; the
        // chain is still ONE draggable thing, so the first occurrence of a code
        // decides and later versions of it are skipped.
        java.util.Set<String> seenCodes = new java.util.HashSet<>();
        for (Long id : request.orderedIds()) {
            WorkCodeCategory row = requireCategory(id);
            if (!seenCodes.add(row.getCategoryNo().toLowerCase())) {
                continue;
            }
            for (WorkCodeCategory version :
                    categoryRepository.findByCategoryNoIgnoreCaseAndArchivedAtIsNull(row.getCategoryNo())) {
                version.setDisplayOrder(order);
                categoryRepository.save(version);
            }
            order += 5;
        }
        categoryRepository.flush();
        return listAll();
    }

    // ── pairs ───────────────────────────────────────────────────────────────

    private WorkCodeCategoryMapping createPair(WorkCodeCategory base,
                                               String codeSuffix,
                                               String nameSuffix,
                                               String mappingType,
                                               LocalDate validFrom,
                                               List<RuleDecision> decisions) {
        String pairCode = base.getCategoryNo() + codeSuffix;
        requireCodeFree(pairCode);

        Normalized mirror = Normalized.mirroring(base, validFrom);
        WorkCodeCategory pair = buildCategory(mirror, pairCode, base.getCategoryName() + nameSuffix,
                validFrom, base.getDisplayOrder());
        carryUnformedFields(base, pair);
        pair = categoryRepository.saveAndFlush(pair);
        insertRules(pair, decisions, validFrom);

        return mappingRepository.saveAndFlush(WorkCodeCategoryMapping.builder()
                .sourceCategory(base)
                .targetCategory(pair)
                .mappingType(mappingType)
                .isActive(true)
                .validFrom(validFrom)
                .build());
    }

    /** In-place path: toggle a pair on or off, or keep an existing one mirrored. */
    private WorkCodeCategoryMapping syncPair(WorkCodeCategory base,
                                             WorkCodeCategoryMapping mapping,
                                             boolean wanted,
                                             String codeSuffix,
                                             String nameSuffix,
                                             String mappingType,
                                             LocalDate effective,
                                             List<RuleDecision> decisions) {
        if (wanted && mapping == null) {
            return createPair(base, codeSuffix, nameSuffix, mappingType, effective, decisions);
        }
        if (!wanted && mapping != null) {
            closePair(mapping, effective);
            return null;
        }
        if (wanted) {
            WorkCodeCategory pair = mapping.getTargetCategory();
            applyValues(pair, Normalized.mirroring(base, pair.getValidFrom()));
            pair.setCategoryName(base.getCategoryName() + nameSuffix);
            pair.setNote(base.getNote());
            // Cosmetic, mirrored like name/note so a pair reads as its base.
            pair.setColor(base.getColor());
            pair.setPattern(normalizePattern(base.getPattern()));
            categoryRepository.saveAndFlush(pair);
            reconcileRules(pair, decisions, effective);
        }
        return mapping;
    }

    /** New-version path: carry a pair (or its absence) over to the new base row. */
    private void reversionPair(WorkCodeCategory oldBase,
                               WorkCodeCategory newBase,
                               WorkCodeCategoryMapping mapping,
                               boolean wanted,
                               String codeSuffix,
                               String nameSuffix,
                               String mappingType,
                               LocalDate effective,
                               List<RuleDecision> decisions) {
        if (mapping == null) {
            if (wanted) {
                createPair(newBase, codeSuffix, nameSuffix, mappingType, effective, decisions);
            }
            return;
        }
        if (!wanted) {
            closePair(mapping, effective);
            return;
        }

        WorkCodeCategory oldPair = mapping.getTargetCategory();
        closeVersion(oldPair, effective);
        closeMapping(mapping, effective);

        Normalized mirror = Normalized.mirroring(newBase, effective);
        WorkCodeCategory newPair = buildCategory(mirror, oldPair.getCategoryNo(),
                newBase.getCategoryName() + nameSuffix, effective, newBase.getDisplayOrder());
        carryUnformedFields(oldPair, newPair);
        newPair = categoryRepository.saveAndFlush(newPair);
        copyTranslations(oldPair, newPair);
        insertRules(newPair, decisions, effective);
        repointRulesTargeting(oldPair, newPair, effective);

        mappingRepository.saveAndFlush(WorkCodeCategoryMapping.builder()
                .sourceCategory(newBase)
                .targetCategory(newPair)
                .mappingType(mappingType)
                .isActive(true)
                .validFrom(effective)
                .build());
    }

    /** Switching a pair off: close (never delete) the pair and its mapping. */
    private void closePair(WorkCodeCategoryMapping mapping, LocalDate effective) {
        closeVersion(mapping.getTargetCategory(), effective);
        closeMapping(mapping, effective);
    }

    private void closeVersion(WorkCodeCategory version, LocalDate effective) {
        if (version.getValidFrom() != null && !version.getValidFrom().isBefore(effective)) {
            // Opened on (or after) the very day it is being removed — there is
            // no day it governed, so deactivate rather than write an
            // impossible window.
            version.setIsActive(false);
        } else {
            version.setValidUntil(effective.minusDays(1));
        }
        categoryRepository.saveAndFlush(version);
        closeRulesForSource(version.getId(), effective);
    }

    private void closeMapping(WorkCodeCategoryMapping mapping, LocalDate effective) {
        if (!mapping.getValidFrom().isBefore(effective)) {
            mapping.setIsActive(false);
        } else {
            mapping.setValidUntil(effective.minusDays(1));
        }
        mappingRepository.saveAndFlush(mapping);
    }

    // ── scheme rules ────────────────────────────────────────────────────────

    /**
     * What each scheme should hold for this category, normalised from the form.
     *
     * @param writeRow whether a rule row exists at all — an open scheme's
     *                 defaults write none.
     */
    private record RuleDecision(CompensationScheme scheme,
                                boolean writeRow,
                                Long effectiveCategoryId,
                                BigDecimal coefficientOverride,
                                boolean isAllowed,
                                boolean isSelectable,
                                String note) {
    }

    private List<RuleDecision> decideRules(Normalized form,
                                           Long selfId,
                                           Map<Long, CompensationScheme> schemes) {
        Map<Long, UpsertWorkCodeCategoryRequest.SchemeRuleInput> bySchemeId = new LinkedHashMap<>();
        for (UpsertWorkCodeCategoryRequest.SchemeRuleInput input : form.schemeRules()) {
            if (input.schemeId() == null || !schemes.containsKey(input.schemeId())) {
                throw new IllegalArgumentException("Nepoznat tip obračuna: " + input.schemeId());
            }
            bySchemeId.put(input.schemeId(), input);
        }

        List<RuleDecision> decisions = new ArrayList<>();
        for (CompensationScheme scheme : schemes.values()) {
            boolean open = Boolean.TRUE.equals(scheme.getAllowUnmappedCategories());
            UpsertWorkCodeCategoryRequest.SchemeRuleInput input = bySchemeId.get(scheme.getId());

            boolean allowed = input != null && input.isAllowed() != null ? input.isAllowed() : open;
            boolean selectable = input == null || input.isSelectable() == null || input.isSelectable();
            Long effectiveId = input == null ? null : input.effectiveCategoryId();
            if (effectiveId != null && effectiveId.equals(selfId)) {
                effectiveId = null;
            }
            BigDecimal coefficient = input == null ? null : input.coefficientOverride();
            String note = input == null ? null : blankToNull(input.note());

            if (!allowed) {
                // A denied category has no coefficient and no remap to speak of.
                effectiveId = null;
                coefficient = null;
            }
            if (coefficient != null && coefficient.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Koeficijent za tip obračuna \"" + scheme.getName() + "\" mora biti veći od nule.");
            }
            if (effectiveId != null) {
                WorkCodeCategory target = requireCategory(effectiveId);
                if (target.getValidUntil() != null) {
                    throw new IllegalArgumentException(
                            "Kategorija u koju se preslikava (\"" + target.getCategoryNo()
                                    + "\") nije važeća verzija.");
                }
            }

            // A closed scheme always writes its decision — a missing row there
            // cannot be told apart from an oversight. An open scheme writes a
            // row only when something deviates from what "no rule" already
            // means.
            boolean deviates = !allowed || !selectable || effectiveId != null
                    || coefficient != null || note != null;
            boolean writeRow = !open || deviates;

            decisions.add(new RuleDecision(scheme, writeRow, effectiveId, coefficient,
                    allowed, selectable, note));
        }
        return decisions;
    }

    /**
     * A pair shares its base's decisions verbatim: the base's "no remap"
     * mirrors as the pair's "no remap", and a real remap target is shared as
     * is — J → S mirrors as JB → S, exactly as the seeded rules do.
     */
    private void insertRules(WorkCodeCategory source, List<RuleDecision> decisions, LocalDate validFrom) {
        for (RuleDecision decision : decisions) {
            if (!decision.writeRow()) {
                continue;
            }
            insertRule(source, decision, validFrom);
        }
    }

    private boolean matches(WorkCodeCategorySchemeRule rule, RuleDecision decision) {
        Long ruleEffective = rule.getEffectiveCategory() == null ? null : rule.getEffectiveCategory().getId();
        return Objects.equals(ruleEffective, decision.effectiveCategoryId())
                && Objects.equals(Boolean.TRUE.equals(rule.getIsAllowed()), decision.isAllowed())
                && Objects.equals(Boolean.TRUE.equals(rule.getIsSelectable()), decision.isSelectable())
                && bigDecimalEquals(rule.getCoefficientOverride(), decision.coefficientOverride())
                && Objects.equals(blankToNull(rule.getNote()), decision.note());
    }

    /**
     * In-place reconciliation for one source: an in-force rule that no longer
     * matches is closed and re-inserted (never edited), unless it started on
     * the very date being corrected — then it is corrected where it stands.
     */
    private void reconcileRules(WorkCodeCategory source, List<RuleDecision> decisions, LocalDate effective) {
        Map<Long, WorkCodeCategorySchemeRule> current = new HashMap<>();
        for (WorkCodeCategorySchemeRule rule :
                ruleRepository.findActiveForSourceCategoryAt(source.getId(), effective)) {
            current.put(rule.getCompensationScheme().getId(), rule);
        }
        for (RuleDecision decision : decisions) {
            WorkCodeCategorySchemeRule rule = current.get(decision.scheme().getId());
            if (rule == null && decision.writeRow()) {
                insertRule(source, decision, effective);
            } else if (rule != null && !decision.writeRow()) {
                closeRule(rule, effective);
            } else if (rule != null && !matches(rule, decision)) {
                if (effective.equals(rule.getValidFrom())) {
                    rule.setEffectiveCategory(decision.effectiveCategoryId() == null
                            ? null
                            : categoryRepository.getReferenceById(decision.effectiveCategoryId()));
                    rule.setIsAllowed(decision.isAllowed());
                    rule.setIsSelectable(decision.isSelectable());
                    rule.setCoefficientOverride(decision.coefficientOverride());
                    rule.setNote(decision.note());
                    ruleRepository.saveAndFlush(rule);
                } else {
                    closeRule(rule, effective);
                    insertRule(source, decision, effective);
                }
            }
        }
    }

    private void insertRule(WorkCodeCategory source, RuleDecision decision, LocalDate validFrom) {
        ruleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(decision.scheme())
                .sourceCategory(source)
                .effectiveCategory(decision.effectiveCategoryId() == null
                        ? null
                        : categoryRepository.getReferenceById(decision.effectiveCategoryId()))
                .isAllowed(decision.isAllowed())
                .isSelectable(decision.isSelectable())
                .coefficientOverride(decision.coefficientOverride())
                .validFrom(validFrom)
                .note(decision.note())
                .isActive(true)
                .build());
    }

    private void closeRule(WorkCodeCategorySchemeRule rule, LocalDate effective) {
        if (!rule.getValidFrom().isBefore(effective)) {
            rule.setIsActive(false);
        } else {
            rule.setValidUntil(effective.minusDays(1));
        }
        ruleRepository.saveAndFlush(rule);
    }

    private void closeRulesForSource(Long sourceId, LocalDate effective) {
        for (WorkCodeCategorySchemeRule rule :
                ruleRepository.findActiveForSourceCategoryAt(sourceId, effective)) {
            closeRule(rule, effective);
        }
    }

    /**
     * Other categories' in-force rules that LAND on the old version are closed
     * and re-inserted pointing at the new one — J → S must keep meaning "the S
     * in force", or new work would accumulate against a version that no longer
     * governs.
     */
    private void repointRulesTargeting(WorkCodeCategory oldVersion,
                                       WorkCodeCategory newVersion,
                                       LocalDate effective) {
        List<WorkCodeCategorySchemeRule> targeting = ruleRepository.findAll().stream()
                .filter(r -> r.getEffectiveCategory() != null
                        && Objects.equals(r.getEffectiveCategory().getId(), oldVersion.getId()))
                .filter(r -> r.isUsableAt(effective))
                .toList();
        for (WorkCodeCategorySchemeRule rule : targeting) {
            closeRule(rule, effective);
            ruleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                    .compensationScheme(rule.getCompensationScheme())
                    .sourceCategory(rule.getSourceCategory())
                    .effectiveCategory(newVersion)
                    .isAllowed(rule.getIsAllowed())
                    .isSelectable(rule.getIsSelectable())
                    .coefficientOverride(rule.getCoefficientOverride())
                    .validFrom(effective)
                    .validUntil(rule.getValidUntil())
                    .note(rule.getNote())
                    .isActive(true)
                    .build());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The request with blanks trimmed and every nullable boolean resolved. */
    private record Normalized(String categoryNo,
                              String categoryName,
                              String type,
                              Double normMultiplier,
                              LocalDate validFrom,
                              boolean isPaid,
                              boolean affectsNorm,
                              String note,
                              boolean fixedHourlyRate,
                              BigDecimal hourlyRate,
                              boolean affectsMealAllowance,
                              boolean basicWorkOperation,
                              boolean affectsWeekendBonus,
                              boolean affectsMonthlyBonus,
                              boolean isFullDay,
                              boolean weekendPair,
                              boolean nightPair,
                              String color,
                              String pattern,
                              List<UpsertWorkCodeCategoryRequest.SchemeRuleInput> schemeRules) {

        /** A pair's values: its base's, dated its own way. Code and name differ. */
        static Normalized mirroring(WorkCodeCategory base, LocalDate validFrom) {
            return new Normalized(
                    base.getCategoryNo(), base.getCategoryName(), base.getType(),
                    base.getNormMultiplier(), validFrom,
                    Boolean.TRUE.equals(base.getIsPaid()),
                    Boolean.TRUE.equals(base.getAffectsNorm()),
                    base.getNote(),
                    Boolean.TRUE.equals(base.getFixedHourlyRate()),
                    base.getHourlyRate(),
                    Boolean.TRUE.equals(base.getAffectsMealAllowance()),
                    Boolean.TRUE.equals(base.getBasicWorkOperation()),
                    Boolean.TRUE.equals(base.getAffectsWeekendBonus()),
                    Boolean.TRUE.equals(base.getAffectsMonthlyBonus()),
                    Boolean.TRUE.equals(base.getIsFullDay()),
                    false, false,
                    // A derived pair (B/3) inherits the base's appearance, so a
                    // category and its bonus pair read as one colour.
                    base.getColor(), normalizePattern(base.getPattern()),
                    List.of());
        }
    }

    private Normalized normalize(UpsertWorkCodeCategoryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Zahtev je prazan.");
        }
        String code = blankToNull(request.categoryNo());
        String name = blankToNull(request.categoryName());
        if (code == null) {
            throw new IllegalArgumentException("Broj kategorije je obavezan.");
        }
        if (name == null) {
            throw new IllegalArgumentException("Naziv kategorije je obavezan.");
        }
        if (request.type() == null || !TYPES.contains(request.type())) {
            throw new IllegalArgumentException(
                    "Tip kategorije mora biti jedan od: na poslu (WORK), van prostorija firme (ABSENCE), bolovanje (SICK_LEAVE).");
        }
        if (request.normMultiplier() == null || request.normMultiplier() < 0) {
            throw new IllegalArgumentException("Koeficijent norme je obavezan i ne može biti negativan.");
        }
        if (request.validFrom() == null) {
            throw new IllegalArgumentException("Datum \"važi od\" je obavezan.");
        }
        boolean fixedRate = Boolean.TRUE.equals(request.fixedHourlyRate());
        if (fixedRate && (request.hourlyRate() == null || request.hourlyRate().signum() <= 0)) {
            throw new IllegalArgumentException("Za fiksnu satnicu mora da se unese satnica veća od nule.");
        }
        boolean isWork = "WORK".equals(request.type());
        boolean basicWorkOperation = Boolean.TRUE.equals(request.basicWorkOperation());
        if (basicWorkOperation && !isWork) {
            throw new IllegalArgumentException("Osnovna kategorija rada mora biti tipa \"na poslu\".");
        }
        boolean weekendPair = Boolean.TRUE.equals(request.weekendBonusPair());
        boolean nightPair = Boolean.TRUE.equals(request.nightShiftBonusPair());
        if ((weekendPair || nightPair) && !isWork) {
            throw new IllegalArgumentException(
                    "Vikend i noćni bonus par imaju smisla samo za kategorije tipa \"na poslu\".");
        }
        return new Normalized(
                code, name, request.type(), request.normMultiplier(), request.validFrom(),
                !Boolean.FALSE.equals(request.isPaid()),
                !Boolean.FALSE.equals(request.affectsNorm()),
                blankToNull(request.note()),
                fixedRate,
                fixedRate ? request.hourlyRate() : null,
                Boolean.TRUE.equals(request.affectsMealAllowance()),
                basicWorkOperation,
                !Boolean.FALSE.equals(request.affectsWeekendBonus()),
                !Boolean.FALSE.equals(request.affectsMonthlyBonus()),
                Boolean.TRUE.equals(request.isFullDay()),
                weekendPair, nightPair,
                blankToNull(request.color()),
                normalizePattern(request.pattern()),
                request.schemeRules() == null ? List.of() : request.schemeRules());
    }

    /** NONE unless the form sends a value we recognise; matches the DB CHECK. */
    private static String normalizePattern(String value) {
        if (value == null) {
            return "NONE";
        }
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "CHECKER", "STRIPES" -> upper;
            default -> "NONE";
        };
    }

    /**
     * Every NOT NULL column set explicitly — the builder ignores plain field
     * initialisers, which is exactly the NULL-into-NOT-NULL trap the entity's
     * own comments warn about.
     */
    private WorkCodeCategory buildCategory(Normalized form,
                                           String code,
                                           String name,
                                           LocalDate validFrom,
                                           int displayOrder) {
        WorkCodeCategory category = WorkCodeCategory.builder()
                .categoryNo(code)
                .categoryName(name)
                .type(form.type())
                .isPaid(form.isPaid())
                .normMultiplier(form.normMultiplier())
                .isActive(true)
                .validFrom(validFrom)
                .note(form.note())
                .hourlyRate(form.hourlyRate())
                .fixedHourlyRate(form.fixedHourlyRate())
                .affectsMealAllowance(form.affectsMealAllowance())
                .affectsNorm(form.affectsNorm())
                // The owner's reading of the historically dead column: it means
                // the monthly-bonus flag.
                .affectsBonus(form.affectsMonthlyBonus())
                .affectsWeekendBonus(form.affectsWeekendBonus())
                .affectsMonthlyBonus(form.affectsMonthlyBonus())
                .displayOrder(displayOrder)
                .baseCategory(false)
                .basicWorkOperation(form.basicWorkOperation())
                .allowsParallelWork(false)
                .isFullDay(form.isFullDay())
                // Cosmetic — carried onto every new version so the whole chain
                // of a code reads as one colour.
                .color(form.color())
                .pattern(form.pattern())
                .build();
        return category;
    }

    /**
     * The columns the form does not carry survive a re-version by copying:
     * losing {@code sick_leave_kind} would silently drop a category out of the
     * thirty-day rule.
     */
    private void carryUnformedFields(WorkCodeCategory from, WorkCodeCategory to) {
        to.setSickLeaveKind(from.getSickLeaveKind());
        to.setAllowsParallelWork(Boolean.TRUE.equals(from.getAllowsParallelWork()));
        to.setBaseCategory(Boolean.TRUE.equals(from.getBaseCategory()));
    }

    /** The versioned values only — code, name and note are cosmetic. */
    private void applyValues(WorkCodeCategory target, Normalized form) {
        target.setType(form.type());
        target.setNormMultiplier(form.normMultiplier());
        target.setIsPaid(form.isPaid());
        target.setAffectsNorm(form.affectsNorm());
        target.setFixedHourlyRate(form.fixedHourlyRate());
        target.setHourlyRate(form.hourlyRate());
        target.setAffectsMealAllowance(form.affectsMealAllowance());
        target.setBasicWorkOperation(form.basicWorkOperation());
        target.setAffectsWeekendBonus(form.affectsWeekendBonus());
        target.setAffectsMonthlyBonus(form.affectsMonthlyBonus());
        target.setAffectsBonus(form.affectsMonthlyBonus());
        target.setIsFullDay(form.isFullDay());
    }

    private boolean versionedValuesDiffer(WorkCodeCategory current, Normalized form) {
        return !Objects.equals(current.getType(), form.type())
                || !Objects.equals(current.getNormMultiplier(), form.normMultiplier())
                || Boolean.TRUE.equals(current.getIsPaid()) != form.isPaid()
                || Boolean.TRUE.equals(current.getAffectsNorm()) != form.affectsNorm()
                || Boolean.TRUE.equals(current.getFixedHourlyRate()) != form.fixedHourlyRate()
                || !bigDecimalEquals(current.getHourlyRate(), form.hourlyRate())
                || Boolean.TRUE.equals(current.getAffectsMealAllowance()) != form.affectsMealAllowance()
                || Boolean.TRUE.equals(current.getBasicWorkOperation()) != form.basicWorkOperation()
                || Boolean.TRUE.equals(current.getAffectsWeekendBonus()) != form.affectsWeekendBonus()
                || Boolean.TRUE.equals(current.getAffectsMonthlyBonus()) != form.affectsMonthlyBonus()
                || Boolean.TRUE.equals(current.getIsFullDay()) != form.isFullDay();
    }

    /**
     * A code rename is identity maintenance, not history: every version of the
     * chain gets the new code (the chain is FOUND by its code), and a linked
     * pair chain follows with its suffix.
     */
    private void renameChainIfNeeded(WorkCodeCategory current,
                                     Normalized form,
                                     WorkCodeCategoryMapping weekendMapping,
                                     WorkCodeCategoryMapping nightMapping) {
        String oldCode = current.getCategoryNo();
        String newCode = form.categoryNo();
        if (oldCode.equalsIgnoreCase(newCode)) {
            if (!oldCode.equals(newCode)) {
                current.setCategoryNo(newCode);
            }
            return;
        }
        requireCodeFree(newCode);
        renameChain(oldCode, newCode);
        if (weekendMapping != null) {
            requireCodeFree(newCode + WEEKEND_CODE_SUFFIX);
            renameChain(oldCode + WEEKEND_CODE_SUFFIX, newCode + WEEKEND_CODE_SUFFIX);
        }
        if (nightMapping != null) {
            requireCodeFree(newCode + NIGHT_CODE_SUFFIX);
            renameChain(oldCode + NIGHT_CODE_SUFFIX, newCode + NIGHT_CODE_SUFFIX);
        }
        categoryRepository.flush();
    }

    private void renameChain(String oldCode, String newCode) {
        for (WorkCodeCategory version : categoryRepository.findByCategoryNoIgnoreCaseAndArchivedAtIsNull(oldCode)) {
            version.setCategoryNo(newCode);
            categoryRepository.save(version);
        }
    }

    private void copyTranslations(WorkCodeCategory from, WorkCodeCategory to) {
        for (WorkCodeCategoryTranslation translation : translationRepository.findByCategory(from.getId())) {
            translationRepository.save(WorkCodeCategoryTranslation.builder()
                    .workCodeCategory(to)
                    .locale(translation.getLocale())
                    .name(translation.getName())
                    .build());
        }
        translationRepository.flush();
    }

    private void requireCodeFree(String code) {
        categoryRepository.findFirstByCategoryNoIgnoreCaseAndValidUntilIsNullAndArchivedAtIsNull(code)
                .filter(existing -> Boolean.TRUE.equals(existing.getIsActive()))
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "Kategorija sa brojem \"" + code + "\" već postoji (\""
                                    + existing.getCategoryName() + "\").");
                });
    }

    private int nextDisplayOrder() {
        return categoryRepository.findByArchivedAtIsNullOrderByDisplayOrderAscIdAsc().stream()
                .map(WorkCodeCategory::getDisplayOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 5;
    }

    private WorkCodeCategory requireCategory(Long id) {
        WorkCodeCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Kategorija rada ne postoji: " + id));
        if (category.getArchivedAt() != null) {
            throw new IllegalArgumentException(
                    "Kategorija rada \"" + category.getCategoryNo() + "\" je arhivirana.");
        }
        return category;
    }

    private Map<Long, CompensationScheme> activeSchemesById() {
        Map<Long, CompensationScheme> byId = new LinkedHashMap<>();
        for (CompensationScheme scheme : schemeRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByCodeAsc()) {
            byId.put(scheme.getId(), scheme);
        }
        return byId;
    }

    private Map<Long, String> englishNames() {
        Map<Long, String> byCategoryId = new HashMap<>();
        translationRepository.findAllByLocale(AppLocales.ENGLISH)
                .forEach(t -> byCategoryId.put(t.getWorkCodeCategory().getId(), t.getName()));
        return byCategoryId;
    }

    /** The open pair mappings, indexed both ways: base → pair and pair → base. */
    private PairIndex loadPairIndex() {
        Map<Long, Map<String, WorkCodeCategoryMapping>> bySourceId = new HashMap<>();
        Map<Long, WorkCodeCategoryMapping> byTargetId = new HashMap<>();
        for (WorkCodeCategoryMapping mapping :
                mappingRepository.findByMappingTypeInAndValidUntilIsNullAndIsActiveTrueAndArchivedAtIsNull(
                        PAIR_MAPPING_TYPES)) {
            bySourceId.computeIfAbsent(mapping.getSourceCategory().getId(), k -> new HashMap<>())
                    .put(mapping.getMappingType(), mapping);
            byTargetId.put(mapping.getTargetCategory().getId(), mapping);
        }
        return new PairIndex(bySourceId, byTargetId);
    }

    private record PairIndex(Map<Long, Map<String, WorkCodeCategoryMapping>> bySourceId,
                             Map<Long, WorkCodeCategoryMapping> derivedByTargetId) {

        WorkCodeCategoryMapping openMapping(Long sourceId, String type) {
            return Optional.ofNullable(bySourceId.get(sourceId)).map(m -> m.get(type)).orElse(null);
        }
    }

    private WorkCodeCategoryAdminDto toAdminDto(WorkCodeCategory c,
                                                PairIndex pairs,
                                                Map<Long, String> englishNames,
                                                LocalDate today) {
        WorkCodeCategoryMapping derivedVia = pairs.derivedByTargetId().get(c.getId());
        boolean current = c.isInForceOn(today);
        boolean editable = c.getValidUntil() == null && derivedVia == null;
        return new WorkCodeCategoryAdminDto(
                c.getId(),
                c.getCategoryNo(),
                c.getCategoryName(),
                englishNames.get(c.getId()),
                c.getType(),
                c.getNormMultiplier(),
                c.getValidFrom(),
                c.getValidUntil(),
                c.getIsPaid(),
                c.getAffectsNorm(),
                c.getNote(),
                c.getHourlyRate(),
                c.getFixedHourlyRate(),
                c.getAffectsMealAllowance(),
                c.getBasicWorkOperation(),
                c.getAffectsWeekendBonus(),
                c.getAffectsMonthlyBonus(),
                c.getIsFullDay(),
                c.getColor(),
                c.getPattern(),
                c.getDisplayOrder(),
                pairs.openMapping(c.getId(), MAPPING_WEEKEND) != null,
                pairs.openMapping(c.getId(), MAPPING_NIGHT) != null,
                derivedVia == null ? null : derivedVia.getSourceCategory().getId(),
                derivedVia == null ? null : derivedVia.getSourceCategory().getCategoryNo(),
                current,
                editable);
    }

    private static boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
