package com.aleksandarparipovic.marel_app.user_table_preferences;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserTablePreferenceRepository extends JpaRepository<UserTablePreference, Long> {

    Optional<UserTablePreference> findByUser_IdAndTableKey(Long userId, String tableKey);

    List<UserTablePreference> findByUser_Id(Long userId);
}
