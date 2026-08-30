package com.faction.clientportal.dto;

import com.faction.clientportal.model.LoginOption;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private LoginOption loginOption;
    private List<String> roleIds;
    private List<String> teamIds;
    private Boolean isInternal;
    private String organizationId;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime disabledAt;
    private Integer failedLoginAttempts;
    private LocalDateTime lastLogin;
    private String profileImageId;
}
