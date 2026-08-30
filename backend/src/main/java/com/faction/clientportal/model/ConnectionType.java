package com.faction.clientportal.model;

import lombok.Getter;

@Getter
public enum ConnectionType {
    DEPENDS_ON("depends on", "supports"),
    USES_API("uses API from", "provides API to"),
    CONSUMES_DATA("consumes data from", "provides data to"),
    AUTHENTICATES_WITH("authenticates with", "authenticates"),
    SHARES_INFRASTRUCTURE("shares infrastructure with", "shares infrastructure with"),
    INTEGRATES_WITH("integrates with", "integrated with");

    private final String forwardLabel;
    private final String reverseLabel;

    ConnectionType(String forwardLabel, String reverseLabel) {
        this.forwardLabel = forwardLabel;
        this.reverseLabel = reverseLabel;
    }
}
