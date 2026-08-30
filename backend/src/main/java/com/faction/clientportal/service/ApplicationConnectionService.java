package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationConnectionDto;
import com.faction.clientportal.dto.CreateApplicationConnectionRequest;
import com.faction.clientportal.dto.UpdateApplicationConnectionRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationConnection;
import com.faction.clientportal.repository.ApplicationConnectionRepository;
import com.faction.clientportal.repository.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApplicationConnectionService {

    private final ApplicationConnectionRepository connectionRepository;
    private final ApplicationRepository applicationRepository;

    public ApplicationConnectionDto createConnection(CreateApplicationConnectionRequest request, String userId) {
        // Verify both applications exist
        Application sourceApp = applicationRepository.findById(request.getSourceApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Source application not found with id: " + request.getSourceApplicationId()));

        Application targetApp = applicationRepository.findById(request.getTargetApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Target application not found with id: " + request.getTargetApplicationId()));

        // Check if connection already exists
        connectionRepository.findBySourceApplicationIdAndTargetApplicationId(
                request.getSourceApplicationId(),
                request.getTargetApplicationId()
        ).ifPresent(conn -> {
            throw new IllegalArgumentException("Connection already exists between these applications");
        });

        ApplicationConnection connection = ApplicationConnection.builder()
                .sourceApplicationId(request.getSourceApplicationId())
                .targetApplicationId(request.getTargetApplicationId())
                .type(request.getType())
                .description(request.getDescription())
                .critical(request.getCritical())
                .dataSensitivity(request.getDataSensitivity())
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        ApplicationConnection savedConnection = connectionRepository.save(connection);
        return toDto(savedConnection, sourceApp.getName(), targetApp.getName());
    }

    public ApplicationConnectionDto updateConnection(String id, UpdateApplicationConnectionRequest request, String userId) {
        ApplicationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found with id: " + id));

        connection.setType(request.getType());
        connection.setDescription(request.getDescription());
        connection.setCritical(request.getCritical());
        connection.setDataSensitivity(request.getDataSensitivity());
        connection.setLastUpdatedBy(userId);
        connection.setUpdatedAt(LocalDateTime.now());

        ApplicationConnection updatedConnection = connectionRepository.save(connection);

        Application sourceApp = applicationRepository.findById(connection.getSourceApplicationId()).orElse(null);
        Application targetApp = applicationRepository.findById(connection.getTargetApplicationId()).orElse(null);

        return toDto(updatedConnection,
                sourceApp != null ? sourceApp.getName() : null,
                targetApp != null ? targetApp.getName() : null);
    }

    public void deleteConnection(String id) {
        ApplicationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found with id: " + id));
        connectionRepository.deleteById(id);
    }

    public ApplicationConnectionDto findById(String id) {
        ApplicationConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found with id: " + id));

        Application sourceApp = applicationRepository.findById(connection.getSourceApplicationId()).orElse(null);
        Application targetApp = applicationRepository.findById(connection.getTargetApplicationId()).orElse(null);

        return toDto(connection,
                sourceApp != null ? sourceApp.getName() : null,
                targetApp != null ? targetApp.getName() : null);
    }

    public List<ApplicationConnectionDto> findAll() {
        return connectionRepository.findAll().stream()
                .map(this::toDtoWithNames)
                .collect(Collectors.toList());
    }

    // Get outgoing connections (what this app depends on)
    public List<ApplicationConnectionDto> findOutgoingConnections(String applicationId) {
        return connectionRepository.findBySourceApplicationId(applicationId).stream()
                .map(this::toDtoWithNames)
                .collect(Collectors.toList());
    }

    // Get incoming connections (what depends on this app)
    public List<ApplicationConnectionDto> findIncomingConnections(String applicationId) {
        return connectionRepository.findByTargetApplicationId(applicationId).stream()
                .map(this::toDtoWithNames)
                .collect(Collectors.toList());
    }

    // Get all connections for an application (both incoming and outgoing)
    public List<ApplicationConnectionDto> findAllConnectionsForApplication(String applicationId) {
        return connectionRepository.findBySourceApplicationIdOrTargetApplicationId(applicationId, applicationId)
                .stream()
                .map(this::toDtoWithNames)
                .collect(Collectors.toList());
    }

    private ApplicationConnectionDto toDto(ApplicationConnection connection, String sourceName, String targetName) {
        return ApplicationConnectionDto.builder()
                .id(connection.getId())
                .sourceApplicationId(connection.getSourceApplicationId())
                .sourceApplicationName(sourceName)
                .targetApplicationId(connection.getTargetApplicationId())
                .targetApplicationName(targetName)
                .type(connection.getType())
                .description(connection.getDescription())
                .critical(connection.getCritical())
                .dataSensitivity(connection.getDataSensitivity())
                .createdBy(connection.getCreatedBy())
                .lastUpdatedBy(connection.getLastUpdatedBy())
                .createdAt(connection.getCreatedAt())
                .updatedAt(connection.getUpdatedAt())
                .build();
    }

    private ApplicationConnectionDto toDtoWithNames(ApplicationConnection connection) {
        Application sourceApp = applicationRepository.findById(connection.getSourceApplicationId()).orElse(null);
        Application targetApp = applicationRepository.findById(connection.getTargetApplicationId()).orElse(null);

        return toDto(connection,
                sourceApp != null ? sourceApp.getName() : null,
                targetApp != null ? targetApp.getName() : null);
    }
}
