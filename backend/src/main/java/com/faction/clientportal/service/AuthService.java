package com.faction.clientportal.service;

import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;


    public String login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        // Checked before the password, so a locked account cannot have its counter driven higher
        // by someone still guessing at it. Also clears a cooldown that has run its course.
        if (passwordPolicyService.isLockedOut(user)) {
            userRepository.save(user);
            throw new BadCredentialsException(
                    "Too many failed sign-in attempts. Try again later or contact an administrator.");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            registerFailedAttempt(user);
            throw new BadCredentialsException("Invalid username or password");
        }

        if (user.getDeletedAt() != null || user.getDisabledAt() != null) {
            throw new BadCredentialsException("Account is disabled");
        }

        List<GrantedAuthority> authorities = getAuthorities(user);

        passwordPolicyService.clearFailedAttempts(user);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtService.generateToken(username, authorities);
        log.info("User {} logged in successfully", username);
        return token;
    }

    /**
     * Counts a wrong password and disables the account once it reaches the configured limit.
     *
     * <p>The counter keeps climbing past the limit rather than being reset here: it is what tells
     * an admin looking at the account <em>why</em> it is off. Only a successful login or an admin
     * re-enabling clears it. An already-disabled account is left alone — it is off either way, and
     * an attacker should not be able to inflate the count on someone an admin disabled by hand.
     */
    private void registerFailedAttempt(User user) {
        if (user.getDisabledAt() != null) {
            // Already off. Counting further attempts would let someone guessing at an account an
            // administrator disabled manufacture a lockout history against it.
            return;
        }
        passwordPolicyService.registerFailedAttempt(user);
        userRepository.save(user);
    }

    public List<GrantedAuthority> getAuthorities(User user) {
        List<Role> roles = roleRepository.findAllById(user.getRoleIds());
        List<String> permissions = roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .distinct()
                .collect(Collectors.toList());
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public List<String> getPermissions(User user) {
        return getAuthorities(user).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }

    public List<String> getRoleNames(User user) {
        return roleRepository.findAllById(user.getRoleIds()).stream()
                .map(Role::getName)
                .collect(Collectors.toList());
    }
}
