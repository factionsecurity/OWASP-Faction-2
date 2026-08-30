package com.faction.clientportal.service;

import com.faction.clientportal.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtTokenProvider jwtTokenProvider;

    public String generateToken(String username, Collection<? extends GrantedAuthority> authorities) {
        return jwtTokenProvider.generateToken(username, authorities);
    }

    public long getExpirationTime() {
        return jwtTokenProvider.getValidityInMilliseconds();
    }
}
