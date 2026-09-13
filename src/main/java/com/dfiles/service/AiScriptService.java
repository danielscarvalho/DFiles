package com.dfiles.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Turns a plain-English instruction into a bash script using an OpenAI-compatible Chat
 * Completions API. Two backends are supported via {@link AiProvider}: OpenAI's own hosted API,
 * and a local <a href="https://github.com/Mozilla-Ocho/llamafile">Llamafile</a> server — both
 * speak the same request/response JSON shape, so one implementation serves either.
 *
 * <p>The OpenAI API key is read from the {@code OPENAI_API_KEY} environment variable at call
 * time only; it is never stored, cached or logged. The JDK's own {@link HttpClient} is used, so
 * no extra HTTP dependency is needed.
 */
public final class AiScriptService {

    private static final Logger LOGGER = LogManager.getLogger(AiScriptService.class);

    private static final String SYSTEM_PROMPT =
            "You are a bash scripting assistant. Given an instruction, respond with ONLY a complete, " +
            "safe bash script that accomplishes it. Start the script with a #!/usr/bin/env bash shebang " +
            "line and add brief comments for non-obvious steps. Do not wrap the script in markdown code " +
            "fences and do not include any explanation before or after the script — output the script " +
            "content only.";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private AiScriptService() {}

    /**
     * Requests a bash script for {@code prompt} from {@code provider}. Blocking — callers must
     * run this off the JavaFX Application Thread.
     *
     * @param prompt   the user's plain-English description of the script they want
     * @param provider which backend to call
     * @return the generated script text, with any markdown code fences stripped
     * @throws IOException if the provider is unreachable, returns a non-200 response, or (for
     *                      {@link AiProvider#OPENAI}) no API key is configured
     * @throws InterruptedException if the calling thread is interrupted while waiting on the request
     */
    public static String generateScript(String prompt, AiProvider provider) throws IOException, InterruptedException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (provider.requiresApiKey() && (apiKey == null || apiKey.isBlank())) {
            throw new IOException("OPENAI_API_KEY environment variable is not set");
        }

        JSONObject body = new JSONObject()
                .put("model", provider.getModel())
                .put("temperature", 0.3)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(new JSONObject().put("role", "user").put("content", prompt)));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(provider.getEndpoint()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (provider.requiresApiKey()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        LOGGER.info("Requesting script generation from {} ({} chars prompt)", provider.getDisplayName(), prompt.length());
        HttpResponse<String> response;
        try {
            response = CLIENT.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException(provider == AiProvider.LOCAL_LLAMAFILE
                    ? "Could not reach the local Llamafile server at " + provider.getEndpoint() + " — is it running?"
                    : "Could not reach " + provider.getDisplayName() + ": " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            LOGGER.error("{} API returned status {}: {}", provider.getDisplayName(), response.statusCode(), response.body());
            throw new IOException(describeError(provider, response.statusCode(), response.body()));
        }

        JSONObject json = new JSONObject(response.body());
        String content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        return stripMarkdownFences(content);
    }

    private static String describeError(AiProvider provider, int statusCode, String body) {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                Object error = json.get("error");
                String message = error instanceof JSONObject
                        ? ((JSONObject) error).optString("message", body)
                        : String.valueOf(error);
                return provider.getDisplayName() + " API error (" + statusCode + "): " + message;
            }
        } catch (Exception ignored) {
            // fall through to the raw body below
        }
        return provider.getDisplayName() + " API error (" + statusCode + "): " + body;
    }

    private static String stripMarkdownFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.strip();
    }
}
