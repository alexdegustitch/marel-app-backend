package com.aleksandarparipovic.marel_app.auth.refresh;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select rt
            from RefreshToken rt
            join fetch rt.user u
            join fetch u.role
            where rt.tokenHash = :tokenHash
            """)
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken rt
            set rt.revokedAt = :revokedAt,
                rt.revokedReason = :reason
            where rt.familyId = :familyId
              and rt.revokedAt is null
            """)
    int revokeAllByFamilyId(
            @Param("familyId") String familyId,
            @Param("revokedAt") OffsetDateTime revokedAt,
            @Param("reason") String reason
    );
}
