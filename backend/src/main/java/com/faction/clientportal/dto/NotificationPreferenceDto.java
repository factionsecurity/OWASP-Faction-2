package com.faction.clientportal.dto;

import com.faction.clientportal.model.NotificationCategory;
import lombok.Data;

/** One category's effective setting, including its label so the UI needs no lookup table. */
@Data
public class NotificationPreferenceDto {

    private NotificationCategory category;
    private String label;
    private String description;
    private boolean inAppEnabled;
    private boolean emailEnabled;

    public static NotificationPreferenceDto of(NotificationCategory category,
                                               boolean inAppEnabled, boolean emailEnabled) {
        NotificationPreferenceDto dto = new NotificationPreferenceDto();
        dto.setCategory(category);
        dto.setLabel(category.label());
        dto.setDescription(category.description());
        dto.setInAppEnabled(inAppEnabled);
        dto.setEmailEnabled(emailEnabled);
        return dto;
    }
}
