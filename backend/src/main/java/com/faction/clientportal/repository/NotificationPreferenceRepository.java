package com.faction.clientportal.repository;

import com.faction.clientportal.model.NotificationCategory;
import com.faction.clientportal.model.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, String> {

    List<NotificationPreference> findByUsername(String username);

    Optional<NotificationPreference> findByUsernameAndCategory(String username, NotificationCategory category);
}
