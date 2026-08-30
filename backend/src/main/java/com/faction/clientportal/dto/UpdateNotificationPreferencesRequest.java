package com.faction.clientportal.dto;

import com.faction.clientportal.model.NotificationCategory;
import lombok.Data;

import java.util.List;

/**
 * Partial update: only the categories present are touched, and within one, a null flag
 * leaves that channel unchanged. Lets the UI save a single toggle.
 */
@Data
public class UpdateNotificationPreferencesRequest {

    private List<Item> preferences;

    @Data
    public static class Item {
        private NotificationCategory category;
        private Boolean inAppEnabled;
        private Boolean emailEnabled;
    }
}
