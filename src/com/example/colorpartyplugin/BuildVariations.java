package com.example.colorpartyplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.*;

public class BuildVariations {
    // Static plugin reference
    private static ColorPartyPlugin plugin;

    // Random generator for beacons, floors, colors, etc.
    private static final Random random = new Random();

    // Setter to initialize the plugin reference
    public static void setPlugin(ColorPartyPlugin instance) {
        plugin = instance;
    }

    public static Set<Material> buildVariationRandomScatter(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(16);
        Set<Material> used = new HashSet<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                Material c = colors[random.nextInt(colors.length)];
                w.getBlockAt(sx + x, y, sz + z).setType(c);
                used.add(c);
            }
        }
        return used;
    }

    public static Set<Material> buildVariation4x4Squares(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(12);
        Set<Material> used = new HashSet<>();
        int sq = 4;
        for (int bx = 0; bx < size; bx += sq) {
            for (int bz = 0; bz < size; bz += sq) {
                Material color = colors[random.nextInt(colors.length)];
                used.add(color);
                for (int dx = 0; dx < sq; dx++) {
                    for (int dz = 0; dz < sq; dz++) {
                        w.getBlockAt(sx + bx + dx, y, sz + bz + dz).setType(color);
                    }
                }
            }
        }
        return used;
    }

    public static Set<Material> buildVariationCircles(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(5);
        Set<Material> used = new HashSet<>();
        Material bg = colors[random.nextInt(colors.length)];
        used.add(bg);
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                w.getBlockAt(sx + xx, y, sz + zz).setType(bg);
            }
        }

        int circleCount = 60;
        int radius = 2;
        for (int i = 0; i < circleCount; i++) {
            int cx = sx + radius + random.nextInt(size - radius * 2);
            int cz = sz + radius + random.nextInt(size - radius * 2);

            Material circleColor = colors[random.nextInt(colors.length)];
            used.add(circleColor);
            Material ringColor = colors[random.nextInt(colors.length)];
            used.add(ringColor);

            for (int xx = cx - radius; xx <= cx + radius; xx++) {
                for (int zz = cz - radius; zz <= cz + radius; zz++) {
                    double dist = Math.sqrt(Math.pow(xx - cx, 2) + Math.pow(zz - cz, 2));
                    if (dist <= radius - 0.5) {
                        w.getBlockAt(xx, y, zz).setType(circleColor);
                    } else if (dist <= radius + 0.4) {
                        w.getBlockAt(xx, y, zz).setType(ringColor);
                    }
                }
            }
        }
        return used;
    }

    public static Set<Material> buildVariationDiagonalStripes(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(9);
        Set<Material> used = new HashSet<>();
        int stripeWidth = random.nextInt(2) + 4; // 4-5
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int stripeIndex = (x + z) / stripeWidth;
                Material c = colors[stripeIndex % colors.length];
                used.add(c);
                w.getBlockAt(sx + x, y, sz + z).setType(c);
            }
        }
        return used;
    }

    public static Set<Material> buildVariationOneWideDiagonal(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(16);
        Set<Material> used = new HashSet<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int diag = Math.abs(x - z);
                Material c = colors[diag % colors.length];
                used.add(c);
                w.getBlockAt(sx + x, y, sz + z).setType(c);
            }
        }
        return used;
    }

    public static Set<Material> buildVariationOverlappingCircles(World w, int sx, int sz, int size, int y) {
        Material[] colors = pickClayColors(9);
        Set<Material> used = new HashSet<>();
        int centerX = sx + size / 2;
        int centerZ = sz + size / 2;

        Material bg = colors[0];
        used.add(bg);
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                w.getBlockAt(sx + xx, y, sz + zz).setType(bg);
            }
        }
        int[] radii = {30, 20, 10, 5};
        for (int i = 0; i < radii.length; i++) {
            Material circleColor = colors[Math.min(i + 1, colors.length - 1)];
            used.add(circleColor);
            int r = radii[i];
            for (int xx = centerX - r; xx <= centerX + r; xx++) {
                for (int zz = centerZ - r; zz <= centerZ + r; zz++) {
                    if (xx < sx || xx >= sx + size || zz < sz || zz >= sz + size) {
                        continue;
                    }
                    double dist = Math.sqrt(Math.pow(xx - centerX, 2) + Math.pow(zz - centerZ, 2));
                    if (dist <= r) {
                        w.getBlockAt(xx, y, zz).setType(circleColor);
                    }
                }
            }
        }
        return used;
    }

    public static Set<Material> buildVariationSquareStripes(World w, int sx, int sz, int size, int y) {
        Set<Material> used = new HashSet<>();

        // Define a 4×4 "pattern grid" of colors (each cell = 4×4 blocks).
        // Replicate this 16×16 pattern grid 4 times in each direction
        // to fill the 64×64 floor.
        Material[][] pattern = {
                {Material.BROWN_TERRACOTTA,  Material.ORANGE_TERRACOTTA, Material.PINK_TERRACOTTA,    Material.PURPLE_TERRACOTTA},
                {Material.GREEN_TERRACOTTA, Material.GRAY_TERRACOTTA,   Material.PINK_TERRACOTTA,    Material.PURPLE_TERRACOTTA},
                {Material.BROWN_TERRACOTTA,  Material.ORANGE_TERRACOTTA, Material.PINK_TERRACOTTA,    Material.PURPLE_TERRACOTTA},
                {Material.GREEN_TERRACOTTA, Material.GRAY_TERRACOTTA,   Material.PINK_TERRACOTTA,    Material.PURPLE_TERRACOTTA}
        };

        // Each of the 16 cells in "pattern" is itself 4×4 blocks,
        // so one pass = 16×16. Replicate that 4 times horizontally
        // and 4 times vertically => 64×64 total.

        for (int patZ = 0; patZ < 4; patZ++) {
            for (int patX = 0; patX < 4; patX++) {
                Material color = pattern[patZ][patX];
                used.add(color);

                // Fill the 4×4 cell
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {

                        // Local position in the 16×16 sub-grid
                        int blockX = patX * 4 + dx;
                        int blockZ = patZ * 4 + dz;

                        // Replicate that sub-grid across the 64×64 area
                        for (int repeatX = 0; repeatX < 4; repeatX++) {
                            for (int repeatZ = 0; repeatZ < 4; repeatZ++) {

                                // Each repeat block is offset by 16 in each direction
                                int finalX = blockX + repeatX * 16;
                                int finalZ = blockZ + repeatZ * 16;

                                w.getBlockAt(sx + finalX, y, sz + finalZ).setType(color);
                            }
                        }
                    }
                }
            }
        }
        return used;
    }

    public static Set<Material> buildVariationStripes8x8Repeat(World w, int sx, int sz, int size, int y) {
        Set<Material> used = new HashSet<>();

        // Define 8 stripes: 4 color pairs repeated twice
        Material[][] stripes = {
                {Material.BLACK_TERRACOTTA,    Material.GRAY_TERRACOTTA},
                {Material.PURPLE_TERRACOTTA,   Material.MAGENTA_TERRACOTTA},
                {Material.GREEN_TERRACOTTA,    Material.LIME_TERRACOTTA},
                {Material.BLUE_TERRACOTTA,     Material.LIGHT_BLUE_TERRACOTTA},

                // Repeat the same 4 pairs a second time
                {Material.BLACK_TERRACOTTA,    Material.GRAY_TERRACOTTA},
                {Material.PURPLE_TERRACOTTA,   Material.MAGENTA_TERRACOTTA},
                {Material.GREEN_TERRACOTTA,    Material.LIME_TERRACOTTA},
                {Material.BLUE_TERRACOTTA,     Material.LIGHT_BLUE_TERRACOTTA}
        };

        // 8 stripes horizontally, each stripe is 8 blocks wide, 64 total
        // Each vertical "square" is 8 blocks high, 8 squares, 64 total
        for (int stripeIndex = 0; stripeIndex < 8; stripeIndex++) {
            // For each vertical square j in [0..7], pick color based on even/odd
            for (int sqY = 0; sqY < 8; sqY++) {
                Material color = (sqY % 2 == 0)
                        ? stripes[stripeIndex][0]
                        : stripes[stripeIndex][1];
                used.add(color);

                // Top-left corner of this 8×8 block
                int blockStartX = sx + (stripeIndex * 8);
                int blockStartZ = sz + (sqY * 8);

                // Fill an 8×8 region
                for (int dx = 0; dx < 8; dx++) {
                    for (int dz = 0; dz < 8; dz++) {
                        w.getBlockAt(blockStartX + dx, y, blockStartZ + dz).setType(color);
                    }
                }
            }
        }
        return used;
    }

    // Helper method to load layout from a JSON file.
    // Retains logging messages that include the file name.
    private static Map<Material, List<int[]>> loadLayoutFromJson(File file, String fileName) {
        plugin.getLogger().info("Loading layout from file: " + file.getAbsolutePath());
        try {
            JsonObject root = new Gson().fromJson(new FileReader(file), JsonObject.class);
            if (root == null) {
                plugin.getLogger().warning(fileName + " parse returned null. Fallback to light gray.");
                return null;
            }
            Map<Material, List<int[]>> layoutMap = new HashMap<>();
            int totalCoordsCount = 0;
            for (String key : root.keySet()) {
                Material mat = Material.matchMaterial(key);
                if (mat == null) {
                    plugin.getLogger().warning("Unknown Material in " + fileName + ": " + key);
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
                // Only store the material if it has at least one coordinate.
                if (!coordsList.isEmpty()) {
                    layoutMap.put(mat, coordsList);
                    totalCoordsCount += coordsList.size();
                    plugin.getLogger().info("Loaded " + coordsList.size() + " coordinates for " + key + " from " + fileName);
                }
            }
            plugin.getLogger().info("Total coordinates loaded from " + fileName + ": " + totalCoordsCount);
            return layoutMap;
        } catch (Exception e) {
            plugin.getLogger().severe("Error reading " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    // This helper method contains the shared logic for clearing the region,
    // creating the floor array, rotating coordinates, and placing blocks.
    // The only parameter that changes per variation is the JSON file name.
    private static Set<Material> buildVariationJsonFloorWithFile(World w, int sx, int sz, int size, int y, String jsonFileName) {
        Set<Material> used = new HashSet<>();

        // Attempt to load the JSON file from the plugin folder.
        File layoutFile = new File(plugin.getDataFolder(), jsonFileName); // adjust path if needed
        if (!layoutFile.exists()) {
            plugin.getLogger().warning(jsonFileName + " not found for buildVariationJsonFloor. Using fallback.");
            fillLightGrayFallback(w, sx, sz, size, y);
            return used; // return empty set
        }

        // Parse the JSON into a map: Material -> List of {x, z} coordinates.
        Map<Material, List<int[]>> layoutMap = loadLayoutFromJson(layoutFile, jsonFileName);
        if (layoutMap == null || layoutMap.isEmpty()) {
            plugin.getLogger().warning(jsonFileName + " returned an empty layout. Using fallback.");
            fillLightGrayFallback(w, sx, sz, size, y);
            return used;
        }

        // Clear the 64×64 region.
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                w.getBlockAt(sx + xx, y, sz + zz).setType(Material.AIR);
            }
        }

        // Create a 2D array for the floor, defaulting to LIGHT_GRAY_TERRACOTTA.
        Material[][] floor = new Material[size][size];
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                floor[xx][zz] = Material.LIGHT_GRAY_TERRACOTTA;
            }
        }

        // Overwrite the floor array using the JSON layout with a 180-degree rotation.
        // For each coordinate (lx, lz) from JSON, calculate new positions:
        // newX = size - 1 - lx, newZ = size - 1 - lz.
        for (Map.Entry<Material, List<int[]>> entry : layoutMap.entrySet()) {
            Material mat = entry.getKey();
            // Add this material to the used set
            used.add(mat);
            for (int[] coord : entry.getValue()) {
                int lx = coord[0];
                int lz = coord[1];
                if (lx >= 0 && lx < size && lz >= 0 && lz < size) {
                    int newX = size - 1 - lx;
                    int newZ = size - 1 - lz;
                    floor[newX][newZ] = mat;
                }
            }
        }

        // Place the floor array into the world.
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                w.getBlockAt(sx + xx, y, sz + zz).setType(floor[xx][zz]);
            }
        }

        return used;
    }

    // JSON FLOORS
    public static Set<Material> buildVariationGameOver(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_gameover.json");
    }

    public static Set<Material> buildVariationJson1(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_variation_1.json");
    }

    public static Set<Material> buildVariationJsonHearts(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_hearts.json");
    }

    public static Set<Material> buildVariationJsonCarrots(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_carrots.json");
    }

    public static Set<Material> buildVariationJsonTurtles(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_turtles.json");
    }

    public static Set<Material> buildVariationJsonConcentricSquares(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_concentric_squares.json");
    }

    public static Set<Material> buildVariationJsonConnectedRings(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_connected_rings.json");
    }

    public static Set<Material> buildVariationJsonStars(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_stars.json");
    }

    public static Set<Material> buildVariationJson4SectionBricks(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_4section_bricks.json");
    }

    public static Set<Material> buildVariationJson9Squares(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_9squares.json");
    }

    public static Set<Material> buildVariationJson3Shapes(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_3shapes.json");
    }

    public static Set<Material> buildVariationJson5Circles(World w, int sx, int sz, int size, int y) {
        return buildVariationJsonFloorWithFile(w, sx, sz, size, y, "floor_5circles.json");
    }


    //Fallback method to fill the entire 64×64 region with LIGHT_GRAY_TERRACOTTA.
    public static void fillLightGrayFallback(World w, int sx, int sz, int size, int y) {
        for (int xx = 0; xx < size; xx++) {
            for (int zz = 0; zz < size; zz++) {
                w.getBlockAt(sx + xx, y, sz + zz).setType(Material.LIGHT_GRAY_TERRACOTTA);
            }
        }
    }

    // pickClayColors: returns up to 'count' random terracotta from the 16 variants
    public static Material[] pickClayColors(int count) {
        Material[] fullSet = {
                Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA,
                Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
                Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
                Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
                Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA,
                Material.BLACK_TERRACOTTA
        };
        List<Material> list = new ArrayList<>(Arrays.asList(fullSet));
        Collections.shuffle(list, random);
        if (count > list.size()) count = list.size();
        return list.subList(0, count).toArray(new Material[0]);
    }
}
