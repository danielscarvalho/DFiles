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
 * Turns a plain-English instruction into a bash script via OpenAI's Chat Completions API.
 * Reads the API key from the OPENAI_API_KEY environment variable at call time (never stored,
 * never logged) and uses the JDK's own HttpClient, so no extra HTTP dependency is needed.
 */
public final class AiScriptService {

    private static final Logger LOGGER = LogManager.getLogger(AiScriptService.class);
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
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

    /** Blocking call — the caller is responsible for running this off the JavaFX Application Thread. */
    public static String generateScript(String prompt) throws IOException, InterruptedException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("OPENAI_API_KEY environment variable is not set");
        }

        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("temperature", 0.3)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(new JSONObject().put("role", "user").put("content", prompt)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        LOGGER.info("Requesting script generation from OpenAI ({} chars prompt)", prompt.length());
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.error("OpenAI API returned status {}: {}", response.statusCode(), response.body());
            throw new IOException(describeError(response.statusCode(), response.body()));
        }

        JSONObject json = new JSONObject(response.body());
        String content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        return stripMarkdownFences(content);
    }

    private static String describeError(int statusCode, String body) {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                return "OpenAI API error (" + statusCode + "): " + json.getJSONObject("error").optString("message", body);
            }
        } catch (Exception ignored) {
            // fall through to the raw body below
        }
        return "OpenAI API error (" + statusCode + "): " + body;
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
