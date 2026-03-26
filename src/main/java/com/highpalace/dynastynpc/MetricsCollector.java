package com.highpalace.dynastynpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe collector for LLM inference metrics.
 * Tracks request counts, response times, tokens, and errors
 * in a format ready for Prometheus exposition.
 */
public class MetricsCollector {

    // Histogram buckets for response time (seconds)
    private static final double[] BUCKETS = {1, 2, 5, 10, 15, 20, 30, 60};

    // --- Counters ---
    // Key: "npc_id:status" e.g. "scholar-wu:success"
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    // Key: "npc_id:error_type"
    private final Map<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    // Key: "npc_id"
    private final Map<String, AtomicLong> tokenCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> conversationCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> memorySummaryCounts = new ConcurrentHashMap<>();

    // --- Histogram data per NPC ---
    private final Map<String, long[]> histogramBuckets = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> histogramCounts = new ConcurrentHashMap<>();
    private final Map<String, DoubleAdder> histogramSums = new ConcurrentHashMap<>();

    // --- Gauge ---
    private final AtomicLong activeRequests = new AtomicLong(0);

    // Model info
    private volatile String modelName = "unknown";

    // Simple thread-safe double accumulator
    public static class DoubleAdder {
        private final AtomicLong bits = new AtomicLong(0);

        public void add(double value) {
            long prev, next;
            do {
                prev = bits.get();
                next = Double.doubleToLongBits(Double.longBitsToDouble(prev) + value);
            } while (!bits.compareAndSet(prev, next));
        }

        public double sum() {
            return Double.longBitsToDouble(bits.get());
        }
    }

    public void setModelName(String model) {
        this.modelName = model;
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }

    public void recordRequest(String npcId, String status) {
        String key = npcId + ":" + status;
        requestCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordError(String npcId, String errorType) {
        String key = npcId + ":" + errorType;
        errorCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordTokens(String npcId, long tokens) {
        tokenCounts.computeIfAbsent(npcId, k -> new AtomicLong(0)).addAndGet(tokens);
    }

    public void recordConversation(String npcId) {
        conversationCounts.computeIfAbsent(npcId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordMemorySummary(String npcId) {
        memorySummaryCounts.computeIfAbsent(npcId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordResponseTime(String npcId, double seconds) {
        // Update histogram buckets
        long[] buckets = histogramBuckets.computeIfAbsent(npcId, k -> new long[BUCKETS.length]);
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                synchronized (buckets) {
                    buckets[i]++;
                }
            }
        }
        histogramCounts.computeIfAbsent(npcId, k -> new AtomicLong(0)).incrementAndGet();
        histogramSums.computeIfAbsent(npcId, k -> new DoubleAdder()).add(seconds);
    }

    /**
     * Render all metrics in Prometheus text exposition format.
     */
    public String renderMetrics() {
        StringBuilder sb = new StringBuilder();

        // Model info gauge
        sb.append("# HELP dynastynpc_llm_model_info LLM model information\n");
        sb.append("# TYPE dynastynpc_llm_model_info gauge\n");
        sb.append("dynastynpc_llm_model_info{model=\"").append(escape(modelName)).append("\"} 1\n\n");

        // Active requests gauge
        sb.append("# HELP dynastynpc_llm_active_requests Currently in-flight LLM requests\n");
        sb.append("# TYPE dynastynpc_llm_active_requests gauge\n");
        sb.append("dynastynpc_llm_active_requests ").append(activeRequests.get()).append("\n\n");

        // Request counter
        sb.append("# HELP dynastynpc_llm_requests_total Total LLM requests\n");
        sb.append("# TYPE dynastynpc_llm_requests_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : requestCounts.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            sb.append("dynastynpc_llm_requests_total{npc_id=\"").append(escape(parts[0]))
              .append("\",status=\"").append(escape(parts[1])).append("\"} ")
              .append(entry.getValue().get()).append("\n");
        }
        sb.append("\n");

        // Error counter
        sb.append("# HELP dynastynpc_llm_errors_total Total LLM errors\n");
        sb.append("# TYPE dynastynpc_llm_errors_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : errorCounts.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            sb.append("dynastynpc_llm_errors_total{npc_id=\"").append(escape(parts[0]))
              .append("\",error_type=\"").append(escape(parts[1])).append("\"} ")
              .append(entry.getValue().get()).append("\n");
        }
        sb.append("\n");

        // Tokens counter
        sb.append("# HELP dynastynpc_llm_tokens_generated_total Total tokens generated\n");
        sb.append("# TYPE dynastynpc_llm_tokens_generated_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : tokenCounts.entrySet()) {
            sb.append("dynastynpc_llm_tokens_generated_total{npc_id=\"").append(escape(entry.getKey()))
              .append("\"} ").append(entry.getValue().get()).append("\n");
        }
        sb.append("\n");

        // Conversations counter
        sb.append("# HELP dynastynpc_conversations_total Total conversation exchanges\n");
        sb.append("# TYPE dynastynpc_conversations_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : conversationCounts.entrySet()) {
            sb.append("dynastynpc_conversations_total{npc_id=\"").append(escape(entry.getKey()))
              .append("\"} ").append(entry.getValue().get()).append("\n");
        }
        sb.append("\n");

        // Memory summary counter
        sb.append("# HELP dynastynpc_memory_summaries_total Memory summarization runs\n");
        sb.append("# TYPE dynastynpc_memory_summaries_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : memorySummaryCounts.entrySet()) {
            sb.append("dynastynpc_memory_summaries_total{npc_id=\"").append(escape(entry.getKey()))
              .append("\"} ").append(entry.getValue().get()).append("\n");
        }
        sb.append("\n");

        // Response time histogram
        sb.append("# HELP dynastynpc_llm_response_seconds LLM response time in seconds\n");
        sb.append("# TYPE dynastynpc_llm_response_seconds histogram\n");
        for (Map.Entry<String, long[]> entry : histogramBuckets.entrySet()) {
            String npcId = entry.getKey();
            long[] buckets = entry.getValue();
            long cumulative = 0;
            synchronized (buckets) {
                for (int i = 0; i < BUCKETS.length; i++) {
                    cumulative += buckets[i];
                    sb.append("dynastynpc_llm_response_seconds_bucket{npc_id=\"").append(escape(npcId))
                      .append("\",le=\"").append(formatDouble(BUCKETS[i])).append("\"} ")
                      .append(cumulative).append("\n");
                }
            }
            long totalCount = histogramCounts.getOrDefault(npcId, new AtomicLong(0)).get();
            sb.append("dynastynpc_llm_response_seconds_bucket{npc_id=\"").append(escape(npcId))
              .append("\",le=\"+Inf\"} ").append(totalCount).append("\n");
            sb.append("dynastynpc_llm_response_seconds_sum{npc_id=\"").append(escape(npcId))
              .append("\"} ").append(formatDouble(histogramSums.getOrDefault(npcId, new DoubleAdder()).sum())).append("\n");
            sb.append("dynastynpc_llm_response_seconds_count{npc_id=\"").append(escape(npcId))
              .append("\"} ").append(totalCount).append("\n");
        }
        sb.append("\n");

        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String formatDouble(double d) {
        if (d == (long) d) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
