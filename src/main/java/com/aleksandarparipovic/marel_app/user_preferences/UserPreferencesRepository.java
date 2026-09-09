package com.aleksandarparipovic.marel_app.user_preferences;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, Long> {

    /**
     * The row locked for update — {@code SELECT ... FOR UPDATE}.
     *
     * <p>A user's own preferences are written from several places at once (the
     * theme sync, the profile page, the sidebar toggle), and two of those firing
     * close together used to race the {@code @Version} column: both read the same
     * version, both wrote {@code version + 1}, and the second flush matched no row
     * and failed the whole request — so a theme toggle would snap back. Taking a
     * row lock on the update path serialises those overlapping writes: the second
     * transaction waits for the first to commit and then reads the fresh version,
     * so its update matches. Contention is only ever between one person's own
     * concurrent requests on their single row, so the lock is never held long.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPreferences p where p.userId = :userId")
    Optional<UserPreferences> findByIdForUpdate(@Param("userId") Long userId);
}
