package com.faction.clientportal.repository;

import com.faction.clientportal.model.EmailNotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailNotificationConfigRepository extends JpaRepository<EmailNotificationConfig, String> {
}
