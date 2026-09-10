package com.gtnhspeedrun.splash;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Worldless site survey, so plots are chosen from measurements rather than guessed.
 *
 * <p>
 * Both gates this reports are pure functions of the world seed and neither generates a chunk:
 *
 * <ul>
 * <li><b>Village viability.</b> {@code MapGenVillage.canSpawnStructureAtCoords} ends in
 * {@code areBiomesViable(cx*16+8, cz*16+8, 0, villageSpawnBiomes)}. RWG's {@code ChunkManagerRealistic} overrides
 * that method and ignores the biome list entirely, replacing it with a terrain-noise flatness test — the centre
 * must sit above y=62 and the spread across a 5x5 grid of 16-block samples must be under 22. Forcing
 * {@code canSpawnStructureAtCoords} to return true does <em>not</em> bypass the ground-level checks each village
 * piece makes as it builds, so a plot that fails this test yields a stunted half village.
 * <li><b>Witchery's biome gate.</b> {@code generateOverworld} returns early when
 * {@code BiomeManager.DISALLOWED_BIOMES} contains the biome id at {@code (blockX + 5, blockZ + 5)}. That check runs
 * <em>before</em> {@code nonInRange}, so {@link com.gtnhspeedrun.splash.mixins.WitcheryForceMixin} cannot force a
 * cell whose biome is blacklisted.
 * </ul>
 *
 * <p>
 * The biome name is reported too, because the fifth filmstrip column wants a wood-hills or wood-mountains cell —
 * those are the only two biomes with a call site for RWG's {@code DecoBigTree} no-arg constructor, which is the
 * {@code Math.random()} tree-sizing bug.
 *
 * <p>
 * Witchery is reached by reflection so this class loads on a pack without it.
 */
public final class Scout {

    private static final Logger LOG = LogManager.getLogger("gtnhsplash");

    private Scout() {}

    public static void run(MinecraftServer server) throws IOException {
        final WorldServer world = server.worldServers[0];
        final WorldChunkManager wcm = world.getWorldChunkManager();
        final int radius = Integer.getInteger("splash.scout.radius", 28);
        final Path out = Paths.get(System.getProperty("splash.scout.out", "splash-scout.tsv"));

        final List<Integer> blacklist = witcheryBlacklist();
        // areBiomesViable's List argument is dead under RWG, but pass a real one so this still means something if the
        // scout is ever pointed at a vanilla-generator world.
        final List<BiomeGenBase> villageBiomes = new ArrayList<>();
        Collections.addAll(villageBiomes, BiomeGenBase.plains, BiomeGenBase.desert, BiomeGenBase.savanna);

        int viable = 0, witchOk = 0, total = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("# seed=" + world.getSeed() + " radius=" + radius + " witchery_blacklist=" + blacklist + "\n");
            w.write("cx\tcz\tbiome_id\tbiome_name\tvillage_viable\twitchery_allowed\n");
            for (int cz = -radius; cz <= radius; cz++) {
                for (int cx = -radius; cx <= radius; cx++) {
                    final boolean v = wcm.areBiomesViable((cx << 4) + 8, (cz << 4) + 8, 0, villageBiomes);
                    // Witchery samples at (chunkX*16 + midX, chunkZ*16 + midZ) with midX = midZ = 5.
                    final BiomeGenBase b = wcm.getBiomeGenAt((cx << 4) + 5, (cz << 4) + 5);
                    final boolean ok = b != null && !blacklist.contains(b.biomeID);
                    w.write(
                        cx + "\t"
                            + cz
                            + "\t"
                            + (b == null ? -1 : b.biomeID)
                            + "\t"
                            + (b == null ? "?" : b.biomeName)
                            + "\t"
                            + v
                            + "\t"
                            + ok
                            + "\n");
                    total++;
                    if (v) viable++;
                    if (ok) witchOk++;
                }
            }
        }
        LOG.info(
            "[splash] scout wrote {} ({} chunks: {} village-viable, {} witchery-allowed)",
            out.toAbsolutePath(),
            total,
            viable,
            witchOk);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> witcheryBlacklist() {
        try {
            return (List<Integer>) disallowedBiomesField().get(null);
        } catch (Throwable t) {
            LOG.warn("[splash] Witchery BiomeManager.DISALLOWED_BIOMES unreadable ({}); assuming empty", t.toString());
            return Collections.emptyList();
        }
    }

    /**
     * Clears Witchery's biome blacklist so a plot can be sited anywhere.
     *
     * <p>
     * A global static mutation, and it is applied identically in all four runs, so it cannot bias the comparison —
     * but it does change which cells generate structures relative to a stock pack, so the run that uses it is not a
     * stock-density world and its log says so. Off by default; prefer picking plots from the scout output.
     */
    static void clearWitcheryBiomeBlacklist() {
        try {
            final List<?> l = (List<?>) disallowedBiomesField().get(null);
            final int n = l.size();
            l.clear();
            LOG.warn("[splash] splash.witchery.anybiome: cleared {} entries from BiomeManager.DISALLOWED_BIOMES", n);
        } catch (Throwable t) {
            LOG.error("[splash] could not clear Witchery biome blacklist", t);
        }
    }

    private static Field disallowedBiomesField() throws ReflectiveOperationException {
        final Field f = Class.forName("com.emoniph.witchery.worldgen.BiomeManager")
            .getDeclaredField("DISALLOWED_BIOMES");
        f.setAccessible(true);
        return f;
    }
}
