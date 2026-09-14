package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Application search must be a substring ("contains") match across name and appId — a
 * prefix-anchored match silently missed anything not at the start of the name (e.g. searching
 * "HIGHVULN" for "SA-HIGHVULN-v50"). Descriptions are deliberately not searched.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationRepositoryTest extends TestContainersConfig {

    @Autowired
    private ApplicationRepository applicationRepository;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        applicationRepository.save(Application.builder()
                .name("SA-HIGHVULN-v50").description("A showcase app").appId("APP-001").build());
        applicationRepository.save(Application.builder()
                .name("MA-001-a881-v4928").description("Multi-assessment").appId("APP-002").build());
        applicationRepository.save(Application.builder()
                .name("Unrelated").description("nothing to see").appId("ZZZ-999").build());
    }

    private List<String> searchNames(String term) {
        return applicationRepository.searchByNameOrAppId(term, PageRequest.of(0, 20))
                .map(Application::getName).getContent();
    }

    @Test
    void search_matchesSubstringInMiddleOfName() {
        assertThat(searchNames("HIGHVULN")).containsExactly("SA-HIGHVULN-v50"); // not a prefix
        assertThat(searchNames("a881")).containsExactly("MA-001-a881-v4928");   // mid-name
    }

    @Test
    void search_stillMatchesPrefix() {
        assertThat(searchNames("SA-HIGH")).containsExactly("SA-HIGHVULN-v50");
    }

    @Test
    void search_isCaseInsensitive_matchesAppId_butNotDescription() {
        assertThat(searchNames("highvuln")).containsExactly("SA-HIGHVULN-v50");   // case-insensitive
        assertThat(searchNames("APP-002")).containsExactly("MA-001-a881-v4928");  // appId
        assertThat(searchNames("showcase")).isEmpty();                            // description only
    }

    /** The applications list search: name and application id only — descriptions are not searched. */
    private List<String> listSearchNames(String term) {
        return applicationRepository.searchFiltered(term, null, null, null, PageRequest.of(0, 20))
                .map(Application::getName).getContent();
    }

    @Test
    void listSearch_matchesNameAndAppId_butNotDescription() {
        assertThat(listSearchNames("highvuln")).containsExactly("SA-HIGHVULN-v50");  // name
        assertThat(listSearchNames("APP-002")).containsExactly("MA-001-a881-v4928"); // appId
        // "showcase" appears only in a description, so it finds nothing.
        assertThat(listSearchNames("showcase")).isEmpty();
    }

    @Test
    void search_returnsEmptyWhenNoMatch() {
        assertThat(searchNames("nonexistent-term")).isEmpty();
    }

    @Test
    void search_treatsWildcardCharactersInTermLiterally() {
        applicationRepository.save(Application.builder().name("MA_special").appId("APP-100").build());
        applicationRepository.save(Application.builder().name("MAXspecial").appId("APP-101").build());
        applicationRepository.save(Application.builder().name("100%done").appId("APP-102").build());

        // '_' must match a literal underscore, not "any single character" (else MAXspecial matches)
        assertThat(searchNames("MA_special")).containsExactly("MA_special");
        // '%' must match a literal percent, not "any sequence"
        assertThat(searchNames("100%done")).containsExactly("100%done");
    }
}
