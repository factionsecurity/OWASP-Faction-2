package com.faction.clientportal.model;

public enum ApplicationType {
    WEB("Web Application"),
    MOBILE("Mobile Application"),
    API("API"),
    DESKTOP("Desktop Application"),
    CLOUD("Cloud Service"),
    IOT("IoT Device"),
    OTHER("Other");

    private final String displayName;

    ApplicationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
