package com.dfiles.service;

/**
 * The AI backends {@link AiScriptService} can talk to. Both speak the same OpenAI-style
 * Chat Completions request/response shape, so a single service implementation covers both —
 * only the endpoint URL, model name and authentication requirement differ.
 */
public enum AiProvider {

    /** OpenAI's hosted API. Requires the {@code OPENAI_API_KEY} environment variable. */
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", true),

    /** A locally running <a href="https://github.com/Mozilla-Ocho/llamafile">Llamafile</a>
     * server, which exposes an OpenAI-compatible endpoint on localhost and needs no API key. */
    LOCAL_LLAMAFILE("Local (Llamafile)", "http://localhost:8080/v1/chat/completions", "local-model", false);

    private final String displayName;
    private final String endpoint;
    private final String model;
    private final boolean requiresApiKey;

    AiProvider(String displayName, String endpoint, String model, boolean requiresApiKey) {
        this.displayName = displayName;
        this.endpoint = endpoint;
        this.model = model;
        this.requiresApiKey = requiresApiKey;
    }

    public String getDisplayName() { return displayName; }
    public String getEndpoint() { return endpoint; }
    public String getModel() { return model; }
    public boolean requiresApiKey() { return requiresApiKey; }

    /** Looks up a provider by its persisted {@link #name()}, falling back to {@link #OPENAI}
     * if the stored value is missing or no longer recognized (e.g. from an older version). */
    public static AiProvider fromSettingValue(String value) {
        if (value == null) return OPENAI;
        try {
            return AiProvider.valueOf(value);
        } catch (IllegalArgumentException e) {
            return OPENAI;
        }
    }
}
