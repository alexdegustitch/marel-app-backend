package com.aleksandarparipovic.marel_app.user_saved_view;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSavedViewRepository extends JpaRepository<UserSavedView, Long> {

    List<UserSavedView> findByUser_IdAndViewKeyAndArchivedAtIsNullOrderByNameAsc(
            Long userId, String viewKey);

    Optional<UserSavedView> findByUser_IdAndViewKeyAndIsDefaultTrueAndArchivedAtIsNull(
            Long userId, String viewKey);

    boolean existsByUser_IdAndViewKeyAndNameIgnoreCaseAndArchivedAtIsNull(
            Long userId, String viewKey, String name);

    /**
     * Clears the current default for a view key in one statement, so the
     * "unset old, set new" pair happens inside a single transaction with no window
     * in which two defaults exist. uq_user_saved_views_one_default is the backstop.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserSavedView v
            set v.isDefault = false
            where v.user.id = :userId
              and v.viewKey = :viewKey
              and v.isDefault = true
            """)
    int clearDefault(@Param("userId") Long userId, @Param("viewKey") String viewKey);
}
