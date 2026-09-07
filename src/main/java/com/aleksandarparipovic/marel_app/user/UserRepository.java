package com.aleksandarparipovic.marel_app.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    @EntityGraph(attributePaths = "role")
    Optional<User> findByUsername(String username);

    /**
     * The lookup sign-in uses.
     *
     * <p>Case-INSENSITIVE, because that is what the database already guarantees: the unique
     * index is on {@code lower(username)}, so "Admin" and "admin" can never be two people.
     * An exact-match lookup therefore refused a sign-in the schema had already promised was
     * unambiguous.
     */
    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsername(String username);

    boolean existsByEmailAddress(String emailAddress);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmailAddressIgnoreCase(String emailAddress);

    /**
     * The account belonging to one worker, if any.
     *
     * <p>Returns at most one because the database says so — uq_users_employee_id
     * is a unique index, not a convention. Used to refuse a second account for
     * the same worker with a sentence rather than a constraint violation.
     */
    Optional<User> findByEmployee_Id(Long employeeId);

    Page<User> findByUsernameContainingIgnoreCase(String username, Pageable pageable);

    /** How many accounts the directory lists at all — its tiles' "Svi". */
    long countByActiveTrue();

    /**
     * Just the ids of the active accounts, for the presence check.
     *
     * <p>Presence is answered by the session table for a LIST of candidates
     * (see {@code UserSessionService#onlineUserIds}), so the directory's
     * "who is here right now" needs the candidate ids and nothing else —
     * loading whole User rows to read one column would drag the role join
     * and every name along for no reader.
     */
    @org.springframework.data.jpa.repository.Query("select u.id from User u where u.active = true")
    List<Long> findActiveIds();

    /**
     * Active accounts per role, in one round trip.
     *
     * <p>Each row is {@code [roleName (String), count (Long)]}. A role nobody
     * active holds returns no row at all, which the stats DTO documents.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r.roleName, count(u) from User u
            join u.role r
            where u.active = true
            group by r.roleName
            """)
    List<Object[]> countActiveByRole();

    Page<User> findByRole_RoleNameIgnoreCase(String roleName, Pageable pageable);

    Page<User> findByActive(Boolean active, Pageable pageable);

    List<User> findByActiveTrueAndArchivedAtIsNullOrderByFullNameAsc();

    List<User> findByActiveTrueAndArchivedAtIsNullAndRole_RoleNameIgnoreCaseOrderByFullNameAsc(String roleName);


    /**
     * Users who currently hold one of the given roles and can actually receive
     * notifications. Used to resolve notification recipients from a permission
     * rather than from a hard-coded role name at the call site.
     */
    @org.springframework.data.jpa.repository.Query("""
            select u from User u
            join fetch u.role r
            where lower(r.roleName) in :roleNames
              and u.accountStatus = com.aleksandarparipovic.marel_app.user.UserAccountStatus.ACTIVE
              and u.archivedAt is null
            """)
    java.util.List<User> findActiveByRoleNames(
            @org.springframework.data.repository.query.Param("roleNames") java.util.List<String> roleNames);
}
