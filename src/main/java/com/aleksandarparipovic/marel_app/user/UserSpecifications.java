package com.aleksandarparipovic.marel_app.user;

import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {

    public static Specification<User> usernameContains(String value) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("username")), "%" + value.toLowerCase() + "%");
    }

    /**
     * One box, three columns.
     *
     * <p>The directory is opened to look somebody up, and people look each other
     * up by NAME. {@link #usernameContains} alone answered almost none of those
     * searches — most of this factory's usernames are generated, so the thing
     * the reader knows about a colleague is the one field it did not match.
     *
     * <p>Kept separate from {@code usernameContains} rather than replacing it:
     * that one is the administrative filter and means exactly what it says.
     */
    public static Specification<User> matches(String value) {
        String needle = "%" + value.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("username")), needle),
                cb.like(cb.lower(root.get("fullName")), needle),
                cb.like(cb.lower(root.get("emailAddress")), needle));
    }

    public static Specification<User> hasRole(String roleName) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("role").get("roleName")), roleName.toLowerCase());
    }

    /**
     * The account belonging to one worker — zero rows or one, never more, because
     * uq_users_employee_id says so.
     *
     * <p>Exists so a worker's page can ask "whose account is this" in one query,
     * rather than pulling every user down and searching for the match in the
     * browser. That approach works today, on a few dozen accounts, and stops
     * working silently.
     */
    public static Specification<User> hasEmployee(Long employeeId) {
        return (root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId);
    }

    public static Specification<User> isActive(Boolean active) {
        return (root, query, cb) ->
                cb.equal(root.get("active"), active);
    }
}
