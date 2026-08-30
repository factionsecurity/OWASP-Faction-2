package com.faction.clientportal.repository;

import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiPromptTemplateRepository extends JpaRepository<AiPromptTemplate, String> {

    List<AiPromptTemplate> findByScopeAndEnabledTrueOrderByNameAsc(AiPromptScope scope);
}
