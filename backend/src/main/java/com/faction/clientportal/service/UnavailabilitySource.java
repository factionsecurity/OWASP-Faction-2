package com.faction.clientportal.service;

import com.faction.clientportal.dto.UnavailabilityDto;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Why people are unavailable beyond their assessment bookings. Core answers "nobody"
 * ({@link NoUnavailabilitySource}); the overlay's team scheduling supersedes it with time off,
 * regional holidays and scheduling blocks, marked {@code @Primary}.
 */
public interface UnavailabilitySource {

    /** Every unavailability for these users overlapping [start, end], inclusive. */
    List<UnavailabilityDto> find(Collection<String> userIds, LocalDate start, LocalDate end);
}
