package com.example.colorpartyplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;

public class ColorPartyJoinListener implements Listener {

    private final ColorPartyPlugin plugin;

    public ColorPartyJoinListener(ColorPartyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // If the player is still in the Color Party world, remove them.
        if (player.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) {
            // Teleport them to the overworld using the same coordinates as the Exit bed.
            World overworld = Bukkit.getWorld("world");
            if (overworld != null) {
                Location mainSpawn = new Location(overworld, 59.5, 66, -393.5);
                player.teleport(mainSpawn);
            }
            // Restore their inventory
            plugin.restoreInventory(player);
            // (OPTIONAL DISABLED FOR NOW) send a message to let them know they must rejoin the game via the NPC
            // player.sendMessage("You have been removed from Color Party due to disconnect. Please rejoin via the NPC if you wish to play.");
        }
    }
}
