package com.example.colorpartyplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * PowerupManager is a separate class for awarding and handling powerup logic.
 * It references certain fields passed in via the constructor (plugin, random, playersWithColorTrail).
 * Any color or plugin-specific logic is passed as parameters when needed.
 */
public class PowerupManager {

    // Store references to the plugin, random, and "playersWithColorTrail" structure
    private final ColorPartyPlugin plugin;
    private final Random random;
    private final Set<UUID> playersWithColorTrail;

    // A set of players currently starved
    private final Set<UUID> starvedPlayers = new HashSet<>();

    // A set of players with magic carpet
    private final Set<UUID> carpetPlayers = new HashSet<>();
    private final Map<UUID, BukkitRunnable> carpetTasks = new HashMap<>();

    // Dance floor bounds
    // Used by magic carpet function
    private static final int FLOOR_SIZE = 64;
    private static final int FLOOR_START_X = -FLOOR_SIZE / 2; // -32
    private static final int FLOOR_START_Z = -FLOOR_SIZE / 2; // -32
    private static final int FLOOR_Y = 120;

    // Constructor: pass in references from your listener
    public PowerupManager(ColorPartyPlugin plugin, Random random, Set<UUID> playersWithColorTrail) {
        this.plugin = plugin;
        this.random = random;
        this.playersWithColorTrail = playersWithColorTrail;
    }

    // givePlayerOneUseAxe: "Leap Axe"
    public void givePlayerOneUseAxe(Player player) {
        ItemStack axe = new ItemStack(Material.IRON_AXE, 1);
        var meta = axe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Leap Axe");
            axe.setItemMeta(meta);
        }
        placeInFirstAvailableSlot(player, axe);
    }

    // Teleport clock
    public void givePlayerTeleportClock(Player player) {
        ItemStack clock = new ItemStack(Material.CLOCK, 1);
        var meta = clock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Teleport");
            clock.setItemMeta(meta);
        }
        // Place in first empty slot to avoid stacking
        int emptySlot = player.getInventory().firstEmpty();
        if (emptySlot != -1) {
            player.getInventory().setItem(emptySlot, clock);
        } else {
            player.getWorld().dropItem(player.getLocation(), clock);
        }
    }

    // Jump Potion
    public void givePlayerJumpPotion(Player player) {
        ItemStack potion = new ItemStack(Material.POTION, 1);
        var meta = potion.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Jump Potion");
            potion.setItemMeta(meta);
        }
        placeInFirstAvailableSlot(player, potion);
    }

    // Speed Potion
    public void givePlayerSpeedPotion(Player player) {
        ItemStack potion = new ItemStack(Material.POTION, 1);
        var meta = potion.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Speed Potion");
            potion.setItemMeta(meta);
        }
        placeInFirstAvailableSlot(player, potion);
    }

    // Adds the player to the set of players who have color-trail effect
    public void activateColorTrail(Player player) {
        playersWithColorTrail.add(player.getUniqueId());
    }

    // placeInFirstAvailableSlot: tries to put item in first empty slot; else drops it
    private void placeInFirstAvailableSlot(Player player, ItemStack item) {
        PlayerInventory inv = player.getInventory();
        var leftover = inv.addItem(item);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), item);
        }
    }

    // spawnExplosionCow: "Color Cow" at beacon location, painting blocks with safeColor
    public void spawnExplosionCow(Location beaconLoc, Material safeColor) {
        if (beaconLoc == null) return;
        World world = beaconLoc.getWorld();
        if (world == null) return;
        if (!ColorPartyPlugin.MINIGAME_WORLD_NAME.equals(world.getName())) return;

        // Final local color reference
        final Material cowColor = safeColor;

        var cowEntity = world.spawnEntity(beaconLoc, EntityType.COW);
        if (cowEntity instanceof Cow cow) {
            cow.setInvulnerable(true);
            cow.setSilent(true);
            cow.setAI(true);
            cow.setBaby();
            double vx = (random.nextDouble() - 0.5) * 0.6;
            double vz = (random.nextDouble() - 0.5) * 0.6;
            cow.setVelocity(new Vector(vx, 0.05, vz));
        }

        // explosion effect after 1s
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!cowEntity.isDead()) {
                    Location finalLoc = cowEntity.getLocation();
                    world.playSound(finalLoc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

                    cowEntity.remove();

                    int bx = finalLoc.getBlockX();
                    int bz = finalLoc.getBlockZ();
                    int radius = 3;
                    for (int x = bx - radius; x <= bx + radius; x++) {
                        for (int z = bz - radius; z <= bz + radius; z++) {
                            Location blockLoc = new Location(world, x, 120, z);
                            if (blockLoc.distance(finalLoc) <= radius + 0.5) {
                                Block b = world.getBlockAt(x, 120, z);
                                if (b.getType() != Material.AIR) {
                                    b.setType(cowColor);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    // Teleport player to random spot on dance floor
    public void teleportPlayerRandomFloor(Player player) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;
        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 121; // above floor
        int randX = startX + random.nextInt(floorSize);
        int randZ = startZ + random.nextInt(floorSize);
        Location target = new Location(cpWorld, randX + 0.5, y, randZ + 0.5);
        player.teleport(target);
        player.sendMessage(ChatColor.GREEN + "Teleported to a random tile!");
    }

    // Starve player, make movement difficult, they won't be able to sprint, no damage will occur
    public void makePlayerHungry(Player player) {
        // Add them to a starvedPlayers set so can track who is starved
        starvedPlayers.add(player.getUniqueId());

        // Immediately set their food level and saturation to 0
        player.setFoodLevel(0);
        player.setSaturation(0f);

    }

    public void restoreStarvedPlayers() {
        for (UUID uuid : starvedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                // Full food is 20
                p.setFoodLevel(20);
                p.setSaturation(5f);
            }
        }
        starvedPlayers.clear();
    }

    public void activateMagicCarpet(Player player) {
        // Add them to the set
        carpetPlayers.add(player.getUniqueId());

        // Play the "Trident return" sound
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1f);

        // Start a repeating task that updates the 3×3 glass beneath them
        BukkitRunnable task = new BukkitRunnable() {
            // Track the last set of blocks that were turned into glass
            private final Set<Block> previousGlassBlocks = new HashSet<>();

            @Override
            public void run() {
                // If player is offline or no longer has carpet, stop
                if (!player.isOnline() || !carpetPlayers.contains(player.getUniqueId())) {
                    clearPreviousGlass();
                    this.cancel();
                    return;
                }

                World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
                if (cpWorld == null) {
                    clearPreviousGlass();
                    this.cancel();
                    return;
                }

                // Remove the old glass from last iteration
                clearPreviousGlass();

                // Get player's block coords (rounded down) at floor Y-1, placing below them
                // Actually placing glass at y=120 if the dance floor is at y=120
                int px = player.getLocation().getBlockX();
                int py = FLOOR_Y; // The floor's Y
                int pz = player.getLocation().getBlockZ();

                // Center them so that the player stands in the middle of 3×3:
                // For a 3×3 region, we want offsets in [-1..1].
                // That means the center is (px, py, pz).
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int blockX = px + dx;
                        int blockZ = pz + dz;

                        // Ensure it's within the 64×64 floor region
                        if (blockX < FLOOR_START_X || blockX >= FLOOR_START_X + FLOOR_SIZE) continue;
                        if (blockZ < FLOOR_START_Z || blockZ >= FLOOR_START_Z + FLOOR_SIZE) continue;

                        Block b = cpWorld.getBlockAt(blockX, py, blockZ);

                        // Place glass ONLY if the block is AIR
                        if (b.getType() == Material.AIR) {
                            b.setType(Material.GLASS);
                            previousGlassBlocks.add(b);
                        }
                    }
                }
            }

            private void clearPreviousGlass() {
                // Remove glass from last iteration
                for (Block b : previousGlassBlocks) {
                    // Double-check it is still glass
                    if (b.getType() == Material.GLASS) {
                        b.setType(Material.AIR);
                    }
                }
                previousGlassBlocks.clear();
            }
        };

        // Schedule the task to run every few ticks
        task.runTaskTimer(plugin, 0L, 3L); // update ~every 3 ticks
        carpetTasks.put(player.getUniqueId(), task);
    }

    public void removeAllMagicCarpets() {
        // Stop tasks and remove glass for all carpet players
        for (UUID uuid : carpetPlayers) {
            BukkitRunnable task = carpetTasks.get(uuid);
            if (task != null) {
                task.cancel();
            }
        }
        carpetTasks.clear();
        carpetPlayers.clear();
    }

}
