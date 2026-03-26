package com.highpalace.dynastynpc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages NPC data — names, personalities, locations, and conversation history.
 * NPCs are virtual (no entity spawned) — they exist as named locations.
 * Players interact with them via /ask or chat when nearby.
 */
public class NPCManager {

    private final DynastyNPC plugin;
    private final Map<String, NPCData> npcs = new ConcurrentHashMap<>();
    // Conversation history now managed by MemoryManager

    public NPCManager(DynastyNPC plugin) {
        this.plugin = plugin;
    }

    /**
     * Load NPCs from npcs.yml, or create defaults from config.yml
     */
    public void loadNPCs() {
        File npcFile = new File(plugin.getDataFolder(), "npcs.yml");

        if (npcFile.exists()) {
            FileConfiguration npcConfig = YamlConfiguration.loadConfiguration(npcFile);
            ConfigurationSection section = npcConfig.getConfigurationSection("npcs");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection npcSection = section.getConfigurationSection(key);
                    if (npcSection == null) continue;

                    NPCData npc = new NPCData();
                    npc.id = key;
                    npc.name = npcSection.getString("name", key);
                    npc.personality = npcSection.getString("personality", "You are a helpful NPC.");

                    if (npcSection.contains("location")) {
                        String worldName = npcSection.getString("location.world", "world");
                        double x = npcSection.getDouble("location.x");
                        double y = npcSection.getDouble("location.y");
                        double z = npcSection.getDouble("location.z");
                        var world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            npc.location = new Location(world, x, y, z);
                        }
                    }

                    npcs.put(key, npc);
                    plugin.getLogger().info("Loaded NPC: " + npc.name + " (" + key + ")");
                }
            }
        } else {
            // Load defaults from config.yml
            ConfigurationSection defaults = plugin.getConfig().getConfigurationSection("default-npcs");
            if (defaults != null) {
                for (String key : defaults.getKeys(false)) {
                    NPCData npc = new NPCData();
                    npc.id = key;
                    npc.name = defaults.getString(key + ".name", key);
                    npc.personality = defaults.getString(key + ".personality", "You are a helpful NPC.");
                    // No location yet — admin needs to place them with /dnpc create
                    npcs.put(key, npc);
                    plugin.getLogger().info("Created default NPC: " + npc.name + " (use /dnpc setloc " + key + " to place)");
                }
            }
            saveNPCs();
        }

        plugin.getLogger().info("Loaded " + npcs.size() + " NPCs.");
    }

    /**
     * Save all NPCs to npcs.yml
     */
    public void saveNPCs() {
        File npcFile = new File(plugin.getDataFolder(), "npcs.yml");
        FileConfiguration npcConfig = new YamlConfiguration();

        for (Map.Entry<String, NPCData> entry : npcs.entrySet()) {
            String path = "npcs." + entry.getKey();
            NPCData npc = entry.getValue();
            npcConfig.set(path + ".name", npc.name);
            npcConfig.set(path + ".personality", npc.personality);

            if (npc.location != null) {
                npcConfig.set(path + ".location.world", npc.location.getWorld().getName());
                npcConfig.set(path + ".location.x", npc.location.getX());
                npcConfig.set(path + ".location.y", npc.location.getY());
                npcConfig.set(path + ".location.z", npc.location.getZ());
            }
        }

        try {
            npcConfig.save(npcFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save NPCs: " + e.getMessage());
        }
    }

    /**
     * Find the nearest NPC to a location within the talk radius.
     */
    public NPCData findNearestNPC(Location playerLocation) {
        double radius = plugin.getConfig().getDouble("npc.talk-radius", 10);
        NPCData nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (NPCData npc : npcs.values()) {
            if (npc.location == null) continue;
            if (!npc.location.getWorld().equals(playerLocation.getWorld())) continue;

            double dist = npc.location.distance(playerLocation);
            if (dist <= radius && dist < nearestDist) {
                nearest = npc;
                nearestDist = dist;
            }
        }

        return nearest;
    }

    public NPCData getNPC(String id) {
        return npcs.get(id);
    }

    public Map<String, NPCData> getAllNPCs() {
        return Collections.unmodifiableMap(npcs);
    }

    public void addNPC(String id, NPCData npc) {
        npcs.put(id, npc);
        saveNPCs();
    }

    public void removeNPC(String id) {
        npcs.remove(id);
        saveNPCs();
    }

    /**
     * Spawn villager entities for all NPCs that have locations.
     * Must be called from the main thread.
     */
    public void spawnAllNPCs() {
        for (NPCData npc : npcs.values()) {
            spawnNPCEntity(npc);
        }
    }

    /**
     * Spawn a villager entity for an NPC at its location.
     */
    public void spawnNPCEntity(NPCData npc) {
        if (npc.location == null || npc.location.getWorld() == null) return;

        // Remove old entity if it exists
        despawnNPCEntity(npc);

        Villager villager = npc.location.getWorld().spawn(npc.location, Villager.class, v -> {
            // Set the display name using legacy color codes from config
            Component displayName = LegacyComponentSerializer.legacySection().deserialize(npc.name);
            v.customName(displayName);
            v.setCustomNameVisible(true);

            // Make it stay put
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);

            // Set profession based on NPC type for visual variety
            if (npc.id.contains("scholar") || npc.id.contains("wu")) {
                v.setProfession(Villager.Profession.LIBRARIAN);
            } else if (npc.id.contains("merchant") || npc.id.contains("chen")) {
                v.setProfession(Villager.Profession.CARTOGRAPHER);
            } else if (npc.id.contains("general") || npc.id.contains("zhao")) {
                v.setProfession(Villager.Profession.WEAPONSMITH);
            } else {
                v.setProfession(Villager.Profession.NITWIT);
            }
        });

        npc.entityUUID = villager.getUniqueId();
        plugin.getLogger().info("Spawned NPC entity: " + npc.name + " at " +
                String.format("%.0f, %.0f, %.0f", npc.location.getX(), npc.location.getY(), npc.location.getZ()));
    }

    /**
     * Remove an NPC's villager entity from the world.
     */
    public void despawnNPCEntity(NPCData npc) {
        if (npc.entityUUID == null) return;
        if (npc.location == null || npc.location.getWorld() == null) return;

        for (Entity entity : npc.location.getWorld().getEntities()) {
            if (entity.getUniqueId().equals(npc.entityUUID)) {
                entity.remove();
                break;
            }
        }
        npc.entityUUID = null;
    }

    /**
     * Despawn all NPC entities (called on plugin disable).
     */
    public void despawnAllNPCs() {
        for (NPCData npc : npcs.values()) {
            despawnNPCEntity(npc);
        }
    }

    /**
     * NPC data class.
     */
    public static class NPCData {
        public String id;
        public String name;
        public String personality;
        public Location location;
        public UUID entityUUID;
    }
}
