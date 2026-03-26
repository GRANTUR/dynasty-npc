package com.highpalace.dynastynpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Manages persistent NPC-player conversation memory.
 *
 * Three tiers:
 *   1. Short-term buffer (last N messages) — sent as LLM message history
 *   2. Persistent log (all messages on disk) — survives restarts
 *   3. Long-term summary (LLM-generated) — injected into system prompt
 *
 * Files stored at: plugins/DynastyNPC/memory/{npcId}/{playerName}.json
 */
public class MemoryManager {

    private final DynastyNPC plugin;
    private final OllamaClient ollama;
    private final MetricsCollector metrics;
    private final Logger logger;
    private final Gson gson;
    private final File memoryDir;

    // In-memory cache of loaded memories
    private final Map<String, PlayerMemory> cache = new ConcurrentHashMap<>();
    // Per-key locks to prevent concurrent file writes
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final int maxRecentMessages;
    private final int summarizeAfter;
    private final int maxSummaryLength;

    public MemoryManager(DynastyNPC plugin, OllamaClient ollama, MetricsCollector metrics) {
        this.plugin = plugin;
        this.ollama = ollama;
        this.metrics = metrics;
        this.logger = plugin.getLogger();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.memoryDir = new File(plugin.getDataFolder(), "memory");

        this.maxRecentMessages = plugin.getConfig().getInt("memory.max-recent-messages", 6);
        this.summarizeAfter = plugin.getConfig().getInt("memory.summarize-after", 20);
        this.maxSummaryLength = plugin.getConfig().getInt("memory.max-summary-length", 500);

        if (!memoryDir.exists()) {
            memoryDir.mkdirs();
        }
    }

    /**
     * Get the memory summary for injection into the system prompt.
     */
    public String getMemorySummary(String playerName, String npcId) {
        PlayerMemory memory = getOrLoadMemory(playerName, npcId);
        if (memory.memorySummary != null && !memory.memorySummary.isEmpty()) {
            return memory.memorySummary;
        }
        return null;
    }

    /**
     * Get recent conversation history (last N messages) for the LLM context.
     */
    public List<Map<String, String>> getRecentHistory(String playerName, String npcId) {
        PlayerMemory memory = getOrLoadMemory(playerName, npcId);
        List<MemoryEntry> entries = memory.conversations;
        int start = Math.max(0, entries.size() - maxRecentMessages);
        List<Map<String, String>> recent = new ArrayList<>();
        for (int i = start; i < entries.size(); i++) {
            Map<String, String> msg = new HashMap<>();
            msg.put("role", entries.get(i).role);
            msg.put("content", entries.get(i).content);
            recent.add(msg);
        }
        return recent;
    }

    /**
     * Record a new message in the conversation log.
     */
    public void recordMessage(String playerName, String npcId, String role, String content) {
        PlayerMemory memory = getOrLoadMemory(playerName, npcId);
        MemoryEntry entry = new MemoryEntry();
        entry.timestamp = System.currentTimeMillis();
        entry.role = role;
        entry.content = content;
        memory.conversations.add(entry);
        memory.dirty = true;
    }

    /**
     * Save a specific player-NPC memory to disk.
     */
    public void saveMemory(String playerName, String npcId) {
        String key = key(playerName, npcId);
        PlayerMemory memory = cache.get(key);
        if (memory == null || !memory.dirty) return;

        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            File npcDir = new File(memoryDir, npcId);
            if (!npcDir.exists()) npcDir.mkdirs();
            File file = new File(npcDir, playerName + ".json");

            // Build saveable structure
            MemoryFile mf = new MemoryFile();
            mf.npcId = npcId;
            mf.playerName = playerName;
            mf.memorySummary = memory.memorySummary;
            mf.lastSummaryTimestamp = memory.lastSummaryTimestamp;
            mf.conversations = memory.conversations;

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(mf, writer);
            }
            memory.dirty = false;
        } catch (IOException e) {
            logger.severe("Failed to save memory for " + playerName + ":" + npcId + " — " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Save all dirty memories to disk.
     */
    public void saveAll() {
        for (Map.Entry<String, PlayerMemory> entry : cache.entrySet()) {
            if (entry.getValue().dirty) {
                String[] parts = entry.getKey().split(":", 2);
                saveMemory(parts[0], parts[1]);
            }
        }
    }

    /**
     * Run summarization for all conversations that have accumulated enough messages.
     * Should be called from an async task.
     */
    public void runSummarization() {
        for (Map.Entry<String, PlayerMemory> entry : cache.entrySet()) {
            PlayerMemory memory = entry.getValue();
            int unsummarized = memory.conversations.size();

            if (unsummarized >= summarizeAfter) {
                String[] parts = entry.getKey().split(":", 2);
                String playerName = parts[0];
                String npcId = parts[1];

                try {
                    summarizeMemory(playerName, npcId, memory);
                } catch (Exception e) {
                    logger.warning("Failed to summarize memory for " + playerName + ":" + npcId + " — " + e.getMessage());
                }
            }
        }
    }

    /**
     * Summarize a conversation and update the memory.
     */
    private void summarizeMemory(String playerName, String npcId, PlayerMemory memory) {
        // Build conversation text for summarization
        StringBuilder convText = new StringBuilder();
        if (memory.memorySummary != null && !memory.memorySummary.isEmpty()) {
            convText.append("Previous summary: ").append(memory.memorySummary).append("\n\n");
        }
        convText.append("Recent conversation:\n");
        for (MemoryEntry e : memory.conversations) {
            String speaker = e.role.equals("user") ? playerName : "NPC";
            convText.append(speaker).append(": ").append(e.content).append("\n");
        }

        String summarizePrompt = "Summarize the following conversation between a player named " + playerName +
                " and an NPC. Focus on: key facts learned, promises made, quests discussed, " +
                "relationship tone, and anything the NPC should remember. " +
                "Keep it under " + maxSummaryLength + " characters. " +
                "Write in third person past tense. Do NOT use markdown.";

        try {
            String summary = ollama.summarize(summarizePrompt, convText.toString()).get();

            if (summary != null && !summary.isEmpty() && !summary.startsWith("§c")) {
                memory.memorySummary = summary;
                memory.lastSummaryTimestamp = System.currentTimeMillis();

                // Keep only the most recent messages after summarization
                int keepCount = maxRecentMessages;
                if (memory.conversations.size() > keepCount) {
                    memory.conversations = new ArrayList<>(
                            memory.conversations.subList(memory.conversations.size() - keepCount, memory.conversations.size()));
                }
                memory.dirty = true;

                // Save immediately after summarization
                String[] parts = key(playerName, npcId).split(":", 2);
                saveMemory(parts[0], parts[1]);

                if (metrics != null) {
                    metrics.recordMemorySummary(npcId);
                }

                logger.info("Summarized memory for " + playerName + ":" + npcId +
                        " (" + summary.length() + " chars)");
            }
        } catch (Exception e) {
            logger.warning("Summarization failed for " + playerName + ":" + npcId + " — " + e.getMessage());
        }
    }

    /**
     * Clear memory for a player-NPC pair.
     */
    public void clearMemory(String playerName, String npcId) {
        String key = key(playerName, npcId);
        cache.remove(key);
        File file = new File(new File(memoryDir, npcId), playerName + ".json");
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * Get memory info string for admin commands.
     */
    public String getMemoryInfo(String playerName, String npcId) {
        PlayerMemory memory = getOrLoadMemory(playerName, npcId);
        StringBuilder sb = new StringBuilder();
        sb.append("Messages: ").append(memory.conversations.size());
        sb.append(" | Summary: ").append(memory.memorySummary != null ? memory.memorySummary.length() + " chars" : "none");
        if (memory.memorySummary != null) {
            sb.append("\n§7").append(memory.memorySummary);
        }
        return sb.toString();
    }

    // --- Internal ---

    private PlayerMemory getOrLoadMemory(String playerName, String npcId) {
        String key = key(playerName, npcId);
        return cache.computeIfAbsent(key, k -> loadFromDisk(playerName, npcId));
    }

    private PlayerMemory loadFromDisk(String playerName, String npcId) {
        File file = new File(new File(memoryDir, npcId), playerName + ".json");
        if (file.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                MemoryFile mf = gson.fromJson(reader, MemoryFile.class);
                if (mf != null) {
                    PlayerMemory memory = new PlayerMemory();
                    memory.memorySummary = mf.memorySummary;
                    memory.lastSummaryTimestamp = mf.lastSummaryTimestamp;
                    memory.conversations = mf.conversations != null ? new ArrayList<>(mf.conversations) : new ArrayList<>();
                    return memory;
                }
            } catch (Exception e) {
                logger.warning("Failed to load memory from " + file.getPath() + " — " + e.getMessage());
            }
        }
        return new PlayerMemory();
    }

    private String key(String playerName, String npcId) {
        return playerName + ":" + npcId;
    }

    // --- Data classes ---

    private static class PlayerMemory {
        String memorySummary;
        long lastSummaryTimestamp;
        List<MemoryEntry> conversations = new ArrayList<>();
        transient boolean dirty = false;
    }

    static class MemoryEntry {
        long timestamp;
        String role;
        String content;
    }

    private static class MemoryFile {
        String npcId;
        String playerName;
        String memorySummary;
        long lastSummaryTimestamp;
        List<MemoryEntry> conversations;
    }
}
