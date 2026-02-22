package com.aleksandarparipovic.marel_app.user;

import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {

    public static Specification<User> usernameContains(String value) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("username")), "%" + value.toLowerCase() + "%");
    }

    public static Specification<User> hasRole(String roleName) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("role").get("roleName")), roleName.toLowerCase());
    }

    public static Specification<User> isActive(Boolean active) {
        return (root, query, cb) ->
                cb.equal(root.get("active"), active);
    }
}
