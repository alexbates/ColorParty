package com.example.colorpartyplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.WorldCreator;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.type.Light;
import org.bukkit.scheduler.BukkitRunnable;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.model.Playlist;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.HashSet;
import java.util.Set;

import com.example.colorpartyplugin.ColorPartyMinigameListener;

public class ColorPartyPlugin extends JavaPlugin {

    public static final String MINIGAME_WORLD_NAME = "colorpartyworld";
    public static final int VOID_LEVEL = 80;

    // Store main-world inventories for each player
    private final Map<UUID, ItemStack[]> mainWorldInventories = new HashMap<>();
    private final Map<UUID, ItemStack[]> mainWorldArmor = new HashMap<>();

    // This is used so that I can call removePlayerFromGame function within DisconnectListener
    private ColorPartyMinigameListener minigameListener;

    // Store references to the loaded songs
    private Song fixYouSong;
    private Song getLuckySong;

    // Used in minigamelistener for adding players to song
    public static ColorPartyPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("ColorPartyPlugin onEnable start...");

        // Ensure plugin data folder
        if (!getDataFolder().exists()) {
            if (getDataFolder().mkdirs()) {
                getLogger().info("Created plugin data folder: " + getDataFolder().getAbsolutePath());
            }
        }

        // Copy JSON dance floor files from JAR to data folder, overwriting if already present
        copyResource("floor_start.json");
        copyResource("floor_gameover.json");
        copyResource("floor_variation_1.json");
        copyResource("floor_carrots.json");
        copyResource("floor_hearts.json");
        copyResource("floor_turtles.json");
        copyResource("floor_concentric_squares.json");
        copyResource("floor_connected_rings.json");
        copyResource("floor_stars.json");
        copyResource("floor_4section_bricks.json");
        copyResource("floor_9squares.json");
        copyResource("floor_3shapes.json");
        copyResource("floor_5circles.json");
        copyResource("Fix You.nbs");
        copyResource("Get Lucky.nbs");

        // Load the NBS songs using NBSDecoder
        File fixYouFile = new File(getDataFolder(), "Fix You.nbs");
        File getLuckyFile = new File(getDataFolder(), "Get Lucky.nbs");
        if (fixYouFile.exists()) {
            fixYouSong = NBSDecoder.parse(fixYouFile);
        }
        if (getLuckyFile.exists()) {
            getLuckySong = NBSDecoder.parse(getLuckyFile);
        }

        // Create/Load colorpartyworld
        World cpWorld = Bukkit.getWorld(MINIGAME_WORLD_NAME);
        if (cpWorld == null) {
            getLogger().info("World " + MINIGAME_WORLD_NAME + " not found; creating it now.");
            WorldCreator wc = new WorldCreator(MINIGAME_WORLD_NAME);
            wc.generator(new VoidGenerator()); // Use our custom void generator
            cpWorld = Bukkit.createWorld(wc);
        }
        if (cpWorld != null) {
            getLogger().info("Configuring " + MINIGAME_WORLD_NAME);
            cpWorld.setTime(18000); // Always night (18000 ticks is midnight)
            cpWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            cpWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false); // Disable all mob spawns
            cpWorld.setSpawnFlags(false, false);
            cpWorld.setDifficulty(Difficulty.NORMAL);
            cpWorld.setSpawnFlags(true, true); // allow hostile and friendly mobs
            cpWorld.setStorm(false);
            cpWorld.setThundering(false);
            cpWorld.setWeatherDuration(999999999);
            cpWorld.setThunderDuration(999999999);
        }

        // Build/reset the arena using floor_start.json
        resetArena();

        // NPC spawn logic
        World mainWorld = Bukkit.getWorld("world");
        if (mainWorld != null) {
            Location npcLoc = new Location(mainWorld, 59.5, 66, -392.5, 180f, 0f);
            boolean npcExists = false;

            for (Entity e : mainWorld.getEntities()) {
                if (e instanceof Villager villager) {
                    if ("Play Color Party".equals(villager.getCustomName())
                            && e.getLocation().distance(npcLoc) < 1) {
                        npcExists = true;
                        break;
                    }
                }
            }

            if (!npcExists) {
                getLogger().info("Spawning NPC 'Play Color Party' at " + npcLoc);
                Villager npc = mainWorld.spawn(npcLoc, Villager.class);
                npc.setCustomName("Play Color Party");
                npc.setCustomNameVisible(true);
                npc.setInvulnerable(true);
                npc.setSilent(true);
                npc.setPersistent(true);
                try {
                    npc.setGravity(false);
                } catch (NoSuchMethodError ignored) {}
                try {
                    npc.setAI(false);
                } catch (NoSuchMethodError ignored) {}
            } else {
                getLogger().info("Found existing 'Play Color Party' NPC.");
            }
        }

        // Schedule placement of invisible lights
        placeInvisibleLights(cpWorld);

        // Register events
        minigameListener = new ColorPartyMinigameListener(this);
        getServer().getPluginManager().registerEvents(new ColorPartyNPCListener(this), this);
        getServer().getPluginManager().registerEvents(new ColorPartyMinigameListener(this), this);
        getServer().getPluginManager().registerEvents(new ColorPartyDisconnectListener(this), this);
        getServer().getPluginManager().registerEvents(new ColorPartyJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new CrazyEffectManager(), this);

        BuildVariations.setPlugin(this);

        // SPAWN FIREWORK PARTICLES ON THE DANCE FLOOR, RANDOMLY (must be final)
        final World cpWorldFinal = cpWorld;
        // VARIABLES TO CONTROL PARTICLE SPAWNING
        // Number of particles to spawn each cycle
        final int particlesPerCycle = 18;
        // Frequency (in ticks, 20 ticks = 1 second)
        final long particleFrequencyTicks = 20L;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                // Use a set to track which block positions (x,y,z) have had a particle this cycle.
                Set<String> usedPositions = new HashSet<>();

                for (int i = 0; i < particlesPerCycle; i++) {
                    // Random integer between -32 and 32 for both X and Z
                    int x = -32 + (int) (Math.random() * 65);
                    int z = -32 + (int) (Math.random() * 65);
                    // Randomly choose Y = 122 or Y = 119
                    int y = (Math.random() < 0.5) ? 122 : 119;

                    // Create a key representing this block position
                    String key = x + "," + y + "," + z;
                    if (usedPositions.contains(key)) {
                        // Skip if a particle is already scheduled for this block this cycle
                        continue;
                    }
                    usedPositions.add(key);

                    // Calculate the precise location (center of the block)
                    Location loc = new Location(cpWorldFinal, x + 0.5, y, z + 0.5);

                    // Spawn a single firework spark particle with no extra offset or speed.
                    cpWorldFinal.spawnParticle(Particle.FIREWORK, loc, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }, 0L, particleFrequencyTicks);

        getLogger().info("ColorPartyPlugin onEnable finished! Plugin is enabled.");
    }

    // This is used in onEnable method to copy JSON files
    private void copyResource(String resourceName) {
        try {
            saveResource(resourceName, true);
            getLogger().info(resourceName + " extracted to: "
                    + new File(getDataFolder(), resourceName).getAbsolutePath());
        } catch (IllegalArgumentException e) {
            getLogger().severe(resourceName + " not found in the JAR! " + e.getMessage());
        }
    }

    // For in-game songs
    public Song getFixYouSong() {
        return fixYouSong;
    }
    public Song getGetLuckySong() {
        return getLuckySong;
    }

    // Provide a public getter for the minigame listener:
    // This is used so that I can call removePlayerFromGame from within DisconnectListener
    public ColorPartyMinigameListener getMinigameListener() {
        return minigameListener;
    }

    // Invisible lights (so dance floor is not dark)
    private void placeInvisibleLights(World cpWorld) {
        final World finalWorld = cpWorld;

        new BukkitRunnable() {
            // Place lights in a 64×64 area (100 block radius around (0,122,0)).
            int radius = 32;
            int xMin = -radius;
            int xMax = radius;
            int zMin = -radius;
            int zMax = radius;
            int currentX = xMin;
            int currentZ = zMin;
            int yLevel = 122; // Y coordinate where you want the light

            @Override
            public void run() {
                // Place lights in small batches to avoid lag spikes
                int blocksPerTick = 200; // Adjust as needed

                for (int i = 0; i < blocksPerTick; i++) {
                    // Place a light block at (currentX, yLevel, currentZ)
                    Block b = finalWorld.getBlockAt(currentX, yLevel, currentZ);
                    if (b.getType() != Material.LIGHT) {
                        b.setType(Material.LIGHT, false);
                        Light lightBlockData = (Light) b.getBlockData();
                        lightBlockData.setLevel(15); // Full brightness
                        b.setBlockData(lightBlockData, false);
                    }

                    // Move to the next coordinate
                    currentZ++;
                    if (currentZ > zMax) {
                        currentZ = zMin;
                        currentX++;
                        if (currentX > xMax) {
                            // We've placed all the lights in the region
                            Bukkit.getLogger().info("Completed placing invisible light blocks in a "
                                    + (radius * 2) + "×" + (radius * 2) + " area at y=" + yLevel);
                            cancel();
                            break;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 1L); // Start after 1s delay, run every tick
    }

    private void removeInvisibleLights(World cpWorld) {
        final World finalWorld = cpWorld;

        new BukkitRunnable() {
            int radius = 100;
            int xMin = -radius;
            int xMax = radius;
            int zMin = -radius;
            int zMax = radius;
            int currentX = xMin;
            int currentZ = zMin;
            int yLevel = 122;
            int blocksPerTick = 200;

            @Override
            public void run() {
                for (int i = 0; i < blocksPerTick; i++) {
                    Block b = finalWorld.getBlockAt(currentX, yLevel, currentZ);
                    if (b.getType() == Material.LIGHT) {
                        b.setType(Material.AIR, false);
                    }
                    currentZ++;
                    if (currentZ > zMax) {
                        currentZ = zMin;
                        currentX++;
                        if (currentX > xMax) {
                            Bukkit.getLogger().info("Removed invisible light blocks.");
                            cancel();
                            break;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // Build/reset the Color Party arena:
    // Clear region y=110..130, x/z ~ ±50
    // Load floor_start.json from the plugin data folder
    // and build a 64x64 dance floor at y=120 (with 180-degree rotation from the file)
    // Build bridging ring
    public void resetArena() {
        ColorPartyMinigameListener.gameEnded = false;

        getLogger().info("resetArena() called.");
        World cpWorld = Bukkit.getWorld(MINIGAME_WORLD_NAME);
        if (cpWorld == null) {
            getLogger().warning("World " + MINIGAME_WORLD_NAME + " is null in resetArena().");
            return;
        }

        // Clear only the inner region (x and z between -34 and 34)
        int minY = 110, maxY = 130;
        // Only clear blocks with X and Z between -34 and 34
        int innerRadius = 34;
        for (int y = minY; y <= maxY; y++) {
            for (int x = -innerRadius; x <= innerRadius; x++) {
                for (int z = -innerRadius; z <= innerRadius; z++) {
                    cpWorld.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        getLogger().info("Cleared inner region around y=110..130, x/z=±" + innerRadius + ".");

        // Load floor_start.json
        File layoutFile = new File(getDataFolder(), "floor_start.json");
        if (!layoutFile.exists()) {
            getLogger().warning("floor_start.json not found in data folder. Using fallback floor.");
            buildDanceFloor(cpWorld);
        } else {
            Map<Material, List<int[]>> layout = loadLayoutFromFile(layoutFile);
            if (layout == null) {
                getLogger().warning("Failed to parse floor_start.json. Using fallback floor.");
                buildDanceFloor(cpWorld);
            } else {
                buildFloorFromLayout(cpWorld, layout);
            }
        }

        // Build bridging ring
        // NO LONGER USED, JUST LOAD INCLUDED MAP FILE THAT CONTAINS SPAWN AREA BRIDGE
        // removeBridgeRing(cpWorld);
        // buildBridgeBase(cpWorld);

        // Reapply invisible lights above dance floor
        placeInvisibleLights(cpWorld);

        getLogger().info("resetArena() finished: custom floor loaded.");
    }

    // Public method to reset the custom floor externally (e.g., after last player leaves).
    public void resetCustomFloor() {
        resetArena();
    }

    // Load the JSON, parse into Map<Material, List<int[]>>.
    private Map<Material, List<int[]>> loadLayoutFromFile(File file) {
        getLogger().info("Loading layout from file: " + file.getAbsolutePath());
        try {
            JsonObject root = new Gson().fromJson(new FileReader(file), JsonObject.class);
            if (root == null) {
                getLogger().warning("JSON root is null in file " + file.getName());
                return null;
            }
            Map<Material, List<int[]>> layoutMap = new HashMap<>();
            int totalCoordsCount = 0;
            for (String key : root.keySet()) {
                Material mat = Material.matchMaterial(key);
                if (mat == null) {
                    getLogger().warning("Unknown Material in JSON: " + key);
                    continue;
                }
                JsonArray coordsArr = root.getAsJsonArray(key);
                List<int[]> coordsList = new ArrayList<>();
                for (JsonElement elem : coordsArr) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject obj = elem.getAsJsonObject();
                    if (!obj.has("x") || !obj.has("z")) continue;
                    int lx = obj.get("x").getAsInt();
                    int lz = obj.get("z").getAsInt();
                    coordsList.add(new int[] { lx, lz });
                }
                layoutMap.put(mat, coordsList);
                totalCoordsCount += coordsList.size();
                getLogger().info("Loaded " + coordsList.size() + " coordinates for " + key);
            }
            getLogger().info("Total coordinates loaded from " + file.getName() + ": " + totalCoordsCount);
            return layoutMap;
        } catch (Exception e) {
            getLogger().severe("Error parsing " + file.getName());
            e.printStackTrace();
            return null;
        }
    }

    // If file fails or is missing, fallback 64x64 floor of LIGHT_GRAY_TERRACOTTA at y=120.
    private void buildDanceFloor(World world) {
        getLogger().info("Building fallback 64x64 light gray floor at y=120.");
        int floorSize = 64;
        int startX = -floorSize / 2;
        int startZ = -floorSize / 2;
        int y = 120;
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                world.getBlockAt(x, y, z).setType(Material.LIGHT_GRAY_TERRACOTTA);
            }
        }
    }

    // Builds the floor from layout, applying a 180-degree rotation:
    // newLocalX = 63 - oldLocalX
    // newLocalZ = 63 - oldLocalZ
    // so that the "top" from the JSON is at positive Z in the game.
    private void buildFloorFromLayout(World world, Map<Material, List<int[]>> layout) {
        getLogger().info("buildFloorFromLayout(): placing blocks at y=120 from layout data, rotated 180°...");
        if (world == null) return;
        int size = 64;
        int startX = -size / 2; // -32
        int startZ = -size / 2; // -32
        int y = 120;

        // Fill 64x64 with LIGHT_GRAY first
        Material[][] floor = new Material[size][size];
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                floor[xx][zz] = Material.LIGHT_GRAY_TERRACOTTA;
            }
        }

        // Overwrite with 180-degree rotation
        for (Map.Entry<Material, List<int[]>> entry : layout.entrySet()) {
            Material mat = entry.getKey();
            for (int[] coord : entry.getValue()) {
                int lx = coord[0]; // 0..63
                int lz = coord[1]; // 0..63

                // Rotate 180-degree around center: newLocalX = 63 - lx, newLocalZ = 63 - lz
                int newLx = 63 - lx;
                int newLz = 63 - lz;

                if (newLx >= 0 && newLx < size && newLz >= 0 && newLz < size) {
                    floor[newLx][newLz] = mat;
                }
            }
        }

        // Place blocks
        int placedCount = 0;
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                int wx = startX + xx;
                int wz = startZ + zz;
                world.getBlockAt(wx, y, wz).setType(floor[xx][zz]);
                placedCount++;
            }
        }
        getLogger().info("Custom floor built with 180° rotation. Blocks placed: " + placedCount);
    }

    // Builds 8-block wide ring around floor, 4-block gap, 6-block tall glass walls
    // NO LONGER USED
    private void buildBridgeRing(World world) {
        getLogger().info("Building bridging ring around dance floor...");
        int floorSize = 64;
        int gap = 4;
        int ringWidth = 8;
        int totalOuter = floorSize + 2 * gap + 2 * ringWidth;
        int floorStart = -(floorSize / 2);
        int ringStart = floorStart - gap - ringWidth;
        int y = 120;

        for (int x = ringStart; x < ringStart + totalOuter; x++) {
            for (int z = ringStart; z < ringStart + totalOuter; z++) {
                boolean inInnerGap = (x >= floorStart - gap && x < floorStart + floorSize + gap)
                        && (z >= floorStart - gap && z < floorStart + floorSize + gap);
                if (!inInnerGap) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);
                    for (int wallY = y + 1; wallY <= y + 6; wallY++) {
                        world.getBlockAt(x, wallY, z).setType(Material.GLASS);
                    }
                }
            }
        }
        getLogger().info("Bridging ring built.");
    }

    // NO LONGER USED
    public void removeBridgeRing(World world) {
        getLogger().info("Removing bridging ring around dance floor...");
        int floorSize = 64;
        int gap = 4;
        int ringWidth = 8;
        int totalOuter = floorSize + 2 * gap + 2 * ringWidth;
        int floorStart = -(floorSize / 2);
        int ringStart = floorStart - gap - ringWidth;
        int y = 120;

        for (int x = ringStart; x < ringStart + totalOuter; x++) {
            for (int z = ringStart; z < ringStart + totalOuter; z++) {
                // Determine if this position is within the inner gap (i.e. not part of the bridge)
                boolean inInnerGap = (x >= floorStart - gap && x < floorStart + floorSize + gap)
                        && (z >= floorStart - gap && z < floorStart + floorSize + gap);
                if (!inInnerGap) {
                    // Remove the base block and the glass wall above it
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                    for (int wallY = y + 1; wallY <= y + 6; wallY++) {
                        world.getBlockAt(x, wallY, z).setType(Material.AIR);
                    }
                }
            }
        }
        getLogger().info("Bridging ring removed.");
    }

    public void buildBridgeBase(World world) {
        getLogger().info("Building bridge base (quartz block) around dance floor...");
        int floorSize = 64;
        int gap = 4;
        int ringWidth = 12;
        int totalOuter = floorSize + 2 * gap + 2 * ringWidth;
        int floorStart = -(floorSize / 2);
        int ringStart = floorStart - gap - ringWidth;
        int y = 120;

        for (int x = ringStart; x < ringStart + totalOuter; x++) {
            for (int z = ringStart; z < ringStart + totalOuter; z++) {
                boolean inInnerGap = (x >= floorStart - gap && x < floorStart + floorSize + gap)
                        && (z >= floorStart - gap && z < floorStart + floorSize + gap);
                if (!inInnerGap) {
                    // Place quartz blocks for 3 block heights
                    for (int height = y; height < y + 3; height++) {
                        world.getBlockAt(x, height, z).setType(Material.QUARTZ_BLOCK);
                    }
                }
            }
        }
        getLogger().info("Bridge base built.");
    }

    // Inventory handling
    public void storeAndClearInventory(Player player) {
        UUID uuid = player.getUniqueId();
        mainWorldInventories.put(uuid, player.getInventory().getContents());
        mainWorldArmor.put(uuid, player.getInventory().getArmorContents());

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        // "Start" emerald in slot 0
        ItemStack emerald = new ItemStack(Material.EMERALD, 1);
        var emMeta = emerald.getItemMeta();
        if (emMeta != null) {
            emMeta.setDisplayName("Start Normal Mode");
            emerald.setItemMeta(emMeta);
        }
        player.getInventory().setItem(0, emerald);

        // "Start Crazy Mode" shard in slot 1
        ItemStack shard = new ItemStack(Material.AMETHYST_SHARD, 1);
        var shardMeta = shard.getItemMeta();
        if (shardMeta != null) {
            shardMeta.setDisplayName("Start Crazy Mode");
            shard.setItemMeta(shardMeta);
        }
        player.getInventory().setItem(1, shard);

        // "Exit" bed in slot 8
        ItemStack bed = new ItemStack(Material.WHITE_BED, 1);
        var bedMeta = bed.getItemMeta();
        if (bedMeta != null) {
            bedMeta.setDisplayName("Exit");
            bed.setItemMeta(bedMeta);
        }
        player.getInventory().setItem(8, bed);

        // Select index 2 (the third inventory position) because it is empty
        player.getInventory().setHeldItemSlot(2);
    }

    public void restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        if (mainWorldInventories.containsKey(uuid)) {
            player.getInventory().setContents(mainWorldInventories.get(uuid));
            mainWorldInventories.remove(uuid);
        }
        if (mainWorldArmor.containsKey(uuid)) {
            player.getInventory().setArmorContents(mainWorldArmor.get(uuid));
            mainWorldArmor.remove(uuid);
        }
    }
}
