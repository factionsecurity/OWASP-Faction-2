package com.faction.clientportal.dto;

import com.faction.clientportal.model.EmailConfig;
import lombok.Data;

@Data
public class EmailConfigDto {

    private static final String MASKED = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

    private boolean enabled;
    private String provider;
    private String host;
    private int port;
    private String username;
    private String password; // null when not set, MASKED when encrypted value is stored
    private String fromName;
    private String fromEmail;
    private String security;
    private boolean authEnabled;
    private String logoBase64;
    private String logoMimeType;

    public static EmailConfigDto fromEntity(EmailConfig config) {
        EmailConfigDto dto = new EmailConfigDto();
        dto.setEnabled(config.isEnabled());
        dto.setProvider(config.getProvider());
        dto.setHost(config.getHost());
        dto.setPort(config.getPort());
        dto.setUsername(config.getUsername());
        dto.setPassword(config.getEncryptedPassword() != null && !config.getEncryptedPassword().isBlank()
                ? MASKED : null);
        dto.setFromName(config.getFromName());
        dto.setFromEmail(config.getFromEmail());
        dto.setSecurity(config.getSecurity());
        dto.setAuthEnabled(config.isAuthEnabled());
        dto.setLogoBase64(config.getLogoBase64());
        dto.setLogoMimeType(config.getLogoMimeType());
        return dto;
    }
}
