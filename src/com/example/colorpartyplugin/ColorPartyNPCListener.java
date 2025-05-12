package com.example.colorpartyplugin;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ColorPartyNPCListener implements Listener {
    private final ColorPartyPlugin plugin;

    // The 6 possible X offsets
    private static final double[] POSSIBLE_X = {
            -2.5, -1.5, -0.5, 0.5, 1.5, 2.5
    };
    // The 6 possible Z offsets
    private static final double[] POSSIBLE_Z = {
            -65.5, -64.5, -63.5, -62.5, -61.5, -60.5
    };

    // Same 8 possible spawn offsets from my Dragons Minecraft minigame
    public static final int[][] SPAWN_OFFSETS = {
            {-2, -2}, {-2, 0}, {-2, 2},
            { 0, -2},          { 0, 2},
            { 2, -2}, { 2, 0}, { 2, 2}
    };

    // Store each player's "pre-game" offset here so can re-teleport them
    // to the dance floor at countdown. Key = player UUID; Value = double[]{xOffset, zOffset}
    private static final Map<UUID, double[]> preGameOffsets = new HashMap<>();

    public ColorPartyNPCListener(ColorPartyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof Villager villager) {
            if ("Play Color Party".equals(villager.getCustomName())) {
                Player player = event.getPlayer();
                World cpWorld = plugin.getServer().getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
                if (cpWorld != null) {
                    // Add them to the minigame
                    ColorPartyMinigameListener.addPlayerToGame(player);

                    // Store/clear inventory
                    plugin.storeAndClearInventory(player);

                    // Instead of spawning them on the dance floor, pick random X & Z from the arrays
                    double xOffset = POSSIBLE_X[(int)(Math.random() * POSSIBLE_X.length)];
                    double zOffset = POSSIBLE_Z[(int)(Math.random() * POSSIBLE_Z.length)];

                    // Save these offsets so we can later place them on the floor with the same "slot"
                    preGameOffsets.put(player.getUniqueId(), new double[]{ xOffset, zOffset });

                    // Y=123, using those offsets
                    Location spawnLoc = new Location(cpWorld, xOffset, 123, zOffset, 0, 0);
                    player.teleport(spawnLoc);
                    player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    player.setFallDistance(0f);

                    player.sendMessage("Welcome to Color Party! Use 'Start' to begin, or 'Exit' to leave.");
                }
            }
        }
    }

    //Public accessor so the minigame code can retrieve a player's pre-game offset
    // to teleport them onto the dance floor. If none is found, default to (0, 0).
    public static double[] getPreGameOffset(UUID playerId) {
        return preGameOffsets.getOrDefault(playerId, new double[]{0.0, 0.0});
    }
}