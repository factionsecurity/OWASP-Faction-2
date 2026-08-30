package com.faction.clientportal.model;

/**
 * Supported AI provider families. OPENAI_COMPATIBLE covers any endpoint that
 * implements the OpenAI REST API shape (Ollama, LM Studio, vLLM, LiteLLM, etc.).
 */
public enum AiProviderType {
    OPENAI("https://api.openai.com/v1"),
    ANTHROPIC("https://api.anthropic.com/v1"),
    OPENROUTER("https://openrouter.ai/api/v1"),
    /** Base URL is the Azure resource endpoint, e.g. https://my-resource.openai.azure.com */
    AZURE_OPENAI(null),
    OPENAI_COMPATIBLE(null);

    private final String defaultBaseUrl;

    AiProviderType(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** Providers with no default base URL require the admin to supply one. */
    public boolean requiresBaseUrl() {
        return defaultBaseUrl == null;
    }
}
