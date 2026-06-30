package com.aleksandarparipovic.marel_app.app_settings;

import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingResponse;
import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingHistoryDto;
import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingResponse;
import com.aleksandarparipovic.marel_app.app_settings.dto.AppSettingUpdateRequest;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    private static final BigDecimal DEFAULT_MAX_EFFICIENCY_PERCENT = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_MEAL_ALLOWANCE_PER_DAY  = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_TRANSPORT_ALLOWANCE_PER_DAY = BigDecimal.ZERO;

    private static final String KEY_MEAL_ALLOWANCE      = "meal_allowance_per_day";
    private static final String KEY_TRANSPORT_ALLOWANCE = "transport_allowance_per_day";

    private final AppSettingRepository appSettingRepository;
    private final EntityManager entityManager;

    public BigDecimal getMaxEfficiencyPercentAt(OffsetDateTime at) {
        return appSettingRepository.findMaxEfficiencyPercentAt(at)
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .orElse(DEFAULT_MAX_EFFICIENCY_PERCENT);
    }

    public BigDecimal getMealAllowancePerDay(OffsetDateTime at) {
        return appSettingRepository.findNumericSettingAt(KEY_MEAL_ALLOWANCE, at)
                .orElse(DEFAULT_MEAL_ALLOWANCE_PER_DAY);
    }

    public BigDecimal getTransportAllowancePerDay(OffsetDateTime at) {
        return appSettingRepository.findNumericSettingAt(KEY_TRANSPORT_ALLOWANCE, at)
                .orElse(DEFAULT_TRANSPORT_ALLOWANCE_PER_DAY);
    }

    public BigDecimal getSettingAt(String key, OffsetDateTime at) {
        return appSettingRepository.findNumericSettingAt(key, at)
                .orElse(null);
    }

    public List<AppSettingResponse> getAllCurrentlyValid() {
        return appSettingRepository.findAllCurrentlyValid(OffsetDateTime.now())
                .stream()
                .map(AppSettingResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppSettingHistoryDto> getAllHistory() {
        List<AppSetting> all = appSettingRepository.findByArchivedAtIsNullOrderBySettingKeyAscValidFromDesc();

        // group by settingKey preserving order (already sorted by key asc)
        Map<String, List<AppSetting>> byKey = all.stream()
                .collect(Collectors.groupingBy(AppSetting::getSettingKey));

        return byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<AppSetting> records = entry.getValue();
                    // take metadata from the most recent record
                    AppSetting latest = records.stream()
                            .max(Comparator.comparing(AppSetting::getValidFrom))
                            .orElseThrow();
                    List<AppSettingResponse> history = records.stream()
                            .sorted(Comparator.comparing(AppSetting::getValidFrom).reversed())
                            .map(AppSettingResponse::new)
                            .toList();
                    return new AppSettingHistoryDto(
                            latest.getSettingKey(),
                            latest.getValueType(),
                            latest.getAffectsPayroll(),
                            latest.getDescription(),
                            latest.getDisplayText(),
                            latest.getUnit(),
                            history
                    );
                })
                .toList();
    }

    @Transactional
    public AppSettingResponse saveSetting(AppSettingUpdateRequest req) {
        OffsetDateTime now     = OffsetDateTime.now();
        OffsetDateTime newFrom = req.getValidFrom();
        OffsetDateTime newTo   = req.getValidUntil();

        List<AppSetting> allActive = appSettingRepository.findAllActiveByKey(req.getSettingKey());

        // Clones to insert AFTER all updates/deletes are flushed (constraint requires no overlap at INSERT time)
        List<AppSetting> toInsert = new java.util.ArrayList<>();

        for (AppSetting existing : allActive) {
            OffsetDateTime oldFrom = existing.getValidFrom();
            OffsetDateTime oldTo   = existing.getValidUntil(); // null = open-ended

            boolean newFromInsideExisting = newFrom.isAfter(oldFrom)
                    && (oldTo == null || newFrom.isBefore(oldTo));

            boolean newToInsideExisting = newTo != null && oldTo != null
                    && newTo.isAfter(oldFrom) && newTo.isBefore(oldTo);

            boolean oldFromInsideNew = oldFrom.isAfter(newFrom)
                    && newTo != null && oldFrom.isBefore(newTo);

            if (newFromInsideExisting && newToInsideExisting) {
                // Case A: new interval is completely inside existing [oldFrom, oldTo]
                // Split into: [oldFrom, newFrom]  [newFrom, newTo]  [newTo, oldTo]
                // Schedule right fragment [newTo, oldTo] for later insert
                toInsert.add(cloneSetting(existing, newTo, oldTo, now));
                // Trim existing to left fragment [oldFrom, newFrom]
                existing.setValidUntil(newFrom);
                existing.setUpdatedAt(now);
                appSettingRepository.save(existing);

            } else if (oldFromInsideNew) {
                // Case B: existing starts inside new interval
                if (oldTo == null || oldTo.isAfter(newTo)) {
                    // Existing extends beyond new interval -> schedule [newTo, oldTo] for later insert
                    toInsert.add(cloneSetting(existing, newTo, oldTo, now));
                }
                // Deactivate the existing record entirely (the surviving part will be re-inserted)
                existing.setIsActive(false);
                existing.setUpdatedAt(now);
                appSettingRepository.save(existing);

            } else if (newFromInsideExisting) {
                // Default: new starts inside existing and extends past it (or to infinity) -> close existing at newFrom.
                // The trimmed [oldFrom, newFrom) window is still a legitimate historical record for point-in-time
                // lookups (e.g. recalculating an old work log), so it must stay active — same as the left fragment
                // in Case A above. Deactivating it here was the bug: it made past settings unfindable by date.
                existing.setValidUntil(newFrom);
                existing.setUpdatedAt(now);
                appSettingRepository.save(existing);
            }
            // else: no overlap — nothing to do
        }

        // Flush all updates/deactivations before any inserts to satisfy the exclusion constraint
        entityManager.flush();

        // Insert cloned fragments
        appSettingRepository.saveAll(toInsert);

        // Insert the new setting record
        AppSetting newSetting = new AppSetting();
        newSetting.setSettingKey(req.getSettingKey());
        newSetting.setValueType(req.getValueType());
        newSetting.setSettingValueNumeric(req.getSettingValueNumeric());
        newSetting.setSettingValueText(req.getSettingValueText());
        newSetting.setSettingValueBoolean(req.getSettingValueBoolean());
        newSetting.setAffectsPayroll(req.getAffectsPayroll());
        newSetting.setDescription(req.getDescription());
        newSetting.setDisplayText(req.getDisplayText());
        newSetting.setUnit(req.getUnit());
        newSetting.setValidFrom(newFrom);
        newSetting.setValidUntil(newTo);
        newSetting.setIsActive(true);
        newSetting.setCreatedAt(now);

        return new AppSettingResponse(appSettingRepository.save(newSetting));
    }

    /** Creates a new AppSetting row cloning the metadata of {@code source} but with a different validity window. */
    private AppSetting cloneSetting(AppSetting source, OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
        AppSetting clone = new AppSetting();
        clone.setSettingKey(source.getSettingKey());
        clone.setValueType(source.getValueType());
        clone.setSettingValueNumeric(source.getSettingValueNumeric());
        clone.setSettingValueText(source.getSettingValueText());
        clone.setSettingValueBoolean(source.getSettingValueBoolean());
        clone.setAffectsPayroll(source.getAffectsPayroll());
        clone.setDescription(source.getDescription());
        clone.setDisplayText(source.getDisplayText());
        clone.setUnit(source.getUnit());
        clone.setValidFrom(from);
        clone.setValidUntil(to);
        clone.setIsActive(true);
        clone.setCreatedAt(now);
        return clone;
    }
}
