package com.example.colorpartyplugin;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

import com.example.colorpartyplugin.ColorPartyPlugin;

public class ColorPartyDisconnectListener implements Listener {

    private final ColorPartyPlugin plugin;

    public ColorPartyDisconnectListener(ColorPartyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Check if player is in the Color Party world.
        if (player.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) {
            plugin.getLogger().info(player.getName() + " disconnected from Color Party world.");
            // Use the existing method to remove the player from the game.
            plugin.getMinigameListener().removePlayerFromGame(player);
        }
    }
}
