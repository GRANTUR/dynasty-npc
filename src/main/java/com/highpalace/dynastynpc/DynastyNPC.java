package com.highpalace.dynastynpc;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * DynastyNPC - AI-powered NPCs for High Palace
 *
 * Connects to a local Ollama instance (via Tailscale VPN) to generate
 * dynamic, in-character NPC dialogue using LLMs.
 *
 * Features:
 *   - Persistent conversation memory across restarts
 *   - LLM-generated memory summaries for long-term context
 *   - Prometheus metrics for AI operations observability
 *   - Visible villager entities with custom names and professions
 */
public class DynastyNPC extends JavaPlugin {

    private OllamaClient ollamaClient;
    private NPCManager npcManager;
    private MemoryManager memoryManager;
    private MetricsCollector metricsCollector;
    private MetricsServer metricsServer;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();

        // Initialize metrics collector
        this.metricsCollector = new MetricsCollector();

        // Initialize Ollama client
        String ollamaHost = getConfig().getString("ollama.host", "http://ollama-relay.minecraft.svc.cluster.local:11434");
        String model = getConfig().getString("ollama.model", "llama2:13b-chat-q4_K_M");
        int maxTokens = getConfig().getInt("ollama.max-tokens", 150);
        int timeout = getConfig().getInt("ollama.timeout", 30);

        this.ollamaClient = new OllamaClient(ollamaHost, model, maxTokens, timeout);
        ollamaClient.setMetrics(metricsCollector);

        // Initialize NPC manager
        this.npcManager = new NPCManager(this);
        npcManager.loadNPCs();

        // Initialize memory manager
        this.memoryManager = new MemoryManager(this, ollamaClient, metricsCollector);

        // Register event listeners
        getServer().getPluginManager().registerEvents(
            new ChatListener(this, ollamaClient, npcManager, memoryManager), this
        );
        getServer().getPluginManager().registerEvents(
            new NPCInteractListener(npcManager), this
        );

        // Register commands
        CommandHandler cmdHandler = new CommandHandler(this, npcManager, memoryManager);
        getCommand("dynastynpc").setExecutor(cmdHandler);
        getCommand("dynastynpc").setTabCompleter(cmdHandler);
        getCommand("ask").setExecutor(cmdHandler);

        // Spawn NPC entities one tick later (after worlds are loaded)
        getServer().getScheduler().runTaskLater(this, () -> {
            npcManager.spawnAllNPCs();
        }, 20L);

        // Start metrics HTTP server
        if (getConfig().getBoolean("metrics.enabled", true)) {
            int metricsPort = getConfig().getInt("metrics.port", 9225);
            try {
                this.metricsServer = new MetricsServer(metricsPort, metricsCollector, getLogger());
                metricsServer.start();
            } catch (Exception e) {
                getLogger().warning("Failed to start metrics server on port " + metricsPort + ": " + e.getMessage());
                getLogger().warning("Metrics will not be available. Check if the port is in use.");
            }
        }

        // Schedule periodic memory summarization (async, every 30 minutes)
        int summarizeInterval = getConfig().getInt("memory.summarize-interval", 1800);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            memoryManager.runSummarization();
        }, 20L * summarizeInterval, 20L * summarizeInterval);

        // Schedule periodic memory saves (async, every 5 minutes)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            memoryManager.saveAll();
        }, 20L * 300, 20L * 300);

        getLogger().info("§6DynastyNPC enabled! Connected to Ollama at " + ollamaHost);
        getLogger().info("§6Using model: " + model);

        // Test connection async
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            if (ollamaClient.testConnection()) {
                getLogger().info("§aOllama connection successful!");
            } else {
                getLogger().warning("§cCould not reach Ollama at " + ollamaHost);
                getLogger().warning("§cNPCs will show error messages until connection is restored.");
            }
        });
    }

    @Override
    public void onDisable() {
        // Stop metrics server
        if (metricsServer != null) {
            metricsServer.stop();
        }

        // Save all memory and despawn entities
        if (memoryManager != null) {
            memoryManager.saveAll();
        }
        if (npcManager != null) {
            npcManager.despawnAllNPCs();
            npcManager.saveNPCs();
        }
        getLogger().info("§6DynastyNPC disabled.");
    }

    public OllamaClient getOllamaClient() {
        return ollamaClient;
    }

    public NPCManager getNpcManager() {
        return npcManager;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
}
