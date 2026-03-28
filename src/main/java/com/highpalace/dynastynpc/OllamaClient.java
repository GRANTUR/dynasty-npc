package com.highpalace.dynastynpc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for communicating with Ollama's REST API.
 * Runs inference requests asynchronously to avoid blocking the game thread.
 * Instrumented with MetricsCollector for Prometheus observability.
 */
public class OllamaClient {

    private final String host;
    private final String model;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final Gson gson;
    private MetricsCollector metrics;

    public OllamaClient(String host, String model, int maxTokens, int timeoutSeconds) {
        this.host = host;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.gson = new Gson();
    }

    public void setMetrics(MetricsCollector metrics) {
        this.metrics = metrics;
        if (metrics != null) {
            metrics.setModelName(model);
        }
    }

    /**
     * Test if Ollama is reachable.
     */
    public boolean testConnection() {
        try {
            URL url = new URL(host + "/api/tags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send a chat message to Ollama and get a response asynchronously.
     *
     * @param systemPrompt The NPC's personality/system prompt
     * @param playerMessage The player's message
     * @param conversationHistory Recent messages for context
     * @return CompletableFuture with the NPC's response
     */
    public CompletableFuture<String> chat(String systemPrompt, String playerMessage,
                                           List<Map<String, String>> conversationHistory) {
        return chat(systemPrompt, playerMessage, conversationHistory, null);
    }

    /**
     * Send a chat message with NPC ID for metrics tracking.
     */
    public CompletableFuture<String> chat(String systemPrompt, String playerMessage,
                                           List<Map<String, String>> conversationHistory,
                                           String npcId) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            String trackId = npcId != null ? npcId : "unknown";

            if (metrics != null) metrics.incrementActiveRequests();

            try {
                URL url = new URL(host + "/api/chat");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(timeoutSeconds * 1000);
                conn.setReadTimeout(timeoutSeconds * 1000);
                conn.setDoOutput(true);

                // Build messages array
                List<Map<String, String>> messages = new ArrayList<>();

                // System prompt
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                messages.add(systemMsg);

                // Conversation history (last few exchanges)
                if (conversationHistory != null) {
                    messages.addAll(conversationHistory);
                }

                // Current player message
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", playerMessage);
                messages.add(userMsg);

                // Build request body
                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                body.put("stream", false);

                Map<String, Object> options = new HashMap<>();
                options.put("num_predict", maxTokens);
                options.put("temperature", 0.65);
                body.put("options", options);

                String jsonBody = gson.toJson(body);

                // Send request
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                // Read response
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    if (metrics != null) {
                        metrics.recordRequest(trackId, "error");
                        metrics.recordError(trackId, "http_" + responseCode);
                    }
                    return "§c*the NPC seems distracted and doesn't respond*";
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }

                conn.disconnect();

                // Parse response
                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
                String content = jsonResponse.getAsJsonObject("message")
                        .get("content").getAsString().trim();

                // Extract token count from Ollama response
                long tokenCount = 0;
                if (jsonResponse.has("eval_count")) {
                    tokenCount = jsonResponse.get("eval_count").getAsLong();
                }

                // Record metrics
                double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
                if (metrics != null) {
                    metrics.recordRequest(trackId, "success");
                    metrics.recordResponseTime(trackId, elapsed);
                    if (tokenCount > 0) {
                        metrics.recordTokens(trackId, tokenCount);
                    }
                    metrics.recordConversation(trackId);
                }

                // Strip any <think> tags from deepseek-r1 or reasoning models
                content = content.replaceAll("(?s)<think>.*?</think>", "").trim();

                // If response is empty after stripping think tags,
                // the model only generated reasoning with no visible reply
                if (content.isEmpty()) {
                    content = "*nods thoughtfully but says nothing*";
                }

                // Truncate at sentence boundary if too long for chat
                if (content.length() > 300) {
                    content = truncateAtSentence(content, 300);
                }

                return content;

            } catch (java.net.SocketTimeoutException e) {
                double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
                if (metrics != null) {
                    metrics.recordRequest(trackId, "error");
                    metrics.recordError(trackId, "timeout");
                    metrics.recordResponseTime(trackId, elapsed);
                }
                return "§c*the NPC is deep in thought... (timeout)*";
            } catch (Exception e) {
                double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
                if (metrics != null) {
                    metrics.recordRequest(trackId, "error");
                    metrics.recordError(trackId, "connection");
                    metrics.recordResponseTime(trackId, elapsed);
                }
                return "§c*the NPC stares blankly* §7(Error: " + e.getMessage() + ")";
            } finally {
                if (metrics != null) metrics.decrementActiveRequests();
            }
        });
    }

    /**
     * Summarize a conversation for long-term memory.
     */
    public CompletableFuture<String> summarize(String systemPrompt, String conversationText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(host + "/api/chat");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(timeoutSeconds * 1000);
                conn.setReadTimeout(timeoutSeconds * 1000);
                conn.setDoOutput(true);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                messages.add(systemMsg);

                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", conversationText);
                messages.add(userMsg);

                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                body.put("stream", false);

                Map<String, Object> options = new HashMap<>();
                options.put("num_predict", 200);
                options.put("temperature", 0.3); // Low temp for factual summarization
                body.put("options", options);

                String jsonBody = gson.toJson(body);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return "";

                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }
                conn.disconnect();

                JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
                String content = jsonResponse.getAsJsonObject("message")
                        .get("content").getAsString().trim();

                // Strip think tags
                content = content.replaceAll("(?s)<think>.*?</think>", "").trim();

                return content;
            } catch (Exception e) {
                return "";
            }
        });
    }

    /**
     * Truncate text at the last sentence-ending punctuation within maxLen.
     * Falls back to last space if no sentence boundary found.
     */
    private String truncateAtSentence(String text, int maxLen) {
        if (text.length() <= maxLen) return text;

        String sub = text.substring(0, maxLen);

        // Try to cut at last sentence-ending punctuation
        int lastEnd = -1;
        for (int i = sub.length() - 1; i >= 0; i--) {
            char c = sub.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                lastEnd = i + 1;
                break;
            }
        }

        if (lastEnd > maxLen / 3) {
            return sub.substring(0, lastEnd).trim();
        }

        // Fall back to last space
        int lastSpace = sub.lastIndexOf(' ');
        if (lastSpace > maxLen / 3) {
            return sub.substring(0, lastSpace).trim() + "...";
        }

        return sub.trim() + "...";
    }
}
