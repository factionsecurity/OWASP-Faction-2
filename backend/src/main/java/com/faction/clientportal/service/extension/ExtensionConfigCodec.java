package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Extension;
import com.faction.clientportal.service.EncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes an extension's configuration.
 *
 * <p>The on-disk shape is the one extension authors already declare in their
 * {@code META-INF/resources/config.json}:
 *
 * <pre>
 * {
 *   "Jira Host":    { "type": "text",     "value": "https://yourhost.com" },
 *   "Jira API Key": { "type": "password", "value": "your api key" }
 * }
 * </pre>
 *
 * <p>The whole document is encrypted at rest with the same AES-GCM
 * {@link EncryptionService} that protects SSO and SMTP secrets, because
 * {@code type: "password"} entries routinely hold live API credentials. What
 * reaches the extension at runtime is the flattened {@code key -> value} map its
 * {@code getConfigs()} expects — Faction 1 flattened it the same way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtensionConfigCodec {

    private static final TypeReference<LinkedHashMap<String, Map<String, Object>>> CONFIG_TYPE =
            new TypeReference<>() {};

    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Decrypts and parses the stored config document. Never null. */
    public LinkedHashMap<String, Map<String, Object>> read(Extension extension) {
        String encrypted = extension.getEncryptedConfigs();
        if (encrypted == null || encrypted.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(encryptionService.decrypt(encrypted), CONFIG_TYPE);
        } catch (Exception e) {
            // Message only, never the exception: this parses *decrypted* config, which
            // holds API credentials. Jackson keeps the offending source out of parse
            // errors by default (INCLUDE_SOURCE_IN_LOCATION is off), but that is a
            // default, and a stack trace is one config change away from carrying a
            // secret into the logs.
            log.error("Could not read config for extension '{}': {}",
                    extension.getName(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Encrypts and stores the config document on the entity. */
    public void write(Extension extension, Map<String, Map<String, Object>> config) {
        try {
            extension.setEncryptedConfigs(
                    encryptionService.encrypt(objectMapper.writeValueAsString(config)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not store config for extension " + extension.getName(), e);
        }
    }

    /** Parses a JAR's raw {@code config.json} into the stored shape. */
    public LinkedHashMap<String, Map<String, Object>> parseDeclared(String configJson) {
        if (configJson == null || configJson.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(configJson, CONFIG_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Extension config.json is not valid JSON of the form "
                    + "{\"Key\": {\"type\": \"text\", \"value\": \"default\"}}: " + e.getMessage(), e);
        }
    }

    /**
     * Merges previously configured values onto a freshly parsed declaration.
     * Used on upgrade so bumping an extension's version does not wipe the
     * operator's credentials, while newly declared keys still appear with their
     * defaults and removed keys drop away.
     */
    public LinkedHashMap<String, Map<String, Object>> mergePreservingValues(
            LinkedHashMap<String, Map<String, Object>> declared,
            Map<String, Map<String, Object>> existing) {

        declared.forEach((key, spec) -> {
            Map<String, Object> previous = existing.get(key);
            if (previous != null && previous.containsKey("value")) {
                spec.put("value", previous.get("value"));
            }
        });
        return declared;
    }

    /** Flattens to the {@code key -> value} map handed to {@code BaseInterface.setConfigs}. */
    public Map<String, String> toMap(Extension extension) {
        Map<String, String> flat = new LinkedHashMap<>();
        read(extension).forEach((key, spec) -> {
            Object value = spec == null ? null : spec.get("value");
            flat.put(key, value == null ? "" : value.toString());
        });
        return flat;
    }

    /**
     * The config document with every {@code password} value replaced by a mask —
     * what the admin UI is allowed to see. A blank password stays blank so the UI
     * can tell "not set" from "set but hidden".
     */
    public LinkedHashMap<String, Map<String, Object>> readMasked(Extension extension) {
        LinkedHashMap<String, Map<String, Object>> config = read(extension);
        config.values().forEach(spec -> {
            if (spec == null) return;
            Object value = spec.get("value");
            if ("password".equalsIgnoreCase(String.valueOf(spec.get("type")))
                    && value != null && !value.toString().isBlank()) {
                spec.put("value", MASK);
            }
        });
        return config;
    }

    /** Sentinel the UI echoes back for an unchanged password field. */
    public static final String MASK = "********";
}
