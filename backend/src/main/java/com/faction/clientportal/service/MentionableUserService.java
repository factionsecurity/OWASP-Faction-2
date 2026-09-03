package com.faction.clientportal.service;

import com.faction.clientportal.dto.MentionableUserDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Who a caller may @mention.
 *
 * <p>Mentioning notifies and emails the target, so the candidate list is an addressing surface,
 * not a directory: it answers "who is already part of this conversation, or on my side of it".
 *
 * <p><b>External (portal) users</b> get exactly three sources, and nothing else:
 * <ol>
 *   <li>their own organization's portal users;</li>
 *   <li>the remediation contact for the record in hand — the vulnerability's remediation owner;</li>
 *   <li>whoever is already on the thread — the vulnerability's subscribers, or the people who have
 *       commented on the application.</li>
 * </ol>
 * A user belonging to a <em>different</em> organization is dropped unconditionally, even if they
 * somehow appear in one of those sources. Staff accounts carry no organization, which is what lets
 * the remediation contact and other assessors through while another client's users can never be.
 *
 * <p><b>Internal users</b> keep the behaviour of the user directory — {@code users:read:all} sees
 * everyone, {@code users:read:team} sees teammates — resolved by {@link UserService}.
 */
@Service
@RequiredArgsConstructor
public class MentionableUserService {

    /** Never return an unbounded list to an autocomplete. */
    private static final int MAX_RESULTS = 10;

    private final UserRepository userRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final AssessmentRepository assessmentRepository;
    private final ApplicationRepository applicationRepository;
    private final AccessScopeService accessScopeService;
    private final UserService userService;

    /**
     * Mention candidates for the current caller, optionally narrowed to a conversation.
     *
     * @param search        prefix typed after the {@code @}; null or blank returns the top of the list
     * @param vulnerabilityId the vulnerability whose comment thread is being written to, if any
     * @param applicationId   the application whose comment thread is being written to, if any
     */
    public List<MentionableUserDto> find(String search, String vulnerabilityId, String applicationId,
                                         Authentication authentication) {
        User caller = accessScopeService.currentUser(authentication).orElse(null);
        if (caller == null) {
            return List.of();
        }
        if (Boolean.TRUE.equals(caller.getIsInternal())) {
            return internalCandidates(search, authentication);
        }
        return externalCandidates(search, vulnerabilityId, applicationId, caller, authentication);
    }

    /**
     * Staff keep the directory they already have: the user list applies {@code users:read:all} /
     * {@code users:read:team} itself, so mentions inherit that scoping rather than defining a
     * second one.
     */
    private List<MentionableUserDto> internalCandidates(String search, Authentication authentication) {
        return userService.searchUsersPaginated(search, PageRequest.of(0, MAX_RESULTS), authentication)
                .getContent().stream()
                // The user list deliberately shows deleted accounts, badged, so an admin can see
                // them; a mention picker must not offer them. Disabled ones stay.
                .filter(u -> u.getDeletedAt() == null)
                .map(u -> new MentionableUserDto(u.getUsername(), displayName(u.getFirstName(), u.getLastName(), u.getUsername())))
                .toList();
    }

    private List<MentionableUserDto> externalCandidates(String search, String vulnerabilityId,
                                                        String applicationId, User caller,
                                                        Authentication authentication) {
        // Keyed by username so a user reachable two ways (an org peer who is also subscribed)
        // appears once, and insertion order puts the conversation ahead of the directory.
        Map<String, User> candidates = new LinkedHashMap<>();

        if (vulnerabilityId != null && !vulnerabilityId.isBlank()) {
            addVulnerabilityThread(vulnerabilityId, authentication, candidates);
        }
        if (applicationId != null && !applicationId.isBlank()) {
            addApplicationThread(applicationId, authentication, candidates);
        }
        for (User peer : orgPeers(caller)) {
            candidates.putIfAbsent(peer.getUsername(), peer);
        }

        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<MentionableUserDto> results = new ArrayList<>();
        for (User candidate : candidates.values()) {
            if (candidate.getUsername().equals(caller.getUsername())) {
                continue; // mentioning yourself notifies you; not useful
            }
            if (candidate.getDeletedAt() != null) {
                continue; // they have left; a disabled account is still offered
            }
            if (isAnotherOrganisation(candidate, caller)) {
                continue; // the hard rule: never another client's users, by any route
            }
            if (!matches(candidate, needle)) {
                continue;
            }
            results.add(new MentionableUserDto(candidate.getUsername(),
                    displayName(candidate.getFirstName(), candidate.getLastName(), candidate.getUsername())));
            if (results.size() == MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    /**
     * The remediation contact and the thread's subscribers. Guarded by the caller's assessment
     * access, so the subscriber list cannot be used to probe threads on other people's work.
     */
    private void addVulnerabilityThread(String vulnerabilityId, Authentication authentication,
                                        Map<String, User> candidates) {
        Vulnerability vuln = vulnerabilityRepository.findByIdAndDeletedAtIsNull(vulnerabilityId).orElse(null);
        if (vuln == null) {
            return;
        }
        Assessment assessment = vuln.getAssessmentId() == null ? null
                : assessmentRepository.findByIdAndDeletedAtIsNull(vuln.getAssessmentId()).orElse(null);
        if (assessment == null) {
            return;
        }
        try {
            accessScopeService.checkAssessmentAccess(authentication, assessment);
        } catch (AccessDeniedException e) {
            return; // out of scope: contribute nothing rather than leaking who is on the thread
        }

        if (vuln.getRemediationOwnerId() != null) {
            userRepository.findById(vuln.getRemediationOwnerId())
                    .ifPresent(owner -> candidates.putIfAbsent(owner.getUsername(), owner));
        }
        addByUsername(vuln.getSubscribers(), candidates);
    }

    /** Whoever has commented on the application — its equivalent of a subscriber list. */
    private void addApplicationThread(String applicationId, Authentication authentication,
                                      Map<String, User> candidates) {
        Application application = applicationRepository.findById(applicationId).orElse(null);
        if (application == null) {
            return;
        }
        try {
            accessScopeService.checkApplicationAccess(authentication, application);
        } catch (AccessDeniedException e) {
            return;
        }
        if (application.getComments() == null) {
            return;
        }
        addByUsername(application.getComments().stream()
                .filter(c -> !c.isSystemGenerated())
                .map(c -> c.getAuthorId())
                .filter(Objects::nonNull)
                .distinct()
                .toList(), candidates);
    }

    private void addByUsername(List<String> usernames, Map<String, User> candidates) {
        if (usernames == null || usernames.isEmpty()) {
            return;
        }
        for (User user : userRepository.findByUsernameIn(usernames)) {
            candidates.putIfAbsent(user.getUsername(), user);
        }
    }

    /**
     * The caller's own organization's portal users; none when they have no organization.
     *
     * <p>Disabled accounts are included — a lockout or an unactivated import is a temporary state,
     * and the notification reaches them once an admin re-enables them. Deleted accounts are not:
     * that person has left.
     */
    private List<User> orgPeers(User caller) {
        if (caller.getOrganizationId() == null) {
            return List.of();
        }
        return userRepository.findByOrganizationIdAndIsInternalFalseAndDeletedAtIsNull(
                caller.getOrganizationId());
    }

    /**
     * True when the candidate belongs to some other organization. A null organization is staff —
     * reachable only because a thread already put them in front of this caller.
     */
    private boolean isAnotherOrganisation(User candidate, User caller) {
        return candidate.getOrganizationId() != null
                && !candidate.getOrganizationId().equals(caller.getOrganizationId());
    }

    private boolean matches(User user, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(user.getUsername(), needle)
                || contains(user.getFirstName(), needle)
                || contains(user.getLastName(), needle)
                || contains(displayName(user.getFirstName(), user.getLastName(), user.getUsername()), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String displayName(String firstName, String lastName, String username) {
        String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return name.isEmpty() ? username : name;
    }
}
