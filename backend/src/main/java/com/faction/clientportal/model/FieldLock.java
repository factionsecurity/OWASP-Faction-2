package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldLock {
    private String fieldId;
    private String username;
    private String displayName;
    private Instant lastActivity;

    public void refreshActivity() { this.lastActivity = Instant.now(); }

    public boolean isExpired(long ttlSeconds) {
        if (lastActivity == null) return true;
        return Instant.now().isAfter(lastActivity.plusSeconds(ttlSeconds));
    }
}
