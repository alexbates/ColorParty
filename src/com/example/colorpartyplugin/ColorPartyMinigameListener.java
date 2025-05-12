package com.example.colorpartyplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.model.Playlist;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;

import com.example.colorpartyplugin.CrazyEffectManager;
import com.example.colorpartyplugin.PowerupManager;
import com.example.colorpartyplugin.BuildVariations;

import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
ColorPartyMinigameListener handles all the event logic for the Color Party minigame.

Major features included in this file:

1) Full "round cycle" logic (up to 25 rounds).
2) Countdown, freeze countdown, random floor variations, safe color selection.
3) Powerups
4) Beacon spawning (3 beacons at 60% chance each round).
5) "Color Cow" explosion painting the floor around it to the safe color.
6) "Color Trail" effect (player's footsteps occasionally recolor blocks).
7) "Teleport" clock powerup to move the player to a random tile of the safe color, even mid-fall.
8) Multi-winner scenario if multiple players fall to their deaths at the same time.
9) Round 25 delay by 4 seconds after freeze before awarding final winners, to allow any last-second falls.
10) At the end of game, we set the floor to light gray. Once the last player truly leaves, the plugin
resets the custom floor for the next game (handled in removePlayerFromGame).

*/
public class ColorPartyMinigameListener implements Listener {

    private RadioSongPlayer currentSongPlayer = null;

    private final PowerupManager powerupManager;

    // Global static final fields for the void level, referencing the plugin's config
    // This is used to detect if a player falls into the void (Y < 80)
    private static final int VOID_LEVEL = ColorPartyPlugin.VOID_LEVEL;

    // Sets to track which players are in the game at all, and which are still alive
    // "playersInGame" = everyone who joined and hasn't fully left
    // "activePlayers" = those who haven't died/fallen yet
    private static final Set<UUID> playersInGame = new HashSet<>();
    private static final Set<UUID> activePlayers = new HashSet<>();

    // Main plugin reference
    private final ColorPartyPlugin plugin;

    // Random generator for beacons, floors, colors, etc.
    private final Random random = new Random();

    // Rounds
    private static final int MAX_ROUNDS = 25;
    private int currentRound = 0;

    // Keep track of whether game has ended
    public static boolean gameEnded = false;

    // Game state flags
    private static boolean gameStarted = false;
    private static boolean countdownRunning = false;

    // Track players who fell "this tick" for multi-winner detection.
    // If multiple players empty the set at once, they are all winners
    private final Set<UUID> fellThisTick = new LinkedHashSet<>();

    // The "safe color" for the current round (the color that remains after freeze)
    // Also used by color cows and color-trail
    private static Material currentSafeColor = Material.LIGHT_GRAY_TERRACOTTA;

    // usageLockedUntil => a map from UUID => last usage time + 250ms
    // to prevent immediate item usage if slot is already selected
    private final Map<UUID, Long> usageLockedUntil = new HashMap<>();

    // The "color trail" effect set of players, blocks below where playing is standing are recolored
    private final Set<UUID> playersWithColorTrail = new HashSet<>();

    // Keep track of whether Crazy Mode is selected
    private static boolean crazyMode = false;

    // CONSTRUCTOR
    public ColorPartyMinigameListener(ColorPartyPlugin plugin) {
        this.plugin = plugin;
        this.powerupManager = new PowerupManager(plugin, random, playersWithColorTrail);
    }

    // Helper: send message only to players in colorpartyworld
    private void sendGameMessage(String msg) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;
        for (Player p : cpWorld.getPlayers()) {
            p.sendMessage(msg);
        }
    }

    // Helper: only play sound in colorpartyworld
    private void playSoundInMinigameWorld(Sound sound, float volume, float pitch) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;
        for (Player p : cpWorld.getPlayers()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    // Called from NPC logic, adds them to playersInGame + activePlayers
    public static void addPlayerToGame(Player player) {
        playersInGame.add(player.getUniqueId());
        activePlayers.add(player.getUniqueId());

        // If there's already a song playing, add them so they can hear it
        ColorPartyMinigameListener listener = ColorPartyPlugin.instance.getMinigameListener();
        if (listener != null && listener.currentSongPlayer != null) {
            listener.currentSongPlayer.addPlayer(player);
        }
    }

    // If all players are gone, reset the custom floor
    public void removePlayerFromGame(Player player) {
        playersInGame.remove(player.getUniqueId());
        activePlayers.remove(player.getUniqueId());

        if (playersInGame.isEmpty()) {
            // No players left: reset custom floor for next time
            plugin.resetCustomFloor();
            gameStarted = false;
            countdownRunning = false;
            currentRound = 0;
        }
    }

    // No damage in colorpartyworld
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            if (p.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) {
                event.setCancelled(true);
            }
        }
    }

    // onPlayerInteract handles:
    // "Start": startCountdown
    // "Exit": exitColorParty
    // Beacon: random powerup
    // Iron Axe: leap
    // Jump/Speed potions: effect
    // Teleport clock: teleport to random safe tile
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!playersInGame.contains(player.getUniqueId())) return;
        if (!player.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) return;

        long lockedUntilVal = usageLockedUntil.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() < lockedUntilVal) {
            return; // Ignore
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && clicked.getType() == Material.BEACON) {
                event.setCancelled(true);
                Location loc = clicked.getLocation();
                clicked.setType(Material.AIR);
                grantRandomPowerup(player, loc);
                return;
            }
        }

        ItemStack item = event.getItem();
        if (item == null) return;

        String displayName = (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
                ? item.getItemMeta().getDisplayName()
                : "";

        // "Start Normal Mode"
        if (displayName.equalsIgnoreCase("Start Normal Mode") && !gameStarted && !countdownRunning) {
            crazyMode = false;
            // Show "Starting Normal Mode!" in green
            sendGameMessage(ChatColor.GREEN + "Starting Normal Mode!");

            // Teleport all in colorpartyworld onto the dance floor
            teleportAllPlayersToDanceFloor();

            // Then do a 5-second countdown
            startCountdown(5);
            event.setCancelled(true);
            return;
        }

        // "Start Crazy Mode"
        if (displayName.equalsIgnoreCase("Start Crazy Mode") && !gameStarted && !countdownRunning) {
            crazyMode = true;
            // Show "Starting Crazy Mode!" in purple
            sendGameMessage(ChatColor.DARK_PURPLE + "Starting Crazy Mode!");

            // Teleport all in colorpartyworld onto the dance floor
            teleportAllPlayersToDanceFloor();

            // Then do a 5-second countdown
            startCountdown(5);
            event.setCancelled(true);
            return;
        }

        // "Exit"
        if (displayName.equalsIgnoreCase("Exit")) {
            exitColorParty(player);
            event.setCancelled(true);
            return;
        }

        // Leap with Iron Axe
        if (item.getType() == Material.IRON_AXE && event.getAction().toString().contains("RIGHT_CLICK")) {
            doLeap(player);
            item.setAmount(0);
            event.setCancelled(true);
            return;
        }

        // Potions
        if (item.getType() == Material.POTION && item.hasItemMeta()) {
            if ("Jump Potion".equalsIgnoreCase(displayName)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 20, 2, false, false));
                item.setAmount(0);
                event.setCancelled(true);
                return;
            }
            if ("Speed Potion".equalsIgnoreCase(displayName)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 20, 2, false, false));
                item.setAmount(0);
                event.setCancelled(true);
                return;
            }
        }

        // Teleport clock
        if (item.getType() == Material.CLOCK && "Teleport".equalsIgnoreCase(displayName)) {
            teleportPlayerToSafeTile(player);
            item.setAmount(0);
            event.setCancelled(true);
            return;
        }
    }

    // Picks from the original 8 SPAWN_OFFSETS in ColorPartyNPCListener,
    // teleporting each player to Y=121 with an offset from (0.5, 0.5).
    private void teleportAllPlayersToDanceFloor() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        for (UUID uuid : playersInGame) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) {
                // Grab a random offset from the NPCListener's array:
                int[][] offsets = ColorPartyNPCListener.SPAWN_OFFSETS;
                int[] chosen = offsets[(int)(Math.random() * offsets.length)];

                double spawnX = 0.5 + chosen[0];
                double spawnZ = 0.5 + chosen[1];

                Location floorLoc = new Location(cpWorld, spawnX, 121, spawnZ, p.getLocation().getYaw(), p.getLocation().getPitch());
                p.teleport(floorLoc);

                p.setGameMode(GameMode.SURVIVAL);
                p.setAllowFlight(false);
                p.setFlying(false);
                p.setFallDistance(0f);
            }
        }
    }

    // teleportPlayerToSafeTile: picks a random block that is currentSafeColor
    // in the 64x64 floor, teleports them there. Called by the Teleport clock usage.
    private void teleportPlayerToSafeTile(Player player) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        List<Location> safeBlocks = new ArrayList<>();
        int size = 64;
        int startX = -size / 2;
        int startZ = -size / 2;
        int y = 120;
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                Block b = cpWorld.getBlockAt(startX + xx, y, startZ + zz);
                if (b.getType() == currentSafeColor) {
                    // stand at y=121
                    safeBlocks.add(b.getLocation().add(0.5, 1.1, 0.5));
                }
            }
        }
        if (safeBlocks.isEmpty()) {
            player.sendMessage("No safe tile found to teleport!");
            return;
        }
        Collections.shuffle(safeBlocks);
        Location target = safeBlocks.get(0);
        player.teleport(target);
        player.sendMessage(ChatColor.GREEN + "Teleported to a safe tile!");
    }

    // grantRandomPowerup has 9 types:
    // 0: Leap Axe
    // 1: Color Cow
    // 2: Jump Potion
    // 3: Speed Potion
    // 4: Color Trail
    // 5: Teleport Clock
    // 6: Teleport to random spot on dance floor
    // 7: Starvation (makes it difficult to move)
    // 8: Magic carpet (player can't fall)
    private void grantRandomPowerup(Player player, Location beaconLoc) {
        // 8 = 0 to 7
        int roll = random.nextInt(9); // 0..8
        switch (roll) {
            case 0:
                powerupManager.givePlayerOneUseAxe(player);
                sendGameMessage(player.getName() + " got a Leap Axe!");
                break;
            case 1:
                powerupManager.spawnExplosionCow(beaconLoc, currentSafeColor);
                sendGameMessage(player.getName() + " unleashed a Color Cow!");
                break;
            case 2:
                powerupManager.givePlayerJumpPotion(player);
                sendGameMessage(player.getName() + " got a Jump Potion!");
                break;
            case 3:
                powerupManager.givePlayerSpeedPotion(player);
                sendGameMessage(player.getName() + " got a Speed Potion!");
                break;
            case 4:
                powerupManager.activateColorTrail(player);
                sendGameMessage(player.getName() + " got a Color Trail!");
                break;
            case 5:
                powerupManager.givePlayerTeleportClock(player);
                sendGameMessage(player.getName() + " got a Teleport clock!");
                break;
            case 6:
                // teleport the player to a random location on the dance floor.
                powerupManager.teleportPlayerRandomFloor(player);
                sendGameMessage(player.getName() + " was teleported!");
                break;
            case 7:
                // Hunger until the next round
                powerupManager.makePlayerHungry(player);
                sendGameMessage(player.getName() + " has been Starved!");
                break;
            case 8:
                // Magic Carpet
                powerupManager.activateMagicCarpet(player);
                sendGameMessage(player.getName() + " got a Magic Carpet!");
                break;
        }
        usageLockedUntil.put(player.getUniqueId(), System.currentTimeMillis() + 250L);
    }

    // Leap with no cooldown
    private void doLeap(Player player) {
        var dir = player.getLocation().getDirection().normalize();
        dir.multiply(1.4);
        dir.setY(dir.getY() + 0.1);
        player.setVelocity(dir);
        player.setFallDistance(0f);
    }

    // onPlayerMove: what to do in multiple scenarios
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!playersInGame.contains(player.getUniqueId())) return;
        if (!player.getWorld().getName().equals(ColorPartyPlugin.MINIGAME_WORLD_NAME)) return;

        // Color trail logic
        if (playersWithColorTrail.contains(player.getUniqueId())) {
            if (event.getTo().getY() >= 120) {
                Block below = player.getWorld().getBlockAt(event.getTo().getBlockX(), 120, event.getTo().getBlockZ());
                if (below.getType() != Material.AIR) {
                    if (random.nextInt(5) == 0) {
                        Material[] terras = {
                                Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA,
                                Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
                                Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
                                Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
                                Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA,
                                Material.BLACK_TERRACOTTA
                        };
                        Material newColor = terras[random.nextInt(terras.length)];
                        if (below.getType() == Material.LIGHT_GRAY_TERRACOTTA && newColor == Material.LIGHT_GRAY_TERRACOTTA) {
                            // Skip changing a LGRAY block to LGRAY
                        } else {
                            below.setType(newColor);
                        }
                    }
                }
            }
        }

        // CASE: Player falls below VOID_LEVEL
        if (event.getTo().getY() < VOID_LEVEL) {
            // World reference is needed for teleports
            World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
            if (cpWorld == null) return;

            // For pre-game (Case A), place them at y=121 on the dance floor:
            int[] offset = ColorPartyNPCListener.SPAWN_OFFSETS[(int) (Math.random() * ColorPartyNPCListener.SPAWN_OFFSETS.length)];
            double spawnX = 0.5 + offset[0];
            double spawnZ = 0.5 + offset[1];
            Location floorSpawn = new Location(cpWorld, spawnX, 121, spawnZ);

            // CASE A: Pre-game (gameStarted == false, gameEnded == false)
            if (!gameStarted && !gameEnded) {
                player.teleport(floorSpawn);
                player.setGameMode(GameMode.SURVIVAL);
                player.setFallDistance(0f);
                // No inventory clearing, no "You fell" message
                return;
            }

            // CASE B: Game in progress (gameStarted == true)
            if (gameStarted) {
                // Only process the "fell to death" logic if they're an active player
                if (activePlayers.contains(player.getUniqueId())) {
                    fellThisTick.add(player.getUniqueId());
                    sendGameMessage(ChatColor.RED + player.getName() + " fell to their death!");
                    activePlayers.remove(player.getUniqueId());

                    player.setFoodLevel(20);
                    player.setSaturation(5f);

                    // Play lightning effect if before final round
                    if (currentRound < MAX_ROUNDS) {
                        Location loc = player.getLocation();
                        cpWorld.strikeLightningEffect(loc); // Visual effect
                        cpWorld.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
                    }

                    // Check if no players remain
                    if (activePlayers.isEmpty()) {
                        // Stop the song immediately when the last player falls.
                        stopCurrentSong();

                        // Remove beacon powerups
                        removeBeaconsInFloorRegion();

                        // Stop Crazy mode effects
                        CrazyEffectManager.stopAllEffects();

                        // Multi-winner scenario
                        Set<UUID> combined = new LinkedHashSet<>(fellThisTick);
                        if (combined.isEmpty()) {
                            sendGameMessage("All players must exit and rejoin to reset the game.");
                        } else {
                            List<String> names = new ArrayList<>();
                            for (UUID id : combined) {
                                Player p2 = Bukkit.getPlayer(id);
                                if (p2 != null) {
                                    names.add(p2.getName());
                                }
                            }
                            if (names.isEmpty()) {
                                sendGameMessage("All players must exit and rejoin to reset the game.");
                            } else if (names.size() == 1) {
                                sendGameMessage(ChatColor.GOLD + names.get(0) + " WON THE GAME!");
                                sendGameMessage("All players must exit and rejoin to reset the game.");
                            } else if (names.size() == 2) {
                                sendGameMessage(ChatColor.GOLD + names.get(0) + " and " + names.get(1) + " WON THE GAME!");
                                sendGameMessage("All players must exit and rejoin to reset the game.");
                            } else {
                                // A, B, and C WON THE GAME
                                String allButLast = String.join(", ", names.subList(0, names.size() - 1));
                                String lastName = names.get(names.size() - 1);
                                sendGameMessage(ChatColor.GOLD + allButLast + ", and " + lastName + " WON THE GAME!");
                                sendGameMessage("All players must exit and rejoin to reset the game.");
                            }
                        }

                        if (cpWorld != null) {
                            // Dance floor is 64×64 at y=120, centered at (0,120,0):
                            int floorSize = 64;
                            int startX = -floorSize / 2; // i.e. -32
                            int startZ = -floorSize / 2; // i.e. -32
                            int y = 120;

                            BuildVariations.buildVariationGameOver(cpWorld, startX, startZ, floorSize, y);
                        }
                        gameStarted = false;
                        // Set as true so post-game logic takes effect
                        gameEnded = true;
                        countdownRunning = false;
                        currentRound = 0;
                    } else if (activePlayers.size() == 1) {
                        UUID lastId = activePlayers.iterator().next();
                        Player lastPlayer = Bukkit.getPlayer(lastId);
                        if (lastPlayer != null) {
                            sendGameMessage(lastPlayer.getName() + " is the last player standing!");
                            sendGameMessage("They will continue alone until they fall (or we reach 25 rounds)...");
                        }
                    }
                }

                // Switch the fallen player to ADVENTURE mode with flight
                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setInvulnerable(true);

                // Move them above the floor to watch
                spawnObserver(player);

                // Clear inventory, give Exit bed
                player.setFallDistance(0f);
                player.getInventory().clear();
                ItemStack bed = new ItemStack(Material.WHITE_BED, 1);
                ItemMeta meta = bed.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("Exit");
                    bed.setItemMeta(meta);
                }
                player.getInventory().setItem(8, bed);
                return;
            }

            // CASE C: Post-game (gameStarted == false, gameEnded == true)
            // In this scenario, the game has finished and winners were declared.
            if (!gameStarted && gameEnded) {
                // Teleport them above the dance floor in ADVENTURE mode with flight to observe
                spawnObserver(player);

                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setInvulnerable(true);
                player.setFoodLevel(20);
                player.setSaturation(5f);

                // OPTIONAL (DISABLED FOR NOW): clear player inventory post game
                // player.getInventory().clear();

                return;
            }
        }
    }

    // spawnObserver: teleport player overhead to watch
    private void spawnObserver(Player player) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;
        int[][] offsets = {
                {-2, -2}, {-2, 0}, {-2, 2},
                { 0, -2},          { 0, 2},
                { 2, -2}, { 2, 0}, { 2, 2}
        };
        int[] offset = offsets[random.nextInt(offsets.length)];
        double x = 0.5 + offset[0];
        double z = 0.5 + offset[1];
        Location loc = new Location(cpWorld, x, 127, z);
        player.teleport(loc);
    }

    // exitColorParty: take player back to overworld
    private void exitColorParty(Player player) {
        World mainWorld = Bukkit.getWorld("world");
        if (mainWorld != null) {
            Location mainSpawn = new Location(mainWorld, 59.5, 66, -393.5);
            player.teleport(mainSpawn);
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.setFallDistance(0f);
        player.setAllowFlight(false);
        player.setInvulnerable(false);
        player.setWalkSpeed(0.2f);
        plugin.restoreInventory(player);
        removePlayerFromGame(player);
        player.sendMessage("You have exited the Color Party minigame.");
    }

    // startCountdown: 5..4..3..2..1..Go
    public void startCountdown(int countdownSeconds) {
        countdownRunning = true;
        // "Starting in 5..."
        sendGameMessage("Starting in " + countdownSeconds + "...");

        new BukkitRunnable() {
            int count = countdownSeconds;
            @Override
            public void run() {
                count--;
                if (count > 0) {
                    sendGameMessage("Starting in " + count + "...");
                } else if (count == 0) {
                    sendGameMessage("Go!");
                    gameStarted = true;
                    countdownRunning = false;
                    currentRound = 0;
                    fellThisTick.clear();
                    playersWithColorTrail.clear();

                    // Remove "Start Normal/Crazy" items from inventory
                    for (UUID uuid : playersInGame) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            // Remove emerald or shard from slot 0 or 1
                            ItemStack slot0 = p.getInventory().getItem(0);
                            if (slot0 != null && (slot0.getType() == Material.EMERALD || slot0.getType() == Material.AMETHYST_SHARD)) {
                                p.getInventory().setItem(0, null);
                            }
                            ItemStack slot1 = p.getInventory().getItem(1);
                            if (slot1 != null && (slot1.getType() == Material.EMERALD || slot1.getType() == Material.AMETHYST_SHARD)) {
                                p.getInventory().setItem(1, null);
                            }
                            // Set walk speed +20%
                            p.setWalkSpeed(0.24f);
                        }
                    }

                    // Start music
                    startRandomSong();
                    // Kick off the round cycle
                    startRoundCycle();

                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startRandomSong() {
        // Get references to the loaded songs
        Song fixYou = plugin.getFixYouSong();
        Song getLucky = plugin.getGetLuckySong();

        if (fixYou == null && getLucky == null) {
            plugin.getLogger().warning("No songs loaded! Skipping music.");
            return;
        }

        // Randomly pick one
        Song chosen = null;
        if (fixYou != null && getLucky != null) {
            chosen = (random.nextBoolean()) ? fixYou : getLucky;
        } else if (fixYou != null) {
            chosen = fixYou;
        } else {
            chosen = getLucky;
        }

        // Create a RadioSongPlayer with the chosen song
        currentSongPlayer = new RadioSongPlayer(chosen);

        // Add all players in the game to the RadioSongPlayer so they can hear it
        for (UUID uuid : playersInGame) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                currentSongPlayer.addPlayer(p);
            }
        }

        // Make sure the music actually plays
        currentSongPlayer.setAutoDestroy(true);
        currentSongPlayer.setPlaying(true);
    }

    private void stopCurrentSong() {
        if (currentSongPlayer != null) {
            currentSongPlayer.setPlaying(false);
            currentSongPlayer.destroy(); // remove internal references, if any
            currentSongPlayer = null;
        }
    }

    // startRoundCycle: reset round=0, doRound
    private void startRoundCycle() {
        currentRound = 0;
        doRound();
    }

    // doRound: currentRound++, build random floor, pick safe color,
    // 60% chance spawn 3 beacons, freeze time check, 3s silent
    private void doRound() {
        // If the game ended or no active players remain, don't start another round
        if (!gameStarted || gameEnded || activePlayers.isEmpty()) {
            return;
        }
        if (currentRound >= MAX_ROUNDS) return;

        // STOP ANY CRAZY EFFECT
        if (crazyMode) {
            CrazyEffectManager.stopChickenJockeyEffect2();
        }

        // If there are any starvedPlayers set in the PowerupManager:
        powerupManager.restoreStarvedPlayers();

        // Refill hunger for all players in the ColorParty world regardless of whether they were starved by powerup:
        for (Player player : Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME).getPlayers()) {
            player.setFoodLevel(20);
            player.setSaturation(5f);
        }

        // Remove magic carpets from the previous round
        powerupManager.removeAllMagicCarpets();

        currentRound++;
        fellThisTick.clear();

        sendGameMessage("Round " + currentRound + "/" + MAX_ROUNDS);

        Set<Material> usedColors = buildRandomFloorVariation();
        if (usedColors.isEmpty()) {
            usedColors.add(Material.LIGHT_GRAY_TERRACOTTA);
        }

        // Pick safe color
        List<Material> colorList = new ArrayList<>(usedColors);
        currentSafeColor = colorList.get(random.nextInt(colorList.size()));

        // Spawn 3 beacons (60% chance)
        if (random.nextDouble() < 0.60) {
            for (int i = 0; i < 3; i++) {
                spawnBeacon();
            }
            sendGameMessage(ChatColor.GREEN + "Powerups have spawned!");
        }

        // If in crazyMode, pick a random effect to start
        if (crazyMode) {
            int floorSize = 64;
            int startX = -32;
            int startZ = -32;
            int floorY = 120;

            // The round length in ticks is freezeCount + the short 3s delay.
            // If freezeCount = 5 => 5s freeze => plus ~3s pre-freeze => 8s total => ~160 ticks
            int freezeCount = getFreezeCountForRound(currentRound);
            int totalSeconds = freezeCount + 3;  // 3s pre-freeze
            int roundTicks = totalSeconds * 20;

            CrazyEffectManager.startRandomEffect(plugin, floorSize, startX, startZ, floorY, roundTicks);
        }

        int freezeCount = getFreezeCountForRound(currentRound);
        if ((currentRound == 6 && freezeCount == 4)
                || (currentRound == 11 && freezeCount == 3)
                || (currentRound == 16 && freezeCount == 2)
                || (currentRound == 21 && freezeCount == 1)) {
            sendGameMessage(ChatColor.YELLOW + "Round time reduced to " + freezeCount + " second" + (freezeCount == 1 ? "!" : "s!"));
        }

        // 3s silent
        new BukkitRunnable() {
            int delay = 3;
            @Override
            public void run() {
                delay--;
                if (delay <= 0) {
                    cancel();
                    startFreezeCountdown(usedColors, freezeCount);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // spawnBeacon: place a beacon block at y=121 in the 64x64 region
    private void spawnBeacon() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int rx = startX + random.nextInt(floorSize);
        int rz = startZ + random.nextInt(floorSize);
        cpWorld.getBlockAt(rx, 121, rz).setType(Material.BEACON);
    }

    // startFreezeCountdown => place safe color in inventory slot 7, do freezeCount
    // then remove beacons, remove unsafe blocks, remove color trail effect
    private void startFreezeCountdown(Set<Material> usedColors, int freezeCount) {
        Material safeClay = currentSafeColor;

        // Place safe block in slot 7
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                ItemStack clayItem = new ItemStack(safeClay, 1);
                var meta = clayItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("Stand on this!");
                    clayItem.setItemMeta(meta);
                }
                p.getInventory().setItem(7, clayItem);
            }
        }

        new BukkitRunnable() {
            int t = freezeCount;
            boolean finalRoundDelayScheduled = false;

            @Override
            public void run() {
                if (!gameStarted) {
                    cancel();
                    return;
                }
                if (t > 0) {
                    playSoundInMinigameWorld(Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    sendGameMessage(t + "...");
                    t--;
                } else {
                    playSoundInMinigameWorld(Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                    sendGameMessage(ChatColor.YELLOW + "FREEZE!");

                    // STOP ANY CRAZY EFFECT
                    if (crazyMode) {
                        CrazyEffectManager.stopAllEffects();

                        //CrazyEffectManager.stopSnowEffect();
                        //CrazyEffectManager.stopWitherEffect();
                        //CrazyEffectManager.stopRollingEffect();
                        //CrazyEffectManager.stopChickenJockeyEffect();
                    }

                    removeBeaconsInFloorRegion();
                    removeUnsafeBlocks(safeClay);

                    // Disable color trail so it doesn't carry into next round
                    playersWithColorTrail.clear();

                    // Remove safe block from slot 7
                    for (UUID uuid : activePlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.getInventory().setItem(7, null);
                        }
                    }

                    cancel();

                    // If last round, wait 4s, then finalize
                    if (currentRound >= MAX_ROUNDS && gameStarted) {
                        if (!finalRoundDelayScheduled) {
                            finalRoundDelayScheduled = true;
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (gameStarted && !activePlayers.isEmpty()) {
                                        multiEndGameNow();
                                    } else {
                                        multiEndGameNow();
                                    }
                                }
                            }.runTaskLater(plugin, 80L); // 4s
                        }
                    } else {
                        startPostFreezeDelay();
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // multiEndGameNow => called after final round's 4s delay
    // teleports survivors overhead and sets them as winners
    private void multiEndGameNow() {
        // Remove all powerup beacons from the dance floor
        removeBeaconsInFloorRegion();

        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        int floorSize = 64;
        int startX = -floorSize / 2; // i.e. -32
        int startZ = -floorSize / 2; // i.e. -32
        int y = 120;

        if (!gameStarted) return;
        gameStarted = false;

        // Indicate that game has ended
        gameEnded = true;

        // Stop the music
        stopCurrentSong();

        // Remove all powerup beacons from the dance floor, again
        removeBeaconsInFloorRegion();

        if (activePlayers.isEmpty()) {
            sendGameMessage("All players must exit and rejoin to reset the game.");
            if (cpWorld != null) {
                BuildVariations.buildVariationGameOver(cpWorld, startX, startZ, floorSize, y);
            }
            currentRound = MAX_ROUNDS;
            return;
        }
        // Multi winners
        Set<UUID> alive = new LinkedHashSet<>(activePlayers);
        List<String> names = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                names.add(p.getName());
            }
        }
        if (names.size() == 1) {
            sendGameMessage(ChatColor.GOLD + names.get(0) + " WON THE GAME!");
        } else if (names.size() == 2) {
            sendGameMessage(ChatColor.GOLD + names.get(0) + " and " + names.get(1) + " WON THE GAME!");
        } else {
            String allButLast = String.join(", ", names.subList(0, names.size() - 1));
            String lastName = names.get(names.size() - 1);
            sendGameMessage(ChatColor.GOLD + allButLast + ", and " + lastName + " WON THE GAME!");
        }
        sendGameMessage("All players must exit and rejoin to reset the game.");
        if (cpWorld != null) {
            BuildVariations.buildVariationGameOver(cpWorld, startX, startZ, floorSize, y);
        }
        currentRound = MAX_ROUNDS;

        // Spawn them overhead
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                p.setFlying(true);
                p.setInvulnerable(true);
                spawnObserver(p);
                p.setFallDistance(0f);
                p.getInventory().clear();
                ItemStack bed = new ItemStack(Material.WHITE_BED, 1);
                var meta = bed.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("Exit");
                    bed.setItemMeta(meta);
                }
                p.getInventory().setItem(8, bed);
            }
        }
    }

    // removeBeaconsInFloorRegion: remove all beacons from y=121 in the 64x64 area
    private void removeBeaconsInFloorRegion() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;
        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 121;
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                Block b = cpWorld.getBlockAt(x, y, z);
                if (b.getType() == Material.BEACON) {
                    b.setType(Material.AIR);
                }
            }
        }
    }

    // startPostFreezeDelay: next round starts 3.25 seconds after the floor is removed
    private void startPostFreezeDelay() {
        // Directly schedule doRound() to run after 65 ticks (≈3.25 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                // If the game ended or no active players remain, do nothing
                if (!gameStarted || gameEnded || activePlayers.isEmpty()) {
                    cancel();
                    return;
                }
                doRound();
            }
        }.runTaskLater(plugin, 65L);
    }

    // endGameForWinners: multi-winner approach
    private void endGameForWinners() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        int floorSize = 64;
        int startX = -floorSize / 2; // i.e. -32
        int startZ = -floorSize / 2; // i.e. -32
        int y = 120;

        if (!gameStarted) return;
        gameStarted = false;

        // Indicate that game has ended
        gameEnded = true;

        // Stop the music
        stopCurrentSong();

        if (activePlayers.isEmpty()) {
            sendGameMessage("All players must exit and rejoin to reset the game.");

            if (cpWorld != null) {
                BuildVariations.buildVariationGameOver(cpWorld, startX, startZ, floorSize, y);
            }
            currentRound = MAX_ROUNDS;
            return;
        }
        Set<UUID> alive = new LinkedHashSet<>(activePlayers);
        List<String> names = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                names.add(p.getName());
            }
        }
        if (names.size() == 1) {
            sendGameMessage(ChatColor.GOLD + names.get(0) + " WON THE GAME!");
        } else if (names.size() == 2) {
            sendGameMessage(ChatColor.GOLD + names.get(0) + " and " + names.get(1) + " WON THE GAME!");
        } else {
            String allButLast = String.join(", ", names.subList(0, names.size() - 1));
            String lastName = names.get(names.size() - 1);
            sendGameMessage(ChatColor.GOLD + allButLast + ", and " + lastName + " WON THE GAME!");
        }
        sendGameMessage("All players must exit and rejoin to reset the game.");
        if (cpWorld != null) {
            BuildVariations.buildVariationGameOver(cpWorld, startX, startZ, floorSize, y);
        }
        currentRound = MAX_ROUNDS;

        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                p.setFlying(true);
                p.setInvulnerable(true);
                spawnObserver(p);
                p.setFallDistance(0f);
                p.getInventory().clear();
                ItemStack bed = new ItemStack(Material.WHITE_BED, 1);
                var meta = bed.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("Exit");
                    bed.setItemMeta(meta);
                }
                p.getInventory().setItem(8, bed);
            }
        }
    }

    // Floor building logic

    // This list will keep track of the last 8 variation numbers used.
    private static final LinkedList<Integer> recentFloorVariations = new LinkedList<>();

    private Set<Material> buildRandomFloorVariation() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return Collections.emptySet();

        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 120;
        // Clear the region
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                cpWorld.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }

        // Select a variation that wasn't used in the last 8 rounds
        List<Integer> allowedVariations = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            if (!recentFloorVariations.contains(i)) {
                allowedVariations.add(i);
            }
        }
        // Fallback: if for some reason all 19 variations are in the history (shouldn't happen since 19 > 12)
        if (allowedVariations.isEmpty()) {
            for (int i = 1; i <= 19; i++) {
                allowedVariations.add(i);
            }
        }
        // Randomly pick one variation from the allowed set.
        int variation = allowedVariations.get(random.nextInt(allowedVariations.size()));
        // Add the chosen variation to the history.
        recentFloorVariations.add(variation);
        // Keep only the last 12 rounds in the history.
        if (recentFloorVariations.size() > 12) {
            recentFloorVariations.removeFirst();
        }

        // BUILD DANCE FLOOR (20 variations)
        Set<Material> usedColors = new HashSet<>();
        switch (variation) {
            case 1:
                usedColors.addAll(BuildVariations.buildVariationRandomScatter(cpWorld, startX, startZ, floorSize, y));
                break;
            case 2:
                usedColors.addAll(BuildVariations.buildVariation4x4Squares(cpWorld, startX, startZ, floorSize, y));
                break;
            case 3:
                usedColors.addAll(BuildVariations.buildVariationCircles(cpWorld, startX, startZ, floorSize, y));
                break;
            case 4:
                usedColors.addAll(BuildVariations.buildVariationDiagonalStripes(cpWorld, startX, startZ, floorSize, y));
                break;
            case 5:
                usedColors.addAll(BuildVariations.buildVariationOneWideDiagonal(cpWorld, startX, startZ, floorSize, y));
                break;
            case 6:
                usedColors.addAll(BuildVariations.buildVariationOverlappingCircles(cpWorld, startX, startZ, floorSize, y));
                break;
            case 7:
                usedColors.addAll(BuildVariations.buildVariationSquareStripes(cpWorld, startX, startZ, floorSize, y));
                break;
            case 8:
                usedColors.addAll(BuildVariations.buildVariationStripes8x8Repeat(cpWorld, startX, startZ, floorSize, y));
                break;
            case 9:
                usedColors.addAll(BuildVariations.buildVariationJson1(cpWorld, startX, startZ, floorSize, y));
                break;
            case 10:
                usedColors.addAll(BuildVariations.buildVariationJsonHearts(cpWorld, startX, startZ, floorSize, y));
                break;
            case 11:
                usedColors.addAll(BuildVariations.buildVariationJsonCarrots(cpWorld, startX, startZ, floorSize, y));
                break;
            case 12:
                usedColors.addAll(BuildVariations.buildVariationJsonTurtles(cpWorld, startX, startZ, floorSize, y));
                break;
            case 13:
                usedColors.addAll(BuildVariations.buildVariationJsonConcentricSquares(cpWorld, startX, startZ, floorSize, y));
                break;
            case 14:
                usedColors.addAll(BuildVariations.buildVariationJsonConnectedRings(cpWorld, startX, startZ, floorSize, y));
                break;
            case 15:
                usedColors.addAll(BuildVariations.buildVariationJsonStars(cpWorld, startX, startZ, floorSize, y));
                break;
            case 16:
                usedColors.addAll(BuildVariations.buildVariationJson4SectionBricks(cpWorld, startX, startZ, floorSize, y));
                break;
            case 17:
                usedColors.addAll(BuildVariations.buildVariationJson9Squares(cpWorld, startX, startZ, floorSize, y));
                break;
            case 18:
                usedColors.addAll(BuildVariations.buildVariationJson3Shapes(cpWorld, startX, startZ, floorSize, y));
                break;
            case 19:
                usedColors.addAll(BuildVariations.buildVariationJson5Circles(cpWorld, startX, startZ, floorSize, y));
                break;
        }
        return usedColors;
    }

    // removeUnsafeBlocks: remove everything not safeClay from the 64x64 floor
    private static void removeUnsafeBlocks(Material safeClay) {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 120;
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                Block b = cpWorld.getBlockAt(x, y, z);
                if (b.getType() != safeClay) {
                    b.setType(Material.AIR);
                }
            }
        }
    }

    // buildSolidLightGrayFloor: after the game ends, until players leave the world
    // THIS FUNCTION IS NO LONGER USED AS OF 4/2/25. LIKELY CAN REMOVE, NEED TO VERIFY
    private static void buildSolidLightGrayFloor() {
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 120;
        // Clear
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                cpWorld.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
        // Fill LGRAY
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                cpWorld.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);
            }
        }
    }

    // Freeze time logic, the rounds get progressively shorter
    // Rounds 1–5 => 5, 6–10 => 4, 11–15 => 3, 16–20 => 2, 21–25 => 1
    private int getFreezeCountForRound(int round) {
        if (round >= 21) return 1;
        if (round >= 16) return 2;
        if (round >= 11) return 3;
        if (round >= 6)  return 4;
        return 5;
    }

}
