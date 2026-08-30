package com.faction.clientportal.repository;

import com.faction.clientportal.model.EmailConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailConfigRepository extends JpaRepository<EmailConfig, String> {
}
