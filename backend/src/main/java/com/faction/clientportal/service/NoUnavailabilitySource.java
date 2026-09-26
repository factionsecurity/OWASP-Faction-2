package com.faction.clientportal.service;

import com.faction.clientportal.dto.UnavailabilityDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** The open source answer: only assessment bookings make someone busy. */
@Service
public class NoUnavailabilitySource implements UnavailabilitySource {

    @Override
    public List<UnavailabilityDto> find(Collection<String> userIds, LocalDate start, LocalDate end) {
        return List.of();
    }
}
