package com.aleksandarparipovic.marel_app.compensation_scheme;

/**
 * The stable codes seeded by {@code 2026-07-27-01-compensation-schemes.sql}.
 *
 * <p>Schemes are resolved by code, never by a hard-coded database id — ids differ
 * between environments and a migration that inserts by id is a data-corruption
 * bug waiting for the first fresh database.
 *
 * <p>This is deliberately NOT an enum over all schemes: administrators may add
 * schemes, and the resolver treats every scheme uniformly. Only these two are
 * named because the seed data and the backfill depend on them.
 */
public final class CompensationSchemeCodes {

    /** Default policy: every active category is selectable, normal coefficients. */
    public static final String STANDARD = "STANDARD";

    /**
     * Restricted policy: only categories with an explicit scheme rule may be
     * selected, and the shift categories resolve to one effective category at a
     * fixed coefficient.
     */
    public static final String FOREIGN_FIXED_COEFFICIENT = "FOREIGN_FIXED_COEFFICIENT";

    /**
     * Commercial staff: work categories behave as under STANDARD, but no hourly
     * bonus is earned and the bonus line is shown at zero.
     *
     * <p>Named here because the 2026-08-15-02 backfill depends on it. It replaces
     * {@code employees.works_in_commercial} as the payroll authority — that column
     * stays as personnel data, and no calculator reads it.
     */
    public static final String COMMERCIAL = "COMMERCIAL";

    private CompensationSchemeCodes() {
    }
}
