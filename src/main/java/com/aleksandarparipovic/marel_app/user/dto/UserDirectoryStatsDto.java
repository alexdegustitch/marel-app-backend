package com.aleksandarparipovic.marel_app.user.dto;

import java.util.List;

/**
 * What the directory's filter tiles say: how many active accounts there are,
 * how many are at the application right now, and how the accounts split by
 * role.
 *
 * <p>Counts ACTIVE accounts only, because that is what the directory itself
 * lists — a tile that said "12" over a list that can only ever show 9 would
 * read as three people lost.
 *
 * <p>Roles come back as the raw {@code role_name} identifiers; the screen owns
 * the words (see the frontend's {@code roleLabel}), the server owns the
 * numbers. A role with zero active accounts is simply absent.
 */
public record UserDirectoryStatsDto(long total, long online, List<RoleCount> roles) {

    public record RoleCount(String roleName, long count) {
    }
}
