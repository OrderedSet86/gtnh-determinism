package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

/**
 * Headless worldgen determinism probe for the GTNH speedrun community.
 *
 * When launched with -Dprobe.order=<order>, force-generates a square of chunks around the origin in a controlled
 * order, then writes a JSON file with a SHA-256 hash of every chunk's blocks/metadata and (canonicalized) tile entity
 * NBT, and shuts the server down. Two runs on the same seed with different walk orders (e.g. rows vs cols) should
 * produce identical JSON iff worldgen is deterministic and chunk-order independent.
 *
 * Properties: -Dprobe.order=rows|cols|rows-reverse|spiral (required to activate) -Dprobe.radius=N (chunk radius to
 * hash, default 12; walks radius N+1 so the border can populate) -Dprobe.out=path (output file, default
 * ./probe-<order>.json)
 */
@Mod(
    modid = WorldgenProbe.MODID,
    version = Tags.VERSION,
    name = "WorldgenProbe",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class WorldgenProbe {

    public static final String MODID = "worldgenprobe";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        final String order = System.getProperty("probe.order");
        if (order == null) return;
        final int radius = Integer.getInteger("probe.radius", 12);
        final String out = System.getProperty("probe.out", "probe-" + order + ".json");
        try {
            runProbe(order, radius, out);
        } catch (Exception e) {
            LOG.error("Probe failed", e);
        }
        LOG.info("[probe] shutting down server");
        FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .initiateShutdown();
    }

    private void runProbe(String order, int radius, String out) throws Exception {
        final WorldServer world = FMLCommonHandler.instance()
            .getMinecraftServerInstance().worldServers[0];
        final long seed = world.getSeed();
        final int walkR = radius + 1;
        LOG.info("[probe] seed={} order={} radius={} (walking r={})", seed, order, radius, walkR);

        final List<int[]> walk = buildWalk(order, walkR);
        long t0 = System.currentTimeMillis();
        int n = 0;
        for (int[] c : walk) {
            world.theChunkProviderServer.loadChunk(c[0], c[1]);
            if (++n % 100 == 0) LOG.info("[probe] generated {}/{} chunks", n, walk.size());
        }
        LOG.info("[probe] generation done in {} ms, hashing…", System.currentTimeMillis() - t0);

        final Map<String, String> hashes = new TreeMap<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                hashes.put(x + "," + z, hashChunk(world.getChunkFromChunkCoords(x, z)));
            }
        }

        final String structures = dumpVillages(world);

        final StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"seed\": ")
            .append(seed)
            .append(",\n  \"order\": \"")
            .append(order)
            .append("\",\n  \"radius\": ")
            .append(radius)
            .append(",\n  \"chunks\": {\n");
        boolean first = true;
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    \"")
                .append(e.getKey())
                .append("\": \"")
                .append(e.getValue())
                .append("\"");
        }
        sb.append("\n  },\n  \"villages\": ")
            .append(structures)
            .append("\n}\n");
        final File f = new File(out);
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        }
        LOG.info("[probe] wrote {} chunk hashes to {}", hashes.size(), f.getAbsolutePath());
    }

    private static List<int[]> buildWalk(String order, int r) {
        final List<int[]> walk = new ArrayList<>();
        switch (order) {
            case "cols": // x outer: east/west columns first
                for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) walk.add(new int[] { x, z });
                break;
            case "rows-reverse":
                for (int z = r; z >= -r; z--) for (int x = r; x >= -r; x--) walk.add(new int[] { x, z });
                break;
            case "spiral": {
                int x = 0, z = 0, dx = 0, dz = -1;
                final int side = 2 * r + 1;
                for (int i = 0; i < side * side * 2; i++) {
                    if (x >= -r && x <= r && z >= -r && z <= r) walk.add(new int[] { x, z });
                    if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                        final int t = dx;
                        dx = -dz;
                        dz = t;
                    }
                    x += dx;
                    z += dz;
                }
                break;
            }
            case "rows":
            default:
                for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) walk.add(new int[] { x, z });
                break;
        }
        return walk;
    }

    private static String hashChunk(Chunk chunk) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (ExtendedBlockStorage ebs : chunk.getBlockStorageArray()) {
            if (ebs == null) {
                md.update((byte) 0);
                continue;
            }
            md.update((byte) 1);
            md.update(ebs.getBlockLSBArray());
            final NibbleArray msb = ebs.getBlockMSBArray();
            if (msb != null) md.update(msb.data);
            final NibbleArray meta = ebs.getMetadataArray();
            if (meta != null) md.update(meta.data);
        }
        // Tile entities (chest loot etc.), canonicalized: sorted by position, NBT keys sorted recursively.
        final Map<String, TileEntity> tes = new TreeMap<>();
        for (Object o : chunk.chunkTileEntityMap.values()) {
            final TileEntity te = (TileEntity) o;
            tes.put(
                te.xCoord + ","
                    + te.yCoord
                    + ","
                    + te.zCoord
                    + ","
                    + te.getClass()
                        .getName(),
                te);
        }
        for (TileEntity te : tes.values()) {
            final NBTTagCompound tag = new NBTTagCompound();
            try {
                te.writeToNBT(tag);
            } catch (Exception e) {
                md.update(
                    ("te-write-error:" + te.getClass()
                        .getName()).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            md.update(canonicalNbt(tag).getBytes(StandardCharsets.UTF_8));
        }
        final byte[] d = md.digest();
        final StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Reflectively dumps every village StructureStart from the overworld chunk generator's MapGenVillage: per village,
     * the sorted list of component class names + bounding boxes. Field/method lookup is done by TYPE so it works with
     * both MCP and SRG runtime names, and with modded chunk providers (RWG) as long as they hold a MapGenVillage field.
     */
    private static String dumpVillages(WorldServer world) {
        try {
            Object provider = world.theChunkProviderServer.currentChunkProvider;
            Object villageGen = null;
            for (java.lang.reflect.Field f : provider.getClass()
                .getDeclaredFields()) {
                if (net.minecraft.world.gen.structure.MapGenVillage.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    villageGen = f.get(provider);
                    break;
                }
            }
            if (villageGen == null) return "\"no MapGenVillage field found\"";
            Map<?, ?> structureMap = null;
            Class<?> c = villageGen.getClass();
            while (c != null && structureMap == null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        structureMap = (Map<?, ?>) f.get(villageGen);
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (structureMap == null) return "\"no structureMap found\"";
            final java.util.List<String> villages = new ArrayList<>();
            for (Object start : structureMap.values()) {
                java.util.List<?> components = null;
                for (Class<?> sc = start.getClass(); sc != null; sc = sc.getSuperclass()) {
                    for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            components = (java.util.List<?>) f.get(start);
                            break;
                        }
                    }
                    if (components != null) break;
                }
                if (components == null) continue;
                final java.util.List<String> parts = new ArrayList<>();
                for (Object comp : components) {
                    parts.add(
                        comp.getClass()
                            .getSimpleName() + "@"
                            + bboxOf(comp));
                }
                java.util.Collections.sort(parts);
                villages.add("\"" + parts.size() + " pieces: " + String.join("; ", parts) + "\"");
            }
            java.util.Collections.sort(villages);
            return "[\n    " + String.join(",\n    ", villages) + "\n  ]";
        } catch (Exception e) {
            return "\"error: " + e + "\"";
        }
    }

    private static String bboxOf(Object component) {
        try {
            for (Class<?> sc = component.getClass(); sc != null; sc = sc.getSuperclass()) {
                for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                    if (net.minecraft.world.gen.structure.StructureBoundingBox.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        net.minecraft.world.gen.structure.StructureBoundingBox bb = (net.minecraft.world.gen.structure.StructureBoundingBox) f
                            .get(component);
                        if (bb == null) return "nobb";
                        return bb.minX + "," + bb.minY + "," + bb.minZ + ".." + bb.maxX + "," + bb.maxY + "," + bb.maxZ;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "nobb";
    }

    @SuppressWarnings("unchecked")
    private static String canonicalNbt(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            final NBTTagCompound c = (NBTTagCompound) tag;
            final Map<String, String> entries = new TreeMap<>();
            for (String key : (java.util.Set<String>) c.func_150296_c()) {
                entries.put(key, canonicalNbt(c.getTag(key)));
            }
            final StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<String, String> e : entries.entrySet()) sb.append(e.getKey())
                .append(":")
                .append(e.getValue())
                .append(",");
            return sb.append("}")
                .toString();
        } else if (tag instanceof NBTTagList) {
            final NBTTagList l = (NBTTagList) tag;
            if (l.func_150303_d() != 10) return l.toString(); // primitive lists print in stable order
            final StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.tagCount(); i++) sb.append(canonicalNbt(l.getCompoundTagAt(i)))
                .append(",");
            return sb.append("]")
                .toString();
        } else {
            return tag.toString();
        }
    }
}
