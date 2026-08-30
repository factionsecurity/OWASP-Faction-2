package com.faction.clientportal.model;

import lombok.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;

@Entity
@Table(name = "email_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailConfig {

    @Id
    @Builder.Default
    private String id = "singleton";

    @Builder.Default
    private boolean enabled = false;

    /** Provider preset: CUSTOM, GMAIL, OUTLOOK, OFFICE365, YAHOO, SENDGRID */
    @Builder.Default
    private String provider = "CUSTOM";

    private String host;

    @Builder.Default
    private int port = 587;

    private String username;

    /** AES-GCM encrypted SMTP password */
    private String encryptedPassword;

    private String fromName;
    private String fromEmail;

    /** Optional custom logo stored as base64 (embedded inline in emails). */
    private String logoBase64;
    /** MIME type of the custom logo, e.g. image/png */
    private String logoMimeType;

    /** TLS mode: NONE, STARTTLS, SSL_TLS */
    @Builder.Default
    private String security = "STARTTLS";

    @Builder.Default
    private boolean authEnabled = true;
}
