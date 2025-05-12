package com.example.colorpartyplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 CrazyEffectManager controls four "crazy mode" effects:
 1) Snow effect (makes it hard to see dance floor)
 2) Rolling colors
 3) Wither bombs
 4) Chicken jockey with color trail

 In #3, bombs are real WitherSkull projectiles:
 - No bridging damage (blocked in onEntityExplode).
 - The color splash occurs if the projectile hits within the 64x64 floor.
 - Withers are AI-off, but we manually move them in small teleports.

 */
public class CrazyEffectManager implements Listener {

    // Start a random effect (#1, #2, or #3)
    public static void startRandomEffect(
            ColorPartyPlugin plugin,
            int floorSize, // e.g. 64
            int startX,    // e.g. -32
            int startZ,    // e.g. -32
            int yFloor,    // e.g. 120
            int roundTicks
    ) {
        int effectId = new Random().nextInt(4);
        switch (effectId) {
            case 0 -> startSnowEffect(plugin, floorSize, startX, startZ, yFloor, roundTicks);
            case 1 -> startRollingEffect(plugin, floorSize, startX, startZ, yFloor, roundTicks);
            case 2 -> startWitherEffect(plugin, floorSize, startX, startZ, yFloor);
            case 3 -> startChickenJockeyEffect(plugin, floorSize, startX, startZ, yFloor);
        }
    }

    // EFFECT #1: SNOW LAYERS
    private static BukkitRunnable activeSnowTask = null;

    public static void startSnowEffect(
            ColorPartyPlugin plugin,
            int floorSize, int startX, int startZ, int yFloor,
            int roundTicks
    ) {
        stopSnowEffect();
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        // 16 intervals, each placing 128 snow => total 2048
        int intervals = 16;
        int snowPerInterval = 128;
        int intervalTicks = roundTicks / intervals;

        activeSnowTask = new BukkitRunnable() {
            int count = 0;
            @Override
            public void run() {
                if (count >= intervals) {
                    this.cancel();
                    return;
                }
                placeSomeSnow(cpWorld, floorSize, startX, startZ, yFloor, snowPerInterval);
                count++;
            }
        };
        activeSnowTask.runTaskTimer(plugin, 0L, intervalTicks);
    }

    private static void placeSomeSnow(World world, int size, int sx, int sz, int y, int amount) {
        Random random = new Random();
        int placed = 0;
        int attempts = 0;
        while (placed < amount && attempts < amount * 10) {
            int rx = sx + random.nextInt(size);
            int rz = sz + random.nextInt(size);

            Block above = world.getBlockAt(rx, y + 1, rz);
            if (above.getType() == Material.AIR) {
                Block floorBlock = world.getBlockAt(rx, y, rz);
                if (floorBlock.getType() != Material.BEACON) {
                    above.setType(Material.SNOW);
                    placed++;
                }
            }
            attempts++;
        }
    }

    public static void stopSnowEffect() {
        if (activeSnowTask != null) {
            activeSnowTask.cancel();
            activeSnowTask = null;
        }
        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        // Remove snow from the 64x64 floor
        int floorSize = 64;
        int startX = -32;
        int startZ = -32;
        int yFloor = 120;
        for (int x = startX; x < startX + floorSize; x++) {
            for (int z = startZ; z < startZ + floorSize; z++) {
                Block above = cpWorld.getBlockAt(x, yFloor + 1, z);
                if (above.getType() == Material.SNOW) {
                    above.setType(Material.AIR);
                }
            }
        }
    }

    // EFFECT #2: ROLLING COLORS
    private static BukkitRunnable currentEffectTask = null;

    public static void startRollingEffect(
            ColorPartyPlugin plugin,
            int floorSize,
            int startX,
            int startZ,
            int yFloor,
            int totalDurationTicks
    ) {
        stopRollingEffect();

        currentEffectTask = new BukkitRunnable() {
            int elapsedTicks = 0;
            @Override
            public void run() {
                elapsedTicks += 10; // runs 2 times per second

                World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
                if (cpWorld == null) {
                    stopRollingEffect();
                    return;
                }

                // SHIFT entire row east
                for (int z = startZ; z < startZ + floorSize; z++) {
                    int farEastX = startX + floorSize - 1;
                    Block eastBlock = cpWorld.getBlockAt(farEastX, yFloor, z);
                    Material savedMat = eastBlock.getType();

                    for (int x = farEastX - 1; x >= startX; x--) {
                        Block current = cpWorld.getBlockAt(x, yFloor, z);
                        Block dest = cpWorld.getBlockAt(x + 1, yFloor, z);
                        dest.setType(current.getType());
                    }
                    cpWorld.getBlockAt(startX, yFloor, z).setType(savedMat);
                }
            }
        };
        currentEffectTask.runTaskTimer(plugin, 0L, 10L);
    }

    public static void stopRollingEffect() {
        if (currentEffectTask != null) {
            currentEffectTask.cancel();
            currentEffectTask = null;
        }
    }

    // EFFECT #3: WITHER BOMBS
    private static final List<Wither> spawnedWithers = new ArrayList<>();
    private static BukkitRunnable witherMoveTask = null;
    private static BukkitRunnable bombTask = null;

    // For identifying our color bomb projectiles
    private static final Set<WitherSkull> colorProjectiles =
            Collections.synchronizedSet(new HashSet<>());

    // Spawns 4 Withers overhead (AI off) that:
    // - drift around the entire 64x64 region at yFloor+10
    // - shoot color bombs every 2 seconds
    // - bombs skip the outer 5 blocks when picking an impact target
    // - bridging remains safe from damage
    public static void startWitherEffect(
            ColorPartyPlugin plugin,
            int floorSize, int startX, int startZ, int yFloor
    ) {
        stopWitherEffect();

        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        spawnedWithers.clear();
        colorProjectiles.clear();

        Random rand = new Random();
        int witherCount = 4;
        int witherY = yFloor + 10; // e.g. 130 if floor=120

        // Spawn 4 withers in random x,z within the 64x64
        for (int i = 0; i < witherCount; i++) {
            int rx = startX + rand.nextInt(floorSize);
            int rz = startZ + rand.nextInt(floorSize);
            Location loc = new Location(cpWorld, rx + 0.5, witherY, rz + 0.5);

            Wither w = (Wither) cpWorld.spawnEntity(loc, EntityType.WITHER);
            if (w == null) continue;

            w.setAI(false); // no player chasing
            w.setCustomName("Color Wither");
            w.setCustomNameVisible(true);

            spawnedWithers.add(w);
        }

        // Movement => manually teleport every 5 ticks for smoother drift
        witherMoveTask = new BukkitRunnable() {
            // A "speed" factor: how many blocks to move each cycle
            final double speed = 0.6;
            final Map<UUID, Location> targets = new HashMap<>();

            @Override
            public void run() {
                for (Wither w : spawnedWithers) {
                    if (w.isDead()) continue;

                    // Pick or keep a random target in the 64x64 overhead
                    targets.putIfAbsent(w.getUniqueId(),
                            pickRandomWitherTarget(rand, cpWorld, floorSize, startX, startZ, witherY));

                    Location target = targets.get(w.getUniqueId());
                    Location curr   = w.getLocation();

                    // If near target, pick new
                    if (curr.distanceSquared(target) < 4.0) {
                        target = pickRandomWitherTarget(rand, cpWorld, floorSize, startX, startZ, witherY);
                        targets.put(w.getUniqueId(), target);
                    }

                    // Direction from current to target
                    Vector dir = target.toVector().subtract(curr.toVector());
                    double lenSqr = dir.lengthSquared();
                    if (lenSqr > 1.0) {
                        dir.normalize().multiply(speed);
                    } else {
                        // If almost arrived, skip
                        continue;
                    }

                    // Manually teleportL ensures noticeable movement
                    double newX = curr.getX() + dir.getX();
                    double newZ = curr.getZ() + dir.getZ();
                    // Keep Y the same
                    Location newLoc = new Location(cpWorld, newX, witherY, newZ,
                            curr.getYaw(), curr.getPitch());
                    w.teleport(newLoc);
                }
            }
        };
        // Run every 5 ticks: 4 times/sec for smoother movement
        witherMoveTask.runTaskTimer(plugin, 0L, 5L);

        // Bomb task, every 2s, 40 ticks
        bombTask = new BukkitRunnable() {
            int shooterIndex = 0;
            @Override
            public void run() {
                if (spawnedWithers.isEmpty()) return;
                if (shooterIndex >= spawnedWithers.size()) {
                    shooterIndex = 0;
                }
                Wither shooter = spawnedWithers.get(shooterIndex);
                if (!shooter.isDead()) {
                    // shoot a bomb => skipping outer 5 blocks
                    spawnColorBombProjectile(shooter, cpWorld, floorSize, startX, startZ, yFloor);
                }
                shooterIndex++;
            }
        };
        bombTask.runTaskTimer(plugin, 0L, 40L);
    }

    // pickRandomWitherTarget => any coordinate within the full 64x64 region,
    // so withers roam freely above the entire floor
    private static Location pickRandomWitherTarget(
            Random rand, World w,
            int floorSize, int sx, int sz, int witherY
    ) {
        int rx = sx + rand.nextInt(floorSize);
        int rz = sz + rand.nextInt(floorSize);
        return new Location(w, rx + 0.5, witherY, rz + 0.5);
    }

    // Actually spawn a real WitherSkull projectile from the wither's location,
    // but skipping the outer 5 blocks => bombs land in [sx+5.. sx+floorSize-6].
    private static void spawnColorBombProjectile(
            Wither shooter, World world,
            int floorSize, int sx, int sz, int yFloor
    ) {
        if (shooter == null || shooter.isDead()) return;

        Random rand = new Random();
        if (floorSize <= 10) {
            // Safety check => can't skip outer 5 blocks if floor <= 10
            return;
        }

        // Choose x in [sx+5 .. sx+floorSize-6]
        int innerWidth = floorSize - 10; // skipping 5 on each side
        int tx = sx + 5 + rand.nextInt(innerWidth);

        // Same for z
        int tz = sz + 5 + rand.nextInt(innerWidth);

        Location target = new Location(world, tx + 0.5, yFloor, tz + 0.5);

        // Spawn a wither skull at the wither's location
        Location startLoc = shooter.getLocation().clone().add(0, 1.5, 0);
        WitherSkull skull = (WitherSkull) world.spawnEntity(startLoc, EntityType.WITHER_SKULL);
        if (skull == null) return;

        colorProjectiles.add(skull);
        skull.setShooter(shooter);

        // Aim velocity
        Vector dir = target.toVector().subtract(startLoc.toVector());
        if (dir.lengthSquared() > 0.01) {
            dir = dir.normalize().multiply(1.2);
        }
        skull.setVelocity(dir);
    }

    // Color bomb, picks random terracotta
    private static final Material[] TERRACOTTA_COLORS = {
            Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA,
            Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
            Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
            Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
            Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA,
            Material.BLACK_TERRACOTTA
    };

    private static Material randomTerracotta(Random rand) {
        return TERRACOTTA_COLORS[rand.nextInt(TERRACOTTA_COLORS.length)];
    }

    // color a 3-block radius, clamp Y=120 so floor is always recolored
    private static void splashColorBomb(
            World world, Location impact,
            int floorSize, int sx, int sz
    ) {
        impact.setY(120.0);

        int ix = impact.getBlockX();
        int iz = impact.getBlockZ();
        // Skip if outside floor region => bridging is safe
        if (ix < sx || ix >= sx + floorSize || iz < sz || iz >= sz + floorSize) {
            return;
        }

        int radius = 3;
        Random rand = new Random();
        for (int x = ix - radius; x <= ix + radius; x++) {
            for (int z = iz - radius; z <= iz + radius; z++) {
                double dist = Math.sqrt((x - ix)*(x - ix) + (z - iz)*(z - iz));
                if (dist <= radius + 0.5) {
                    if (x < sx || x >= sx + floorSize || z < sz || z >= sz + floorSize) {
                        continue;
                    }
                    Block b = world.getBlockAt(x, 120, z);
                    if (b.getType() != Material.AIR) {
                        b.setType(randomTerracotta(rand));
                    }
                }
            }
        }
    }

    // STOPPING / CLEANUP
    public static void stopWitherEffect() {
        if (witherMoveTask != null) {
            witherMoveTask.cancel();
            witherMoveTask = null;
        }
        if (bombTask != null) {
            bombTask.cancel();
            bombTask = null;
        }
        for (Wither w : spawnedWithers) {
            if (!w.isDead()) {
                w.remove();
            }
        }
        spawnedWithers.clear();
        colorProjectiles.clear();
    }


    private static final List<JockeyPair> chickenJockeys = new ArrayList<>();
    private static BukkitRunnable jockeyMoveTask = null;

    // Simple container class for one Chicken + its rider Zombie.
    // We track both, so we can despawn them together
    private static class JockeyPair {
        public final org.bukkit.entity.Chicken chicken;
        public final org.bukkit.entity.Zombie rider;

        public JockeyPair(org.bukkit.entity.Chicken chicken, org.bukkit.entity.Zombie rider) {
            this.chicken = chicken;
            this.rider = rider;
        }
    }

    // Spawns 7 chicken jockeys, each constantly running around
    // with a color-trail effect. If they get close to a player, knock the player back
    public static void startChickenJockeyEffect(
            ColorPartyPlugin plugin,
            int floorSize, int startX, int startZ, int yFloor
    ) {
        stopChickenJockeyEffect(); // clean up any old effect

        World cpWorld = Bukkit.getWorld(ColorPartyPlugin.MINIGAME_WORLD_NAME);
        if (cpWorld == null) return;

        Random rand = new Random();
        int spawnY = yFloor + 1; // just above the floor
        int numJockeys = 7;      // Now 7 chicken jockeys

        // Spawn the jockeys
        for (int i = 0; i < numJockeys; i++) {
            int rx = startX + rand.nextInt(floorSize);
            int rz = startZ + rand.nextInt(floorSize);
            Location loc = new Location(cpWorld, rx + 0.5, spawnY, rz + 0.5);

            // Spawn a chicken
            org.bukkit.entity.Chicken chicken = (org.bukkit.entity.Chicken) cpWorld.spawnEntity(loc, EntityType.CHICKEN);
            if (chicken == null) continue;

            // Spawn a baby zombie
            org.bukkit.entity.Zombie zombie = (org.bukkit.entity.Zombie) cpWorld.spawnEntity(loc, EntityType.ZOMBIE);
            if (zombie == null) {
                chicken.remove();
                continue;
            }
            zombie.setBaby(true);
            zombie.setAI(true);

            // Give them a Speed potion effect to move faster
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 2, false, false));

            // Put the baby zombie on the chicken
            chicken.addPassenger(zombie);

            // Store both references so we can despawn them together
            chickenJockeys.add(new JockeyPair(chicken, zombie));
        }

        // Create a repeating task to constantly give them new targets & do color-trail
        jockeyMoveTask = new BukkitRunnable() {
            // Map each jockey (rider) to its current target location
            private final Map<UUID, Location> targets = new HashMap<>();
            // Increase the velocity multiplier (faster movement)
            private final double baseSpeed = 0.6;
            private final double knockbackStrength = 1.2;

            @Override
            public void run() {
                if (chickenJockeys.isEmpty()) {
                    this.cancel();
                    return;
                }

                Iterator<JockeyPair> it = chickenJockeys.iterator();
                while (it.hasNext()) {
                    JockeyPair pair = it.next();
                    org.bukkit.entity.Zombie rider = pair.rider;
                    org.bukkit.entity.Chicken chicken = pair.chicken;

                    if (rider == null || rider.isDead() || chicken == null || chicken.isDead()) {
                        // If either entity is gone, remove from the list
                        if (rider != null && !rider.isDead()) {
                            rider.remove();
                        }
                        if (chicken != null && !chicken.isDead()) {
                            chicken.remove();
                        }
                        it.remove();
                        continue;
                    }

                    Location currLoc = rider.getLocation();

                    // If not in the minigame world, remove them
                    if (!ColorPartyPlugin.MINIGAME_WORLD_NAME.equals(currLoc.getWorld().getName())) {
                        rider.remove();
                        chicken.remove();
                        it.remove();
                        continue;
                    }

                    // Color trail: EVERY block they step on changes color
                    if (currLoc.getY() >= yFloor) {
                        Block below = currLoc.getWorld().getBlockAt(currLoc.getBlockX(), yFloor, currLoc.getBlockZ());
                        Material oldColor = below.getType();

                        // Only recolor if it's not air (or not something you want to skip)
                        if (oldColor != Material.AIR) {
                            // Pick a random color
                            Material[] terras = {
                                    Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA,
                                    Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
                                    Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
                                    Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
                                    Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA,
                                    Material.BLACK_TERRACOTTA
                            };

                            Material newColor = terras[rand.nextInt(terras.length)];
                            // If the new color is the same as the old one, pick again
                            int tries = 0;
                            while (newColor == oldColor && tries < 16) {
                                newColor = terras[rand.nextInt(terras.length)];
                                tries++;
                            }

                            below.setType(newColor);
                        }
                    }

                    // Acquire or maintain a random target in the 64×64 region
                    UUID riderId = rider.getUniqueId();
                    targets.putIfAbsent(riderId,
                            pickRandomTarget(rand, currLoc.getWorld(), floorSize, startX, startZ, yFloor));

                    Location targetLoc = targets.get(riderId);
                    double dist = currLoc.distanceSquared(targetLoc);

                    // If the jockey is close, pick a new target
                    if (dist < 2.0) {
                        targetLoc = pickRandomTarget(rand, currLoc.getWorld(), floorSize, startX, startZ, yFloor);
                        targets.put(riderId, targetLoc);
                    }

                    // Move them toward the target
                    Vector dir = targetLoc.toVector().subtract(currLoc.toVector());
                    if (dir.lengthSquared() > 0.5) {
                        dir.normalize().multiply(baseSpeed);
                        // Zero out vertical to keep them from jumping
                        dir.setY(0);
                        // The rider is controlling the chicken from the server's perspective,
                        // so setting velocity on the rider often moves the pair
                        rider.setVelocity(dir);
                    }

                    // Check for nearby players to knock back
                    for (Player p : currLoc.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(currLoc) < 2.0) {
                            // Push them away from the jockey
                            Vector push = p.getLocation().toVector().subtract(currLoc.toVector()).normalize();
                            push.setY(0.35); // small vertical bump
                            push.multiply(knockbackStrength);
                            p.setVelocity(push);
                        }
                    }
                }
            }
        };
        jockeyMoveTask.runTaskTimer(plugin, 0L, 10L); // update every 10 ticks (~0.5s)
    }

    // Pick a random location within the 64×64 floor region
    private static Location pickRandomTarget(
            Random rand, World w,
            int floorSize, int sx, int sz, int yFloor
    ) {
        int rx = sx + rand.nextInt(floorSize);
        int rz = sz + rand.nextInt(floorSize);
        return new Location(w, rx + 0.5, yFloor + 1, rz + 0.5);
    }

    // Stop the chicken jockey effect. Cancel the movement task, then wait 2 seconds
    // so they can start falling, and remove both chickens and zombies.
    public static void stopChickenJockeyEffect() {
        if (jockeyMoveTask != null) {
            jockeyMoveTask.cancel();
            jockeyMoveTask = null;
        }

        // Disable AI so they don't keep moving
        for (JockeyPair pair : chickenJockeys) {
            if (pair.rider != null && !pair.rider.isDead()) {
                pair.rider.setAI(false);
            }
            if (pair.chicken != null && !pair.chicken.isDead()) {
                pair.chicken.setAI(false);
            }
        }
    }

    public static void stopChickenJockeyEffect2() {
        for (JockeyPair pair : chickenJockeys) {
            if (pair.rider != null && !pair.rider.isDead()) {
                pair.rider.remove();
            }
            if (pair.chicken != null && !pair.chicken.isDead()) {
                pair.chicken.remove();
            }
        }
        chickenJockeys.clear();
    }


    public static void stopAllEffects() {
        stopSnowEffect();
        stopRollingEffect();
        stopWitherEffect();
        stopChickenJockeyEffect();
        stopChickenJockeyEffect2();
    }

    // LISTENERS: block wither bomb damage & color the floor
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        // If the exploding entity is one of our color bombs, remove block damage
        if (e.getEntity() instanceof WitherSkull skull) {
            if (colorProjectiles.contains(skull)) {
                e.blockList().clear();
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        // If it's our color projectile => color the floor
        if (e.getEntity() instanceof WitherSkull skull) {
            if (colorProjectiles.remove(skull)) {
                World w = e.getEntity().getWorld();
                Location impactLoc = e.getEntity().getLocation();

                // Hard-code: floor is 64 wide, from (-32,-32) up to (31,31)
                int floorSize = 64;
                int sx = -32;
                int sz = -32;

                splashColorBomb(w, impactLoc, floorSize, sx, sz);
            }
        }
    }
}
