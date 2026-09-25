package com.faction.clientportal.dto;

import java.time.LocalDate;

/**
 * One stretch of days a person cannot be scheduled: their own time off, a public holiday in
 * their region, or a scheduling block that covers them. Filled in only by the paid overlay; the
 * open source edition never produces one. {@code sourceId} is the time-off or block id, null for
 * holidays.
 */
public record UnavailabilityDto(String userId, LocalDate start, LocalDate end, Kind kind,
                                String label, String sourceId) {

    public enum Kind { TIME_OFF, HOLIDAY, BLOCK }
}
