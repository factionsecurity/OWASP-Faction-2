package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentTypeDto;
import com.faction.clientportal.dto.CreateAssessmentTypeRequest;
import com.faction.clientportal.dto.UpdateAssessmentTypeRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssessmentTypeService {

    private final AssessmentTypeRepository assessmentTypeRepository;
    // TODO: Inject AssessmentRepository once Assessment model is created

    /**
     * Create a new assessment type
     *
     * @param request The assessment type creation request
     * @return The created assessment type DTO
     * @throws IllegalArgumentException if assessment type name already exists
     */
    @Transactional
    public AssessmentTypeDto createAssessmentType(CreateAssessmentTypeRequest request) {
        // Check if assessment type name already exists
        if (assessmentTypeRepository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Assessment type with name '" + request.getName() + "' already exists");
        }

        AssessmentType assessmentType = AssessmentType.builder()
                .name(request.getName())
                .description(request.getDescription())
                .active(request.getActive())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        AssessmentType savedAssessmentType = assessmentTypeRepository.save(assessmentType);
        return toDto(savedAssessmentType);
    }

    /**
     * Update an existing assessment type
     *
     * @param id      The assessment type ID
     * @param request The update request
     * @return The updated assessment type DTO
     * @throws ResourceNotFoundException if assessment type not found
     * @throws IllegalArgumentException  if name conflicts with another assessment type
     */
    @Transactional
    public AssessmentTypeDto updateAssessmentType(String id, UpdateAssessmentTypeRequest request) {
        AssessmentType assessmentType = assessmentTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + id));

        // Check if name is being changed and if it conflicts with another assessment type
        if (!assessmentType.getName().equals(request.getName())) {
            assessmentTypeRepository.findByName(request.getName()).ifPresent(existingType -> {
                if (!existingType.getId().equals(id)) {
                    throw new IllegalArgumentException("Assessment type with name '" + request.getName() + "' already exists");
                }
            });
        }

        assessmentType.setName(request.getName());
        assessmentType.setDescription(request.getDescription());
        assessmentType.setActive(request.getActive());
        assessmentType.setUpdatedAt(LocalDateTime.now());

        AssessmentType updatedAssessmentType = assessmentTypeRepository.save(assessmentType);
        return toDto(updatedAssessmentType);
    }

    /**
     * Delete or deactivate an assessment type
     * If the assessment type is assigned to any assessments, it will be deactivated instead of deleted
     *
     * @param id The assessment type ID
     * @return true if deleted, false if deactivated
     * @throws ResourceNotFoundException if assessment type not found
     */
    @Transactional
    public boolean deleteAssessmentType(String id) {
        AssessmentType assessmentType = assessmentTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + id));

        // Check if assessment type is assigned to any assessments
        boolean isInUse = isAssessmentTypeInUse(id);

        if (isInUse) {
            // Deactivate instead of delete
            assessmentType.setActive(false);
            assessmentType.setUpdatedAt(LocalDateTime.now());
            assessmentTypeRepository.save(assessmentType);
            return false; // Indicates it was deactivated
        } else {
            // Safe to delete
            assessmentTypeRepository.deleteById(id);
            return true; // Indicates it was deleted
        }
    }

    /**
     * Get an assessment type by ID
     *
     * @param id The assessment type ID
     * @return The assessment type DTO
     * @throws ResourceNotFoundException if assessment type not found
     */
    public AssessmentTypeDto getAssessmentTypeById(String id) {
        AssessmentType assessmentType = assessmentTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + id));
        return toDto(assessmentType);
    }

    /**
     * Get all assessment types with pagination
     *
     * @param pageable Pagination information
     * @return Page of assessment type DTOs
     */
    public Page<AssessmentTypeDto> getAllAssessmentTypes(Pageable pageable) {
        return assessmentTypeRepository.findAll(pageable)
                .map(this::toDto);
    }

    /**
     * Search assessment types by name or description with pagination
     *
     * @param search   Search term (searches name and description, case-insensitive)
     * @param pageable Pagination information
     * @return Page of assessment type DTOs matching the search criteria
     */
    public Page<AssessmentTypeDto> searchAssessmentTypes(String search, Pageable pageable) {
        if (search == null || search.trim().isEmpty()) {
            return getAllAssessmentTypes(pageable);
        }
        return assessmentTypeRepository.searchByNameOrDescription(search.trim(), pageable)
                .map(this::toDto);
    }

    /**
     * Get all assessment types without pagination
     *
     * @return List of all assessment type DTOs
     */
    public List<AssessmentTypeDto> getAllAssessmentTypes() {
        return assessmentTypeRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Check if an assessment type is assigned to any assessments
     * TODO: Implement this once Assessment model is created
     *
     * @param assessmentTypeId The assessment type ID
     * @return true if in use, false otherwise
     */
    private boolean isAssessmentTypeInUse(String assessmentTypeId) {
        // TODO: Once Assessment model exists, check if any assessments reference this type
        // Example: return assessmentRepository.existsByAssessmentTypeId(assessmentTypeId);
        return false; // Placeholder - currently allows deletion
    }

    /**
     * Convert AssessmentType entity to AssessmentTypeDto
     *
     * @param assessmentType The assessment type entity
     * @return The assessment type DTO
     */
    private AssessmentTypeDto toDto(AssessmentType assessmentType) {
        return AssessmentTypeDto.builder()
                .id(assessmentType.getId())
                .name(assessmentType.getName())
                .description(assessmentType.getDescription())
                .active(assessmentType.getActive())
                .createdAt(assessmentType.getCreatedAt())
                .updatedAt(assessmentType.getUpdatedAt())
                .build();
    }
}
