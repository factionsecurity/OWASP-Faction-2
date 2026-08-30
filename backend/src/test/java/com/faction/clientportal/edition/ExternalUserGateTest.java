package com.faction.clientportal.edition;

import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.ApiKeyService;
import com.faction.clientportal.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * External accounts are portal users, and the portal is a paid capability.
 *
 * <p>The user form hides the choice where it does not apply, but a form is not a gate —
 * the endpoint accepts whatever it is sent. These cover the endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalUserGateTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RoleRepository roleRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ApiKeyService apiKeyService;
    @Mock private EditionPolicy editionPolicy;

    @InjectMocks private UserService userService;

    private static final Authentication SUPER_ADMIN = new UsernamePasswordAuthenticationToken(
            "admin", null, List.of(new SimpleGrantedAuthority("super_admin")));

    private CreateUserRequest request(Boolean internal) {
        CreateUserRequest r = new CreateUserRequest();
        r.setUsername("someone");
        r.setEmail("someone@test.com");
        r.setPassword("password");
        r.setRoleIds(List.of());
        r.setIsInternal(internal);
        return r;
    }

    @Test
    void refusesAnExternalAccountWhenThePortalIsNotIncluded() {
        doThrow(new FeatureNotLicensedException(Feature.EXTERNAL_OWNERS))
                .when(editionPolicy).require(Feature.EXTERNAL_OWNERS);

        assertThatThrownBy(() -> userService.createUserDto(request(false), SUPER_ADMIN))
                .isInstanceOf(FeatureNotLicensedException.class);

        verify(userRepository, never()).save(any());
    }

    /** The ordinary case has to keep working, and is the only kind this build creates. */
    @Test
    void allowsAnInternalAccount() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(roleRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> userService.createUserDto(request(true), SUPER_ADMIN))
                .doesNotThrowAnyException();

        verify(editionPolicy, never()).require(Feature.EXTERNAL_OWNERS);
    }

    /** An unset flag is an internal account, not an unguarded external one. */
    @Test
    void treatsAnUnsetFlagAsInternal() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(roleRepository.findAllById(any())).thenReturn(List.of());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> userService.createUserDto(request(null), SUPER_ADMIN))
                .doesNotThrowAnyException();

        verify(editionPolicy, never()).require(Feature.EXTERNAL_OWNERS);
    }
}
