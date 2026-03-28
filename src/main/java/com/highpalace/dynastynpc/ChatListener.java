package com.highpalace.dynastynpc;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player chat near NPCs and triggers LLM responses.
 */
public class ChatListener implements Listener {

    private final DynastyNPC plugin;
    private final OllamaClient ollama;
    private final NPCManager npcManager;
    private final MemoryManager memoryManager;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ChatListener(DynastyNPC plugin, OllamaClient ollama, NPCManager npcManager, MemoryManager memoryManager) {
        this.plugin = plugin;
        this.ollama = ollama;
        this.npcManager = npcManager;
        this.memoryManager = memoryManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Check if player is near an NPC
        NPCManager.NPCData nearbyNPC = npcManager.findNearestNPC(player.getLocation());
        if (nearbyNPC == null) return;

        // Cooldown check
        long cooldownMs = plugin.getConfig().getInt("npc.cooldown", 3) * 1000L;
        Long lastChat = cooldowns.get(player.getUniqueId());
        if (lastChat != null && System.currentTimeMillis() - lastChat < cooldownMs) {
            return; // Silently ignore, don't spam
        }
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        // Get conversation history from memory manager
        List<Map<String, String>> history = memoryManager.getRecentHistory(
                player.getName(), nearbyNPC.id);

        // Show "thinking" indicator
        player.sendMessage(Component.text("")
                .append(Component.text(stripColorCodes(nearbyNPC.name), NamedTextColor.GOLD))
                .append(Component.text(" is thinking...", NamedTextColor.GRAY)));

        // Build system prompt with context and memory
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append(nearbyNPC.personality);
        systemPrompt.append("\n\nThe player's name is ").append(player.getName()).append(".");
        systemPrompt.append("\nYou are in the world of High Palace - Center Kingdom, a Minecraft server set in Song Dynasty China.");
        systemPrompt.append("\n\nRules:");
        systemPrompt.append("\n- Directly answer or respond to what the player just said. Do not ignore their question.");
        systemPrompt.append("\n- Keep your entire response to 1-2 sentences. Finish your thought — do not trail off.");
        systemPrompt.append("\n- Stay in character. Do NOT use markdown, asterisks, or formatting.");
        systemPrompt.append("\n- Do NOT break character or mention that you are an AI.");
        systemPrompt.append("\n- If you do not know something, say so in character rather than making up facts.");
        systemPrompt.append("\n- You can reference Minecraft gameplay (crafting, mobs, biomes, ores) in character.");

        // Inject long-term memory if available
        String memorySummary = memoryManager.getMemorySummary(player.getName(), nearbyNPC.id);
        if (memorySummary != null && !memorySummary.isEmpty()) {
            systemPrompt.append("\n\nYour memory of past conversations with ").append(player.getName()).append(":\n");
            systemPrompt.append(memorySummary);
        }

        // Send to LLM asynchronously with NPC ID for metrics
        ollama.chat(systemPrompt.toString(), message, history, nearbyNPC.id).thenAccept(response -> {
            // Record in persistent memory
            memoryManager.recordMessage(player.getName(), nearbyNPC.id, "user", message);
            memoryManager.recordMessage(player.getName(), nearbyNPC.id, "assistant", response);

            // Async save to disk
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                memoryManager.saveMemory(player.getName(), nearbyNPC.id);
            });

            // Send response to player on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Send to the player who spoke
                player.sendMessage(Component.text("")
                        .append(Component.text("[" + stripColorCodes(nearbyNPC.name) + "] ", NamedTextColor.GOLD))
                        .append(Component.text(response, NamedTextColor.WHITE)));

                // Also send to nearby players so they can see the conversation
                double radius = plugin.getConfig().getDouble("npc.talk-radius", 10);
                for (org.bukkit.entity.Player nearby : player.getWorld().getPlayers()) {
                    if (nearby.equals(player)) continue;
                    if (nearbyNPC.location != null &&
                            nearby.getLocation().distance(nearbyNPC.location) <= radius) {
                        nearby.sendMessage(Component.text("")
                                .append(Component.text("[" + stripColorCodes(nearbyNPC.name) + "] ", NamedTextColor.GOLD))
                                .append(Component.text("(to " + player.getName() + ") ", NamedTextColor.GRAY))
                                .append(Component.text(response, NamedTextColor.WHITE)));
                    }
                }
            });
        });
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "");
    }
}
