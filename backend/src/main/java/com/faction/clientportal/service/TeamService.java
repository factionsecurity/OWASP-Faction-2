package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateTeamRequest;
import com.faction.clientportal.dto.TeamDto;
import com.faction.clientportal.dto.UpdateTeamRequest;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
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
public class TeamService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;

    /**
     * Create a new team
     *
     * @param request The team creation request
     * @return The created team DTO
     * @throws IllegalArgumentException if team name already exists
     */
    @Transactional
    public TeamDto createTeam(CreateTeamRequest request) {
        // Check if team name already exists
        if (teamRepository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Team with name '" + request.getName() + "' already exists");
        }

        Team team = Team.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Team savedTeam = teamRepository.save(team);
        return toDto(savedTeam);
    }

    /**
     * Update an existing team
     *
     * @param id      The team ID
     * @param request The update request
     * @return The updated team DTO
     * @throws ResourceNotFoundException if team not found
     * @throws IllegalArgumentException  if name conflicts with another team
     */
    @Transactional
    public TeamDto updateTeam(String id, UpdateTeamRequest request) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + id));

        // Check if name is being changed and if it conflicts with another team
        if (!team.getName().equals(request.getName())) {
            teamRepository.findByName(request.getName()).ifPresent(existingTeam -> {
                if (!existingTeam.getId().equals(id)) {
                    throw new IllegalArgumentException("Team with name '" + request.getName() + "' already exists");
                }
            });
        }

        team.setName(request.getName());
        team.setDescription(request.getDescription());
        team.setUpdatedAt(LocalDateTime.now());

        Team updatedTeam = teamRepository.save(team);
        return toDto(updatedTeam);
    }

    /**
     * Delete a team by ID
     * Also removes the team from all users' teamIds lists
     *
     * @param id The team ID
     * @throws ResourceNotFoundException if team not found
     */
    @Transactional
    public void deleteTeam(String id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + id));

        // Remove team from all users' teamIds
        List<User> usersInTeam = userRepository.findAll().stream()
                .filter(user -> user.getTeamIds() != null && user.getTeamIds().contains(id))
                .collect(Collectors.toList());

        for (User user : usersInTeam) {
            user.getTeamIds().remove(id);
            userRepository.save(user);
        }

        teamRepository.deleteById(id);
    }

    /**
     * Get a team by ID
     *
     * @param id The team ID
     * @return The team DTO
     * @throws ResourceNotFoundException if team not found
     */
    public TeamDto getTeamById(String id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + id));
        return toDto(team);
    }

    /**
     * Get all teams with pagination
     *
     * @param pageable Pagination information
     * @return Page of team DTOs
     */
    public Page<TeamDto> getAllTeams(Pageable pageable) {
        return teamRepository.findAll(pageable)
                .map(this::toDto);
    }

    /**
     * Search teams by name or description with pagination
     *
     * @param search   Search term (searches name and description, case-insensitive)
     * @param pageable Pagination information
     * @return Page of team DTOs matching the search criteria
     */
    public Page<TeamDto> searchTeams(String search, Pageable pageable) {
        if (search == null || search.trim().isEmpty()) {
            return getAllTeams(pageable);
        }
        return teamRepository.searchByNameOrDescription(search.trim(), pageable)
                .map(this::toDto);
    }

    /**
     * Get all teams without pagination
     *
     * @return List of all team DTOs
     */
    public List<TeamDto> getAllTeams() {
        return teamRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Add a user to a team
     *
     * @param teamId The team ID
     * @param userId The user ID
     * @throws ResourceNotFoundException if team or user not found
     * @throws IllegalArgumentException  if user is already in the team
     */
    @Transactional
    public void addUserToTeam(String teamId, String userId) {
        // Verify team exists
        teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + teamId));

        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // Check if user is already in the team
        if (user.getTeamIds() != null && user.getTeamIds().contains(teamId)) {
            throw new IllegalArgumentException("User is already a member of this team");
        }

        // Add team to user's teamIds
        if (user.getTeamIds() == null) {
            user.setTeamIds(List.of(teamId));
        } else {
            user.getTeamIds().add(teamId);
        }

        userRepository.save(user);
    }

    /**
     * Remove a user from a team
     *
     * @param teamId The team ID
     * @param userId The user ID
     * @throws ResourceNotFoundException if team or user not found
     * @throws IllegalArgumentException  if user is not in the team
     */
    @Transactional
    public void removeUserFromTeam(String teamId, String userId) {
        // Verify team exists
        teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + teamId));

        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // Check if user is in the team
        if (user.getTeamIds() == null || !user.getTeamIds().contains(teamId)) {
            throw new IllegalArgumentException("User is not a member of this team");
        }

        // Remove team from user's teamIds
        user.getTeamIds().remove(teamId);
        userRepository.save(user);
    }

    /**
     * Get all users in a team
     *
     * @param teamId The team ID
     * @return List of users in the team
     * @throws ResourceNotFoundException if team not found
     */
    public List<UserDto> getUsersInTeam(String teamId) {
        // Verify team exists
        teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found with id: " + teamId));

        // Get all users that have this teamId
        return userRepository.findByTeamIdsContaining(teamId).stream()
                .map(this::toUserDto)
                .collect(Collectors.toList());
    }

    /**
     * Convert Team entity to TeamDto
     *
     * @param team The team entity
     * @return The team DTO
     */
    private TeamDto toDto(Team team) {
        return TeamDto.builder()
                .id(team.getId())
                .name(team.getName())
                .description(team.getDescription())
                .createdAt(team.getCreatedAt())
                .updatedAt(team.getUpdatedAt())
                .build();
    }

    /**
     * Convert User entity to UserDto
     *
     * @param user The user entity
     * @return The user DTO
     */
    private UserDto toUserDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .loginOption(user.getLoginOption())
                .roleIds(user.getRoleIds())
                .teamIds(user.getTeamIds())
                .isInternal(user.getIsInternal())
                .organizationId(user.getOrganizationId())
                .createdAt(user.getCreatedAt())
                .deletedAt(user.getDeletedAt())
                .disabledAt(user.getDisabledAt())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .lastLogin(user.getLastLogin())
                .build();
    }
}
