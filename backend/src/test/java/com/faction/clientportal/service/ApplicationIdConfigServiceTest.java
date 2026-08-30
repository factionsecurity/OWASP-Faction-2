package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationIdConfigDto;
import com.faction.clientportal.dto.ApplicationIdConfigUpdateRequest;
import com.faction.clientportal.model.ApplicationIdConfig;
import com.faction.clientportal.repository.ApplicationIdConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationIdConfigServiceTest {

    @Mock
    private ApplicationIdConfigRepository repository;

    @InjectMocks
    private ApplicationIdConfigService service;

    private ApplicationIdConfig config(String prefix, int nextNumber, boolean enabled) {
        return ApplicationIdConfig.builder()
                .id("default")
                .prefix(prefix)
                .nextNumber(nextNumber)
                .padding(0)
                .enabled(enabled)
                .createdBy("system")
                .lastUpdatedBy("system")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void getConfig_seedsDefaultRowOnFirstRun() {
        when(repository.findById("default")).thenReturn(Optional.empty());
        when(repository.save(any(ApplicationIdConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicationIdConfigDto dto = service.getConfig();

        assertThat(dto.getId()).isEqualTo("default");
        assertThat(dto.getPrefix()).isEqualTo("ASMT");
        assertThat(dto.getNextNumber()).isEqualTo(1);
        assertThat(dto.getPadding()).isEqualTo(0);
        assertThat(dto.getEnabled()).isTrue();
        verify(repository).save(any(ApplicationIdConfig.class));
    }

    @Test
    void getConfig_returnsExistingRow() {
        when(repository.findById("default")).thenReturn(Optional.of(config("WEB", 42, false)));

        ApplicationIdConfigDto dto = service.getConfig();

        assertThat(dto.getPrefix()).isEqualTo("WEB");
        assertThat(dto.getNextNumber()).isEqualTo(42);
        assertThat(dto.getEnabled()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void generateNextAppId_returnsFormattedIdAndIncrementsCounter() {
        when(repository.findByIdForUpdate("default")).thenReturn(Optional.of(config("ASMT", 5, true)));

        String appId = service.generateNextAppId();

        assertThat(appId).isEqualTo("ASMT-5");
        ArgumentCaptor<ApplicationIdConfig> captor = ArgumentCaptor.forClass(ApplicationIdConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getNextNumber()).isEqualTo(6);
    }

    @Test
    void generateNextAppId_isSequentialAcrossCalls() {
        ApplicationIdConfig cfg = config("ASMT", 1, true);
        when(repository.findByIdForUpdate("default")).thenReturn(Optional.of(cfg));
        when(repository.save(any(ApplicationIdConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.generateNextAppId()).isEqualTo("ASMT-1");
        assertThat(service.generateNextAppId()).isEqualTo("ASMT-2");
        assertThat(service.generateNextAppId()).isEqualTo("ASMT-3");
    }

    @Test
    void generateNextAppId_seedsConfigOnFirstRun() {
        ApplicationIdConfig seeded = config("ASMT", 1, true);
        when(repository.findByIdForUpdate("default"))
                .thenReturn(Optional.empty(), Optional.of(seeded));
        when(repository.findById("default")).thenReturn(Optional.empty());
        when(repository.save(any(ApplicationIdConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        String appId = service.generateNextAppId();

        assertThat(appId).isEqualTo("ASMT-1");
    }

    @Test
    void getPreviewNext_returnsSequenceWithoutIncrementing() {
        when(repository.findById("default")).thenReturn(Optional.of(config("APP", 3, true)));

        List<String> preview = service.getPreviewNext(5);

        assertThat(preview).containsExactly("APP-3", "APP-4", "APP-5", "APP-6", "APP-7");
        verify(repository, never()).save(any());
    }

    @Test
    void isEnabled_reflectsConfigFlag() {
        when(repository.findById("default")).thenReturn(Optional.of(config("ASMT", 1, false)));
        assertThat(service.isEnabled()).isFalse();

        when(repository.findById("default")).thenReturn(Optional.of(config("ASMT", 1, true)));
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void updateConfig_updatesProvidedFieldsOnly() {
        when(repository.findById("default")).thenReturn(Optional.of(config("ASMT", 1, true)));
        when(repository.save(any(ApplicationIdConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicationIdConfigDto dto = service.updateConfig(ApplicationIdConfigUpdateRequest.builder()
                .prefix("WEB")
                .nextNumber(100)
                .build());

        assertThat(dto.getPrefix()).isEqualTo("WEB");
        assertThat(dto.getNextNumber()).isEqualTo(100);
        assertThat(dto.getEnabled()).isTrue();
    }

    @Test
    void updateConfig_seedsRowIfMissing() {
        when(repository.findById("default")).thenReturn(Optional.empty());
        when(repository.save(any(ApplicationIdConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ApplicationIdConfigDto dto = service.updateConfig(ApplicationIdConfigUpdateRequest.builder()
                .enabled(false)
                .build());

        assertThat(dto.getPrefix()).isEqualTo("ASMT");
        assertThat(dto.getEnabled()).isFalse();
    }
}
