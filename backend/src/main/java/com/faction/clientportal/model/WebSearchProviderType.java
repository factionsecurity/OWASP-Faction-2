package com.faction.clientportal.model;

/**
 * Supported web search backends for AI tools. Each takes a single API key.
 * TAVILY is LLM-optimized; BRAVE and SERPER return general web results.
 */
public enum WebSearchProviderType {
    BRAVE,
    TAVILY,
    SERPER
}
