package com.aleksandarparipovic.marel_app.user_session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByFamilyId(String familyId);

    @Query("""
            select s from UserSession s
            where s.user.id = :userId and s.revokedAt is null
            order by s.lastSeenAt desc
            """)
    List<UserSession> findLiveByUserId(@Param("userId") Long userId);

    /**
     * Presence for a set of users in one query, so an "who is online" list does not
     * fan out into one query per row.
     */
    @Query("""
            select distinct s.user.id from UserSession s
            where s.user.id in :userIds
              and s.revokedAt is null
              and s.expiresAt > :now
              and s.lastSeenAt > :threshold
            """)
    List<Long> findOnlineUserIds(
            @Param("userIds") List<Long> userIds,
            @Param("now") OffsetDateTime now,
            @Param("threshold") OffsetDateTime threshold
    );

    /**
     * Heartbeat as a single UPDATE.
     *
     * <p>Loading the entity to touch one timestamp would be pure overhead on the
     * hottest write in the system (every client, every 30-60s). The WHERE clause
     * also means a revoked session silently stops being refreshed.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update UserSession s
            set s.lastSeenAt = :now
            where s.familyId = :familyId and s.revokedAt is null
            """)
    int touchLastSeen(@Param("familyId") String familyId, @Param("now") OffsetDateTime now);

    /** Cleanup candidate: expired sessions, which also retires their IP/user-agent. */
    @Modifying
    @Query("delete from UserSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
