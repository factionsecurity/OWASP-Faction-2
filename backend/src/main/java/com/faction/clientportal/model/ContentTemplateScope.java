package com.faction.clientportal.model;

/**
 * Where a content template is offered in the UI. Mirrors {@link AiPromptScope} — the
 * template picker sits next to the AI menu in the same rich text editors.
 * ASSESSMENT: user-defined rich text fields on the assessment itself.
 * VULNERABILITY: rich text editors on vulnerability add/edit forms.
 */
public enum ContentTemplateScope {
    ASSESSMENT,
    VULNERABILITY
}
