package com.highpalace.dynastynpc;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.UUID;

/**
 * Prevents players from opening trade menus or damaging NPC villagers.
 */
public class NPCInteractListener implements Listener {

    private final NPCManager npcManager;

    public NPCInteractListener(NPCManager npcManager) {
        this.npcManager = npcManager;
    }

    /**
     * Check if an entity is one of our NPC villagers.
     */
    private boolean isNPCEntity(Entity entity) {
        if (!(entity instanceof Villager)) return false;
        UUID entityId = entity.getUniqueId();
        for (NPCManager.NPCData npc : npcManager.getAllNPCs().values()) {
            if (npc.entityUUID != null && npc.entityUUID.equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isNPCEntity(event.getRightClicked())) {
            event.setCancelled(true); // Prevent trade menu
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (isNPCEntity(event.getEntity())) {
            event.setCancelled(true); // Prevent all damage
        }
    }
}
