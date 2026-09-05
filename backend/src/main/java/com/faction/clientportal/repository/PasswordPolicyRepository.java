package com.faction.clientportal.repository;

import com.faction.clientportal.model.PasswordPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordPolicyRepository extends JpaRepository<PasswordPolicy, String> {
}
