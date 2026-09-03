package com.faction.clientportal.repository;

import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.ContentTemplateScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentTemplateRepository extends JpaRepository<ContentTemplate, String> {

    List<ContentTemplate> findByScopeAndEnabledTrueOrderByNameAsc(ContentTemplateScope scope);
}
