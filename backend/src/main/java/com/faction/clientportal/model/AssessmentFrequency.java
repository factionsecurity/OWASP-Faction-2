package com.faction.clientportal.model;

public enum AssessmentFrequency {
    QUARTERLY("Quarterly"),
    YEARLY("Yearly"),
    AD_HOC("Ad-Hoc"),
    CUSTOM("Custom");

    private final String displayName;

    AssessmentFrequency(String displayName) {
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
