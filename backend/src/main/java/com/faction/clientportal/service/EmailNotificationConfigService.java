package com.faction.clientportal.service;

import com.faction.clientportal.dto.EmailNotificationConfigDto;
import com.faction.clientportal.dto.UpdateEmailNotificationConfigRequest;
import com.faction.clientportal.model.AssessmentWorkflowConfig.RemediationStage;
import com.faction.clientportal.model.EmailNotificationConfig;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.repository.EmailNotificationConfigRepository;
import com.faction.clientportal.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the outbound notification routing table.
 *
 * <p>Callers ask {@link #isEnabled} before building an email. That single method folds in
 * the master switch, so a notifier never has to remember to check it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationConfigService {

    private final EmailNotificationConfigRepository repository;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final EmailService emailService;

    public EmailNotificationConfig getOrCreate() {
        return repository.findById(EmailNotificationConfig.SINGLETON_ID)
                .orElseGet(() -> repository.save(EmailNotificationConfig.builder()
                        .id(EmailNotificationConfig.SINGLETON_ID)
                        .build()));
    }

    /** Settings for one event key, never null. All-off when nothing has been configured. */
    public EventSettings settingsFor(String key) {
        try {
            return getOrCreate().settingsFor(key);
        } catch (Exception e) {
            // A config read must never be the reason a request fails — the email is the
            // optional part of whatever the user was actually doing.
            log.warn("Could not read email notification settings for {}: {}", key, e.getMessage());
            return EventSettings.builder().build();
        }
    }

    public EventSettings settingsFor(EmailNotificationEvent event) {
        return settingsFor(event.key());
    }

    /** True when the master switch is on and at least one audience is selected for this event. */
    public boolean isEnabled(String key) {
        try {
            EmailNotificationConfig config = getOrCreate();
            return config.isEnabled() && config.settingsFor(key).isAnyAudienceEnabled();
        } catch (Exception e) {
            log.warn("Could not read email notification config for {}: {}", key, e.getMessage());
            return false;
        }
    }

    public boolean isEnabled(EmailNotificationEvent event) {
        return isEnabled(event.key());
    }

    // ── Admin API ─────────────────────────────────────────────────────────────

    public EmailNotificationConfigDto getConfig() {
        return toDto(getOrCreate());
    }

    public EmailNotificationConfigDto updateConfig(UpdateEmailNotificationConfigRequest request) {
        EmailNotificationConfig config = getOrCreate();

        if (request.getEnabled() != null) config.setEnabled(request.getEnabled());
        if (request.getPastDueRepeatCount() != null) {
            config.setPastDueRepeatCount(Math.max(0, request.getPastDueRepeatCount()));
        }
        if (request.getPastDueRepeatIntervalDays() != null) {
            // At least a day: a zero-day interval would re-send the digest on every run.
            config.setPastDueRepeatIntervalDays(Math.max(1, request.getPastDueRepeatIntervalDays()));
        }

        if (request.getEvents() != null) {
            // getEvents() hands back a defensive copy, so the map has to be mutated locally
            // and written back with setEvents. Calling config.getEvents().put(...) would
            // update a throwaway and silently lose the change.
            Map<String, EventSettings> events = config.getEvents();

            for (UpdateEmailNotificationConfigRequest.EventUpdate update : request.getEvents()) {
                if (update.getKey() == null || update.getKey().isBlank()) continue;
                if (EmailNotificationEvent.fromKey(update.getKey()) == null) continue; // unknown key

                EventSettings settings = events
                        .getOrDefault(update.getKey(), EventSettings.builder().build());

                if (update.getNotifyAssessors() != null)      settings.setNotifyAssessors(update.getNotifyAssessors());
                if (update.getNotifyStakeholders() != null)   settings.setNotifyStakeholders(update.getNotifyStakeholders());
                if (update.getNotifyAppOwner() != null)       settings.setNotifyAppOwner(update.getNotifyAppOwner());
                if (update.getIncludeMentionedUsers() != null) settings.setIncludeMentionedUsers(update.getIncludeMentionedUsers());
                if (update.getNotifyOrgUsers() != null)       settings.setNotifyOrgUsers(update.getNotifyOrgUsers());
                if (update.getCustomMessage() != null) {
                    settings.setCustomMessage(update.getCustomMessage().isBlank()
                            ? null : update.getCustomMessage().trim());
                }

                events.put(update.getKey(), settings);
            }

            config.setEvents(events);
        }

        return toDto(repository.save(config));
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private EmailNotificationConfigDto toDto(EmailNotificationConfig config) {
        List<EmailNotificationConfigDto.EventDto> events = new ArrayList<>();

        for (EmailNotificationEvent event : EmailNotificationEvent.values()) {
            if (event.isPerStage()) {
                for (RemediationStage stage : workflowConfigService.remediationStages()) {
                    events.add(eventDto(config, event, stage.getId(),
                            event.label() + " in " + stage.getName()));
                }
            } else {
                events.add(eventDto(config, event, null, event.label()));
            }
        }

        return EmailNotificationConfigDto.builder()
                .enabled(config.isEnabled())
                .smtpConfigured(emailService.isConfigured())
                .pastDueRepeatCount(config.getPastDueRepeatCount())
                .pastDueRepeatIntervalDays(config.getPastDueRepeatIntervalDays())
                .events(events)
                .build();
    }

    private EmailNotificationConfigDto.EventDto eventDto(EmailNotificationConfig config,
                                                        EmailNotificationEvent event,
                                                        String stageId,
                                                        String label) {
        String key = event.key(stageId);
        EventSettings settings = config.settingsFor(key);
        return EmailNotificationConfigDto.EventDto.builder()
                .key(key)
                .event(event.name())
                .label(label)
                .description(event.description())
                .audiences(new ArrayList<>(event.audiences()))
                .notifyAssessors(settings.isNotifyAssessors())
                .notifyStakeholders(settings.isNotifyStakeholders())
                .notifyAppOwner(settings.isNotifyAppOwner())
                .includeMentionedUsers(settings.isIncludeMentionedUsers())
                .notifyOrgUsers(settings.isNotifyOrgUsers())
                .customMessage(settings.getCustomMessage())
                .perStage(event.isPerStage())
                .stageId(stageId)
                .build();
    }
}
