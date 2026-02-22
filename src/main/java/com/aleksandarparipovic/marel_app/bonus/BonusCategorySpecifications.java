package com.aleksandarparipovic.marel_app.bonus;

import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDate;

public class BonusCategorySpecifications {

    public static Specification<BonusCategory> isActive(Boolean active) {
        return (root, q, cb) ->
                active == null ? null : cb.equal(root.get("active"), active);
    }

    public static Specification<BonusCategory> notArchived() {
        return (root, q, cb) -> cb.isNull(root.get("archivedAt"));
    }

    public static Specification<BonusCategory> validOn(LocalDate date) {
        return (root, q, cb) -> {
            if (date == null) return null;
            return cb.and(
                    cb.lessThanOrEqualTo(root.get("validFrom"), date),
                    cb.or(
                            cb.isNull(root.get("validUntil")),
                            cb.greaterThanOrEqualTo(root.get("validUntil"), date)
                    )
            );
        };
    }

    public static Specification<BonusCategory> hasCode(String code) {
        return (root, q, cb) ->
                code == null ? null : cb.equal(root.get("categoryNo"), code);
    }
}
