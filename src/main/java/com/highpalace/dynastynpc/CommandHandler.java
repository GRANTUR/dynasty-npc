package com.highpalace.dynastynpc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Command handler for /dynastynpc and /ask commands.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    private final DynastyNPC plugin;
    private final NPCManager npcManager;
    private final MemoryManager memoryManager;

    public CommandHandler(DynastyNPC plugin, NPCManager npcManager, MemoryManager memoryManager) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        this.memoryManager = memoryManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /ask <message> — talk to nearest NPC directly
        if (command.getName().equalsIgnoreCase("ask")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can talk to NPCs.");
                return true;
            }

            if (args.length == 0) {
                player.sendMessage(Component.text("Usage: /ask <message>", NamedTextColor.RED));
                return true;
            }

            NPCManager.NPCData nearbyNPC = npcManager.findNearestNPC(player.getLocation());
            if (nearbyNPC == null) {
                player.sendMessage(Component.text("There are no NPCs nearby to talk to.", NamedTextColor.RED));
                return true;
            }

            String message = String.join(" ", args);

            player.sendMessage(Component.text("")
                    .append(Component.text(stripColorCodes(nearbyNPC.name), NamedTextColor.GOLD))
                    .append(Component.text(" is thinking...", NamedTextColor.GRAY)));

            // Build system prompt with memory
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

            // Inject long-term memory
            String memorySummary = memoryManager.getMemorySummary(player.getName(), nearbyNPC.id);
            if (memorySummary != null && !memorySummary.isEmpty()) {
                systemPrompt.append("\n\nYour memory of past conversations with ").append(player.getName()).append(":\n");
                systemPrompt.append(memorySummary);
            }

            List<Map<String, String>> history = memoryManager.getRecentHistory(
                    player.getName(), nearbyNPC.id);

            plugin.getOllamaClient().chat(systemPrompt.toString(), message, history, nearbyNPC.id).thenAccept(response -> {
                memoryManager.recordMessage(player.getName(), nearbyNPC.id, "user", message);
                memoryManager.recordMessage(player.getName(), nearbyNPC.id, "assistant", response);

                // Async save
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    memoryManager.saveMemory(player.getName(), nearbyNPC.id);
                });

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(Component.text("")
                            .append(Component.text("[" + stripColorCodes(nearbyNPC.name) + "] ", NamedTextColor.GOLD))
                            .append(Component.text(response, NamedTextColor.WHITE)));
                });
            });

            return true;
        }

        // /dynastynpc commands (admin)
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "list" -> {
                sender.sendMessage(Component.text("=== Dynasty NPCs ===", NamedTextColor.GOLD));
                for (Map.Entry<String, NPCManager.NPCData> entry : npcManager.getAllNPCs().entrySet()) {
                    NPCManager.NPCData npc = entry.getValue();
                    String locStr = npc.location != null ?
                            String.format("at %.0f, %.0f, %.0f",
                                    npc.location.getX(), npc.location.getY(), npc.location.getZ()) :
                            "§cno location set";
                    sender.sendMessage(Component.text("  " + npc.name + " §7(" + entry.getKey() + ") " + locStr));
                }
            }

            case "setloc" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can set NPC locations.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /dnpc setloc <npc-id>", NamedTextColor.RED));
                    return true;
                }
                String npcId = args[1].toLowerCase();
                NPCManager.NPCData npc = npcManager.getNPC(npcId);
                if (npc == null) {
                    sender.sendMessage(Component.text("NPC '" + npcId + "' not found.", NamedTextColor.RED));
                    return true;
                }
                npc.location = player.getLocation();
                npcManager.saveNPCs();
                npcManager.spawnNPCEntity(npc);
                sender.sendMessage(Component.text("Set " + npc.name + " §alocation to your position!", NamedTextColor.GREEN));
            }

            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can create NPCs.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /dnpc create <id> <name>", NamedTextColor.RED));
                    return true;
                }
                String newId = args[1].toLowerCase();
                String newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                NPCManager.NPCData newNpc = new NPCManager.NPCData();
                newNpc.id = newId;
                newNpc.name = newName;
                newNpc.personality = "You are " + newName + ", a character in the Center Kingdom. Respond in character. Keep responses under 2 sentences.";
                newNpc.location = player.getLocation();
                npcManager.addNPC(newId, newNpc);
                npcManager.spawnNPCEntity(newNpc);
                sender.sendMessage(Component.text("Created NPC '" + newName + "' at your location!", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Edit personality in plugins/DynastyNPC/npcs.yml", NamedTextColor.GRAY));
            }

            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /dnpc remove <id>", NamedTextColor.RED));
                    return true;
                }
                String removeId = args[1].toLowerCase();
                NPCManager.NPCData removeNpc = npcManager.getNPC(removeId);
                if (removeNpc == null) {
                    sender.sendMessage(Component.text("NPC '" + removeId + "' not found.", NamedTextColor.RED));
                    return true;
                }
                npcManager.despawnNPCEntity(removeNpc);
                npcManager.removeNPC(removeId);
                sender.sendMessage(Component.text("Removed NPC '" + removeId + "'.", NamedTextColor.GREEN));
            }

            case "memory" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /dnpc memory <npc-id> <player>", NamedTextColor.RED));
                    sender.sendMessage(Component.text("       /dnpc memory <npc-id> <player> clear", NamedTextColor.RED));
                    return true;
                }
                String memNpcId = args[1].toLowerCase();
                String memPlayer = args[2];

                if (args.length >= 4 && args[3].equalsIgnoreCase("clear")) {
                    memoryManager.clearMemory(memPlayer, memNpcId);
                    sender.sendMessage(Component.text("Cleared memory for " + memPlayer + " <-> " + memNpcId, NamedTextColor.GREEN));
                } else {
                    String info = memoryManager.getMemoryInfo(memPlayer, memNpcId);
                    sender.sendMessage(Component.text("Memory [" + memPlayer + " <-> " + memNpcId + "]: ", NamedTextColor.GOLD)
                            .append(Component.text(info, NamedTextColor.GRAY)));
                }
            }

            case "forgetme" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }
                for (String npcId : npcManager.getAllNPCs().keySet()) {
                    memoryManager.clearMemory(player.getName(), npcId);
                }
                sender.sendMessage(Component.text("All NPCs have forgotten you.", NamedTextColor.GREEN));
            }

            case "reload" -> {
                npcManager.despawnAllNPCs();
                plugin.reloadConfig();
                npcManager.loadNPCs();
                npcManager.spawnAllNPCs();
                sender.sendMessage(Component.text("DynastyNPC config reloaded!", NamedTextColor.GREEN));
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== DynastyNPC Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/dnpc list", NamedTextColor.YELLOW)
                .append(Component.text(" - List all NPCs", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc setloc <id>", NamedTextColor.YELLOW)
                .append(Component.text(" - Set NPC location to where you stand", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc create <id> <name>", NamedTextColor.YELLOW)
                .append(Component.text(" - Create a new NPC at your location", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc remove <id>", NamedTextColor.YELLOW)
                .append(Component.text(" - Remove an NPC", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc memory <npc> <player> [clear]", NamedTextColor.YELLOW)
                .append(Component.text(" - View/clear NPC memory", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc forgetme", NamedTextColor.YELLOW)
                .append(Component.text(" - Make all NPCs forget you", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/dnpc reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload config", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ask <message>", NamedTextColor.YELLOW)
                .append(Component.text(" - Talk to the nearest NPC", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("dynastynpc")) {
            if (args.length == 1) {
                return filterStartsWith(args[0], "list", "setloc", "create", "remove", "memory", "forgetme", "reload");
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("setloc") ||
                    args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("memory"))) {
                return filterStartsWith(args[1], npcManager.getAllNPCs().keySet().toArray(new String[0]));
            }
        }
        return List.of();
    }

    private List<String> filterStartsWith(String input, String... options) {
        List<String> result = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(input.toLowerCase())) {
                result.add(opt);
            }
        }
        return result;
    }

    private String stripColorCodes(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "");
    }
}
