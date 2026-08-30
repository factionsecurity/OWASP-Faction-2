package com.faction.clientportal.dto;

import lombok.Data;

@Data
public class UpdateEmailConfigRequest {
    private Boolean enabled;
    private String provider;
    private String host;
    private Integer port;
    private String username;
    /** Null or "••••••••" keeps existing password; any other value is treated as new plaintext. */
    private String password;
    private String fromName;
    private String fromEmail;
    private String security;
    private Boolean authEnabled;
    /** Base64-encoded logo image. Empty string clears the logo; null leaves it unchanged. */
    private String logoBase64;
    private String logoMimeType;
}
