package com.faction.clientportal.service;

import com.faction.clientportal.dto.CampaignDto;
import com.faction.clientportal.dto.CreateCampaignRequest;
import com.faction.clientportal.dto.UpdateCampaignRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.CampaignRepository;
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
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final AssessmentRepository assessmentRepository;

    /**
     * Create a new campaign
     *
     * @throws IllegalArgumentException if the campaign name already exists
     */
    @Transactional
    public CampaignDto createCampaign(CreateCampaignRequest request) {
        String name = request.getName().trim();
        if (campaignRepository.existsByName(name)) {
            throw new IllegalArgumentException("Campaign with name '" + name + "' already exists");
        }

        Campaign campaign = Campaign.builder()
                .name(name)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return toDto(campaignRepository.save(campaign));
    }

    /**
     * Update a campaign (rename and/or toggle the default flag).
     * At most one campaign is flagged default at a time: setting isDefault=true
     * unsets the flag on the previous default.
     *
     * @throws ResourceNotFoundException if the campaign is not found
     * @throws IllegalArgumentException  if the new name conflicts with another campaign
     */
    @Transactional
    public CampaignDto updateCampaign(String id, UpdateCampaignRequest request) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + id));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            String name = request.getName().trim();
            if (!campaign.getName().equals(name)) {
                campaignRepository.findByName(name).ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new IllegalArgumentException("Campaign with name '" + name + "' already exists");
                    }
                });
                campaign.setName(name);
            }
        }

        if (request.getIsDefault() != null) {
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                campaignRepository.findByIsDefaultTrue().ifPresent(previousDefault -> {
                    if (!previousDefault.getId().equals(id)) {
                        previousDefault.setIsDefault(false);
                        previousDefault.setUpdatedAt(LocalDateTime.now());
                        campaignRepository.save(previousDefault);
                    }
                });
            }
            campaign.setIsDefault(request.getIsDefault());
        }

        campaign.setUpdatedAt(LocalDateTime.now());
        return toDto(campaignRepository.save(campaign));
    }

    /**
     * Delete a campaign. Blocked while any (non-deleted) assessment references it,
     * matching the legacy behavior — reassign or delete those assessments first.
     *
     * @throws ResourceNotFoundException if the campaign is not found
     * @throws IllegalArgumentException  if the campaign is referenced by an assessment
     */
    @Transactional
    public void deleteCampaign(String id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + id));

        if (assessmentRepository.existsByCampaignIdAndDeletedAtIsNull(id)) {
            throw new IllegalArgumentException(
                    "Cannot delete campaign '" + campaign.getName() + "': it is assigned to one or more assessments");
        }

        campaignRepository.deleteById(id);
    }

    public CampaignDto getCampaignById(String id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + id));
        return toDto(campaign);
    }

    public Page<CampaignDto> searchCampaigns(String search, Pageable pageable) {
        if (search == null || search.trim().isEmpty()) {
            return campaignRepository.findAll(pageable).map(this::toDto);
        }
        return campaignRepository.searchByName(search.trim(), pageable).map(this::toDto);
    }

    public List<CampaignDto> getAllCampaigns() {
        return campaignRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private CampaignDto toDto(Campaign campaign) {
        return CampaignDto.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .isDefault(campaign.getIsDefault())
                .createdAt(campaign.getCreatedAt())
                .updatedAt(campaign.getUpdatedAt())
                .build();
    }
}
