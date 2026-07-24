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
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
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

    /** EndlessIDs (2.9.0+/daily packs, replaces NEID) forbids raw section-array reads — see hashChunk. */
    private static final boolean ENDLESS_IDS = cpw.mods.fml.common.Loader.isModLoaded("endlessids");

    /**
     * CRIU checkpoint barrier. With -Dprobe.criu=<control-dir>, this handler blocks the server thread after mod
     * loading but before DedicatedServer.startServer parses level-seed (the port is already bound at this point,
     * which is fine for sequential restores). The external harness dumps the frozen JVM here; every restore resumes in
     * the
     * poll loop, reads <control-dir>/go.json ({"seed":..,"order":"..","radius":..,"out":".."}), injects the seed into
     * the live PropertyManager and the probe params into system properties, then lets startServer continue: seed
     * parse -> loadAllWorlds -> FMLServerStartedEvent -> normal probe run.
     */
    /** Population-order trace: records the exact sequence chunks get populated, for cascade forensics. */
    public static final List<String> POP_SEQ = new ArrayList<>();

    /** FML's ASMEventHandler needs a public named class — anonymous listeners throw during event dispatch. */
    public static final class PopSeqHandler {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onPopulate(net.minecraftforge.event.terraingen.PopulateChunkEvent.Pre e) {
            if (e.world == null || e.world.provider.dimensionId != 0) return;
            synchronized (POP_SEQ) {
                POP_SEQ.add("P:" + e.chunkX + "," + e.chunkZ);
            }
        }

        /** Fires when a chunk enters the provider — for fresh chunks this is generation order (incl. cascades). */
        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onChunkLoad(net.minecraftforge.event.world.ChunkEvent.Load e) {
            if (e.world == null || e.world.provider.dimensionId != 0) return;
            String entry = "G:" + e.getChunk().xPosition + "," + e.getChunk().zPosition;
            if (Boolean.getBoolean("probe.tracestacks")) {
                // name the code that triggered this cascade: first non-vanilla, non-probe frames
                final StringBuilder who = new StringBuilder();
                int taken = 0;
                for (StackTraceElement fr : new Throwable().getStackTrace()) {
                    final String c = fr.getClassName();
                    if (c.startsWith("net.minecraft") || c.startsWith("cpw.")
                        || c.startsWith("net.minecraftforge")
                        || c.startsWith("com.gtnhspeedrun")
                        || c.startsWith("org.embeddedt")
                        || c.startsWith("java.")) continue;
                    who.append("|")
                        .append(c.substring(c.lastIndexOf('.') + 1))
                        .append(".")
                        .append(fr.getMethodName())
                        .append(":")
                        .append(fr.getLineNumber());
                    if (++taken >= 4) break;
                }
                entry += who.toString();
            }
            synchronized (POP_SEQ) {
                POP_SEQ.add(entry);
            }
        }
    }

    public static final Object POP_LISTENER = new PopSeqHandler();

    private static void clearPopSeq() {
        synchronized (POP_SEQ) {
            POP_SEQ.clear();
        }
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        // Pre-ServerStarting loot-table snapshot: cold boots generate the SPAWN REGION during loadAllWorlds,
        // BEFORE FMLServerStartingEvent mutates ChestGenHooks (TooMuchLoot rewrites categories there). Warm
        // recreates run their replicated spawn preload long after that mutation, so spawn-region chests roll
        // post-TML loot — deterministically different from every real/cold world (the 2.8.4 warm chest
        // contamination: 17 wrong chests, all inside the preload radius). Captured here (post-init, pre-server)
        // and restored around each warm recreate so preload-time table state matches a cold boot exactly.
        try {
            lootSnapPre = captureLootTables();
            LOG.info("[probe] pre-server loot snapshot: {} categories", lootSnapPre.size());
        } catch (Throwable t) {
            LOG.warn("[probe] pre-server loot snapshot failed: {}", t.toString());
        }
        final String ctl = System.getProperty("probe.criu");
        if (ctl == null) return;
        try {
            final File dir = new File(ctl);
            dir.mkdirs();
            final File go = new File(dir, "go.json");
            final String pid = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getName()
                .split("@")[0];
            try (FileWriter w = new FileWriter(new File(dir, "ready"))) {
                w.write(pid);
            }
            LOG.info("[probe] CRIU barrier up (pid {}), waiting for {}", pid, go);
            while (!go.exists()) Thread.sleep(200);
            final String json = new String(java.nio.file.Files.readAllBytes(go.toPath()), StandardCharsets.UTF_8);
            final String seed = jsonField(json, "seed");
            if (seed == null) throw new IllegalStateException("go.json missing \"seed\": " + json);
            final String jOrder = jsonField(json, "order");
            final String jRadius = jsonField(json, "radius");
            final String jOut = jsonField(json, "out");
            if (jOrder != null) System.setProperty("probe.order", jOrder);
            if (jRadius != null) System.setProperty("probe.radius", jRadius);
            if (jOut != null) System.setProperty("probe.out", jOut);
            injectLevelSeed(seed);
            LOG.info("[probe] CRIU resume: level-seed={} order={} out={}", seed, jOrder, jOut);
        } catch (Exception e) {
            throw new RuntimeException("[probe] CRIU barrier failed", e);
        }
    }

    /** Pulls "field": value out of a flat one-object JSON without a parser dependency. */
    private static String jsonField(String json, String field) {
        // quoted values first (may contain commas/paths), then bare numbers/booleans
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(-?[\\w.\\-/]+)")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Sets level-seed in the live DedicatedServer PropertyManager (fields found by type: they are private). */
    private static void injectLevelSeed(String seed) throws Exception {
        final MinecraftServer server = MinecraftServer.getServer();
        Object propertyManager = null;
        for (java.lang.reflect.Field f : server.getClass()
            .getDeclaredFields()) {
            if (f.getType()
                .getName()
                .endsWith("PropertyManager")) {
                f.setAccessible(true);
                propertyManager = f.get(server);
                break;
            }
        }
        if (propertyManager == null)
            throw new IllegalStateException("no PropertyManager field on " + server.getClass());
        for (java.lang.reflect.Field f : propertyManager.getClass()
            .getDeclaredFields()) {
            if (java.util.Properties.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                ((java.util.Properties) f.get(propertyManager)).setProperty("level-seed", seed);
                return;
            }
        }
        throw new IllegalStateException("no Properties field on PropertyManager");
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        MinecraftForge.EVENT_BUS.register(POP_LISTENER);
        final String daemonDir = System.getProperty("probe.daemon");
        if (daemonDir != null) {
            try {
                runDaemon(new File(daemonDir));
            } catch (Exception e) {
                LOG.error("Probe daemon died", e);
            }
            LOG.info("[probe] shutting down server");
            FMLCommonHandler.instance()
                .getMinecraftServerInstance()
                .initiateShutdown();
            return;
        }
        final String order = System.getProperty("probe.order");
        if (order == null) return;
        final int radius = Integer.getInteger("probe.radius", 12);
        final String out = System.getProperty("probe.out", "probe-" + order + ".json");
        final String seedsSpec = System.getProperty("probe.seeds");
        try {
            if (seedsSpec != null) {
                runWarmBatch(parseSeeds(seedsSpec), order, radius, out);
            } else {
                runProbe(
                    FMLCommonHandler.instance()
                        .getMinecraftServerInstance().worldServers[0],
                    order,
                    radius,
                    out);
            }
        } catch (Exception e) {
            LOG.error("Probe failed", e);
        }
        LOG.info("[probe] shutting down server");
        FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .initiateShutdown();
    }

    /**
     * Warm job-queue daemon (-Dprobe.daemon=<control-dir>): boot once, then park on the server thread polling
     * <control-dir>/queue/ for job files. Each job is a one-line JSON:
     * {"seed": N, "order": "rows", "radius": 8, "out": "/abs/path.json", "search": false, "tedetail": false,
     * "teraw": "cx,cz", "cx": N, "cz": N} — only seed and out are required. Jobs run in filename sort order, each as
     * a full warm cycle (teardown, static reset, recreate, probe). Job files move to done/ (or failed/) with a
     * .status file (millis + error). Touch <control-dir>/stop to shut the daemon down. Worldgen must run on the
     * server thread, so this loop intentionally never returns to the tick loop.
     */
    private void runDaemon(File dir) throws Exception {
        final MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        final File queue = new File(dir, "queue");
        final File done = new File(dir, "done");
        final File failed = new File(dir, "failed");
        queue.mkdirs();
        done.mkdirs();
        failed.mkdirs();
        final File stop = new File(dir, "stop");
        final WorldInfo bootInfo = server.worldServers[0].getWorldInfo();
        final WorldType worldType = bootInfo.getTerrainType();
        final String genOpts = bootInfo.getGeneratorOptions();
        try (FileWriter w = new FileWriter(new File(dir, "ready"))) {
            w.write(
                java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getName()
                    .split("@")[0]);
        }
        LOG.info("[probe] daemon up, polling {}", queue);
        int jobs = 0;
        while (!stop.exists()) {
            final File[] pending = queue.listFiles((d, n) -> n.endsWith(".json"));
            if (pending == null || pending.length == 0) {
                Thread.sleep(250);
                continue;
            }
            java.util.Arrays.sort(pending, java.util.Comparator.comparing(File::getName));
            final File job = pending[0];
            final long t0 = System.currentTimeMillis();
            String error = null;
            try {
                final String json = new String(java.nio.file.Files.readAllBytes(job.toPath()), StandardCharsets.UTF_8);
                // {"save": "true"} job: persist the CURRENT world completely (save + drain async IO + flush)
                // while the JVM lives — shutdown-path saves lose queued region writes to System.exit.
                if ("true".equals(jsonField(json, "save"))) {
                    final WorldServer cur = DimensionManager.getWorld(0);
                    if (cur == null) throw new IllegalStateException("no overworld to save");
                    // re-assert session.lock ownership: any later-constructed handler on the same dir overwrote
                    // the lock, and checkSessionLock inside saveAllChunks would abort the save
                    final Object curSh = cur.getSaveHandler();
                    if (curSh instanceof net.minecraft.world.storage.SaveHandler) {
                        java.lang.reflect.Method lockM;
                        try {
                            lockM = net.minecraft.world.storage.SaveHandler.class.getDeclaredMethod("setSessionLock");
                        } catch (NoSuchMethodException e) {
                            lockM = net.minecraft.world.storage.SaveHandler.class.getDeclaredMethod("func_75766_h"); // reobf
                                                                                                                     // name
                        }
                        lockM.setAccessible(true);
                        lockM.invoke(curSh);
                    }
                    cur.saveAllChunks(true, null);
                    ThreadedFileIOBase.threadedIOInstance.waitForFinish();
                    cur.flush();
                    LOG.info("[probe] world saved + IO drained");
                    final File destOk = new File(done, job.getName());
                    job.renameTo(destOk);
                    try (FileWriter w = new FileWriter(new File(done, job.getName() + ".status"))) {
                        w.write("{\"millis\": " + (System.currentTimeMillis() - t0) + "}\n");
                    }
                    jobs++;
                    continue;
                }
                // {"mc": "roguelike-loot", "draws": N, "out": path}: Monte-Carlo the SHIPPED Roguelike loot
                // tables (Loot.getLoot() -> ILoot.get(category, level) -> IWeighted.get(rand)) — real code, no
                // reimplementation. Gives tight per-item CIs for rare loot without worldgen. The fix jar does not
                // touch this code path, so one histogram serves both arms; the loot-DELIVERY semantics delta
                // (dead-chest skip) is an analytic rate factor applied offline.
                if ("roguelike-loot".equals(jsonField(json, "mc"))) {
                    final int draws = Integer.parseInt(orDefault(jsonField(json, "draws"), "100000"));
                    final String mcOut = jsonField(json, "out");
                    if (mcOut == null) throw new IllegalArgumentException("mc job needs out");
                    runRoguelikeLootMc(draws, mcOut);
                    job.renameTo(new File(done, job.getName()));
                    try (FileWriter w = new FileWriter(new File(done, job.getName() + ".status"))) {
                        w.write("{\"millis\": " + (System.currentTimeMillis() - t0) + "}\n");
                    }
                    jobs++;
                    continue;
                }
                final String seedS = jsonField(json, "seed");
                final String outS = jsonField(json, "out");
                if (seedS == null || outS == null) throw new IllegalArgumentException("job needs seed and out");
                final String order = orDefault(jsonField(json, "order"), "rows");
                final int radius = Integer.parseInt(orDefault(jsonField(json, "radius"), "8"));
                // per-job flags ride on the existing system properties runProbe reads
                setOrClear("probe.search", jsonField(json, "search"));
                setOrClear("probe.tedetail", jsonField(json, "tedetail"));
                setOrClear("probe.tracestacks", jsonField(json, "tracestacks"));
                setOrClear("probe.dim0only", jsonField(json, "dim0only"));
                setOrClear("probe.teraw", jsonField(json, "teraw"));
                setOrClear("probe.tefiltered", jsonField(json, "tefiltered"));
                setOrClear("probe.cx", jsonField(json, "cx"));
                setOrClear("probe.cz", jsonField(json, "cz"));
                final long seed = Long.parseLong(seedS);
                clearPopSeq();
                ensurePostBootLootSnapshot();
                teardownAllWorlds(server);
                resetStatics();
                restoreLootTables(lootSnapPre, "pre-server");
                recreateWorlds(server, seed, worldType, genOpts);
                restoreLootTables(lootSnapPost, "post-boot");
                final WorldServer over = DimensionManager.getWorld(0);
                if (over == null || over.getSeed() != seed)
                    throw new IllegalStateException("recreated overworld missing or wrong seed");
                runProbe(over, order, radius, outS);
            } catch (Exception e) {
                error = e.toString();
                LOG.error("[probe] job {} failed", job.getName(), e);
            }
            final File destDir = error == null ? done : failed;
            final File dest = new File(destDir, job.getName());
            job.renameTo(dest);
            try (FileWriter w = new FileWriter(new File(destDir, job.getName() + ".status"))) {
                w.write(
                    "{\"millis\": " + (System.currentTimeMillis() - t0)
                        + (error == null ? "" : ", \"error\": \"" + jsonEscape(error) + "\"")
                        + "}\n");
            }
            jobs++;
            LOG.info(
                "[probe] job {} {} in {} ms ({} total)",
                job.getName(),
                error == null ? "done" : "FAILED",
                System.currentTimeMillis() - t0,
                jobs);
        }
        LOG.info("[probe] daemon stop requested after {} jobs", jobs);
        stop.delete();
    }

    private static String orDefault(String v, String def) {
        return v == null ? def : v;
    }

    /**
     * Draws `draws` samples per (Loot category, level 0..4) from the shipped Roguelike tables via reflection and
     * writes a JSON histogram: category.level -> {"id:d": [stacks, units]}. MC rand is fixed-seeded so the run
     * itself is reproducible.
     */
    private static void runRoguelikeLootMc(int draws, String out) throws Exception {
        final Class<?> lootCls = Class.forName("greymerk.roguelike.treasure.loot.Loot");
        final Object iloot = lootCls.getMethod("getLoot")
            .invoke(null);
        final java.lang.reflect.Method getWeighted = iloot.getClass()
            .getMethod("get", lootCls, int.class);
        getWeighted.setAccessible(true);
        final Object[] cats = (Object[]) lootCls.getMethod("values")
            .invoke(null);
        final java.util.Random rand = new java.util.Random(0x600D5EED);
        final StringBuilder sb = new StringBuilder("{\n  \"draws\": ").append(draws)
            .append(",\n");
        boolean firstCat = true;
        for (Object cat : cats) {
            for (int level = 0; level <= 4; level++) {
                final Object weighted;
                try {
                    weighted = getWeighted.invoke(iloot, cat, level);
                } catch (Exception e) {
                    continue;
                }
                if (weighted == null) continue;
                final java.lang.reflect.Method get = weighted.getClass()
                    .getMethod("get", java.util.Random.class);
                get.setAccessible(true);
                final Map<String, long[]> hist = new TreeMap<>();
                for (int i = 0; i < draws; i++) {
                    final Object stackO;
                    try {
                        stackO = get.invoke(weighted, rand);
                    } catch (Exception e) {
                        continue;
                    }
                    if (!(stackO instanceof net.minecraft.item.ItemStack)) continue;
                    final net.minecraft.item.ItemStack st = (net.minecraft.item.ItemStack) stackO;
                    if (st.getItem() == null) continue;
                    final String key = net.minecraft.item.Item.itemRegistry.getNameForObject(st.getItem()) + ":"
                        + st.getItemDamage();
                    hist.computeIfAbsent(key, k -> new long[2]);
                    hist.get(key)[0]++;
                    hist.get(key)[1] += st.stackSize;
                }
                if (hist.isEmpty()) continue;
                if (!firstCat) sb.append(",\n");
                firstCat = false;
                sb.append("  \"")
                    .append(((Enum<?>) cat).name())
                    .append(".")
                    .append(level)
                    .append("\": {");
                boolean f1 = true;
                for (Map.Entry<String, long[]> e : hist.entrySet()) {
                    if (!f1) sb.append(", ");
                    f1 = false;
                    sb.append("\"")
                        .append(jsonEscape(e.getKey()))
                        .append("\": [")
                        .append(e.getValue()[0])
                        .append(",")
                        .append(e.getValue()[1])
                        .append("]");
                }
                sb.append("}");
                LOG.info("[probe] mc {} level {}: {} distinct items", ((Enum<?>) cat).name(), level, hist.size());
            }
        }
        sb.append("\n}\n");
        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        LOG.info("[probe] roguelike loot MC written to {}", out);
    }

    private static void setOrClear(String prop, String value) {
        if (value == null || "false".equals(value) || "null".equals(value)) System.clearProperty(prop);
        else System.setProperty(prop, value);
    }

    /**
     * Warm multi-seed mode (-Dprobe.seeds=comma-list or @file): tears the boot world down and, per seed, recreates
     * dim 0 (and every static dim) with a fresh save handler inside the same JVM — a complete worldgen reset (seed
     * lives only in WorldInfo; RWG's chunk manager/generator are built per world from it), amortizing the ~82s mod
     * boot to a few seconds per seed. Between seeds, mod statics that outlive the world are reset (resetStatics).
     * ONLY valid with the determinism fix jar: identity-hash-order state (F1 et al.) is constant within a JVM, so
     * warm runs cannot stand in for cold-launch variance tests on a stock pack.
     */
    private void runWarmBatch(long[] seeds, String order, int radius, String outTemplate) throws Exception {
        final MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        final WorldInfo bootInfo = server.worldServers[0].getWorldInfo();
        final WorldType worldType = bootInfo.getTerrainType();
        final String genOpts = bootInfo.getGeneratorOptions();
        LOG.info("[probe] warm batch: {} seeds, worldType={}", seeds.length, worldType.getWorldTypeName());
        ensurePostBootLootSnapshot();
        for (int i = 0; i < seeds.length; i++) {
            final long t0 = System.currentTimeMillis();
            clearPopSeq();
            teardownAllWorlds(server);
            resetStatics();
            restoreLootTables(lootSnapPre, "pre-server"); // spawn preload must see cold-boot table state
            recreateWorlds(server, seeds[i], worldType, genOpts);
            restoreLootTables(lootSnapPost, "post-boot"); // the walk generates with fully-loaded tables
            final WorldServer over = DimensionManager.getWorld(0);
            if (over == null || over.getSeed() != seeds[i])
                throw new IllegalStateException("recreated overworld missing or wrong seed");
            LOG.info(
                "[probe] warm slot {}/{}: seed {} world ready in {} ms",
                i + 1,
                seeds.length,
                seeds[i],
                System.currentTimeMillis() - t0);
            String slotOut = outFor(outTemplate, seeds[i]);
            // repeated seed in one batch (self-contamination tests): don't clobber the earlier slot's JSON
            if (new File(slotOut).exists()) slotOut = slotOut + ".slot" + (i + 1);
            runProbe(over, order, radius, slotOut);
        }
    }

    private static long[] parseSeeds(String spec) throws Exception {
        String body = spec;
        if (spec.startsWith("@")) {
            body = new String(
                java.nio.file.Files.readAllBytes(new File(spec.substring(1)).toPath()),
                StandardCharsets.UTF_8);
        }
        final String[] parts = body.trim()
            .split("[,\\s]+");
        final long[] seeds = new long[parts.length];
        for (int i = 0; i < parts.length; i++) seeds[i] = Long.parseLong(parts[i]);
        return seeds;
    }

    private static String outFor(String template, long seed) {
        if (template.contains("{seed}")) return template.replace("{seed}", Long.toString(seed));
        if (template.endsWith(".json")) return template.substring(0, template.length() - 5) + "-" + seed + ".json";
        return template + "-" + seed;
    }

    /** Mirrors MinecraftServer.stopServer() minus saving: Unload events, flush, deregister, drain IO, delete save. */
    private static void teardownAllWorlds(MinecraftServer server) throws Exception {
        final File worldDir = new File(server.getFolderName());
        for (WorldServer ws : DimensionManager.getWorlds()) {
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(ws));
            ws.flush();
            DimensionManager.setWorld(ws.provider.dimensionId, null);
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        warnIfChunkIOPending();
        deleteRecursively(worldDir);
        if (worldDir.exists()) throw new IllegalStateException("could not delete " + worldDir.getAbsolutePath());
    }

    /** Probe loads chunks synchronously so Forge's async ChunkIOExecutor queue should be empty; verify. */
    private static void warnIfChunkIOPending() {
        try {
            final Class<?> exec = Class.forName("net.minecraftforge.common.chunkio.ChunkIOExecutor");
            for (java.lang.reflect.Field f : exec.getDeclaredFields()) {
                f.setAccessible(true);
                final Object inst = f.get(null);
                if (inst == null) continue;
                for (java.lang.reflect.Field g : inst.getClass()
                    .getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(g.getType())) {
                        g.setAccessible(true);
                        final int n = ((Map<?, ?>) g.get(inst)).size();
                        if (n > 0) LOG.warn("[probe] ChunkIOExecutor has {} pending tasks at teardown", n);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[probe] could not inspect ChunkIOExecutor: {}", e.toString());
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            final File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    /**
     * Statics that outlive the world and fed worldgen historically. Reflection-based and hard-failing: if a listed
     * class/field vanishes (mod update), the warm run must die loudly rather than silently produce contaminated
     * results. Verified against the shipped 2.7.4 jars (TC 4.2.3.5a, witchery 0.24.1, GT 5.09.50.119, RWG alpha-1.5.0).
     */
    private static void resetStatics() throws Exception {
        // TC maze map (rings/obelisks): clearHashMap() clears the static `labyrinth`. The dimension/biome blacklists
        // on the same class are config-derived — must NOT be touched.
        Class.forName("thaumcraft.common.lib.world.dim.MazeHandler")
            .getMethod("clearHashMap")
            .invoke(null);
        // TC first-chunk-wins node dedup map (instance field on the registered generator).
        final Object tcGen = findRegisteredGenerator("ThaumcraftWorldGenerator");
        if (tcGen == null) throw new IllegalStateException("ThaumcraftWorldGenerator not registered");
        clearMapField(tcGen, "structureNode");
        // Witchery placed-structure chunk list (read back by the probe's witchery dump, too).
        final Object wGen = findRegisteredGenerator("WitcheryWorldGenerator");
        if (wGen == null) throw new IllegalStateException("WitcheryWorldGenerator not registered");
        clearCollectionField(wGen, "structuresList");
        // GT vein cache: keys embed the world seed, but the table grows without bound across seeds.
        final Class<?> gt = Class.forName("gregtech.common.GTWorldgenerator");
        final java.lang.reflect.Field veins = gt.getDeclaredField("validOreveins");
        veins.setAccessible(true);
        ((Map<?, ?>) veins.get(null)).clear();
        // Aggressive round-1 resets for the cross-seed ore-TE contamination bisect: GT's deferred worldgen queue,
        // its (vestigial) processed-chunk set, and the re-entrancy flag on the registered generator instance.
        final java.lang.reflect.Field mListF = gt.getDeclaredField("mList");
        mListF.setAccessible(true);
        final java.util.Collection<?> mList = (java.util.Collection<?>) mListF.get(null);
        if (!mList.isEmpty()) LOG.warn("[probe] GT mList had {} queued containers at teardown!", mList.size());
        mList.clear();
        final java.lang.reflect.Field procF = gt.getDeclaredField("ProcChunks");
        procF.setAccessible(true);
        ((java.util.Collection<?>) procF.get(null)).clear();
        final Object gtGen = findRegisteredGenerator("GTWorldgenerator");
        if (gtGen != null) {
            final java.lang.reflect.Field genF = findField(gtGen.getClass(), "mIsGenerating");
            genF.setAccessible(true);
            if (genF.getBoolean(gtGen)) LOG.warn("[probe] GT mIsGenerating was TRUE at teardown!");
            genF.setBoolean(gtGen, false);
        }
        // ROOT CAUSE of the cross-seed ore-TE contamination (found 2026-07-23 via staticsweep): BartWorks'
        // per-chunk dedup is a STATIC HashSet<ChunkCoordIntPair> — no world/seed in the key, add-only. Stale
        // positions from a previous world make the next world SKIP BartWorks small-ore gen there, which shifts GT
        // host-rock recording (te-only diffs, first-write-wins). Also leaks across dimensions on live servers —
        // upstream-reportable. Cleared per seed:
        final java.lang.reflect.Field bwGen = Class.forName("bartworks.system.oregen.BWWordGenerator$WorldGenContainer")
            .getDeclaredField("mGenerated");
        bwGen.setAccessible(true);
        final java.util.Collection<?> bwSet = (java.util.Collection<?>) bwGen.get(null);
        if (!bwSet.isEmpty()) LOG.info("[probe] clearing {} stale BartWorks mGenerated chunk entries", bwSet.size());
        bwSet.clear();
        // CoFH WorldHandler tracks in-flight populations in a STATIC LinkedHashList<ChunkReference> (dim+coords, no
        // world identity) — entries strand when a world is torn down mid-cascade (observed 93→209 growth across
        // slots), and stale same-coordinate refs flip its defer/retrogen decisions in the next world. GTNH runs its
        // flat-bedrock pass on every chunk, so this perturbs population order broadly. Clear tracker + retrogen queues:
        final Class<?> cofhWh = Class.forName("cofh.core.world.WorldHandler");
        final java.lang.reflect.Field popF = cofhWh.getDeclaredField("populatingChunks");
        popF.setAccessible(true);
        final java.util.Collection<?> popChunks = (java.util.Collection<?>) popF.get(null);
        if (!popChunks.isEmpty()) LOG.info("[probe] clearing {} stale CoFH populatingChunks entries", popChunks.size());
        popChunks.clear();
        final Object cofhInst = cofhWh.getField("instance")
            .get(null);
        if (cofhInst != null) {
            for (java.lang.reflect.Field f : cofhWh.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Queue.class.isAssignableFrom(f.getType())
                    && !java.util.Deque.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                final Object q = f.get(cofhInst);
                if (q instanceof java.util.Collection && !((java.util.Collection<?>) q).isEmpty()) {
                    LOG.info(
                        "[probe] clearing CoFH WorldHandler.{} ({} queued)",
                        f.getName(),
                        ((java.util.Collection<?>) q).size());
                    ((java.util.Collection<?>) q).clear();
                }
            }
        }
        // GT++ Everglades keeps its own validOreveins cache (Long-keyed) — defensively cleared for the same reason.
        try {
            final java.lang.reflect.Field gppVeins = Class.forName("gtPlusPlus.everglades.gen.gt.WorldGen_Ores")
                .getDeclaredField("validOreveins");
            gppVeins.setAccessible(true);
            ((Map<?, ?>) gppVeins.get(null)).clear();
        } catch (ClassNotFoundException e) {
            LOG.warn("[probe] GT++ WorldGen_Ores not present — skipping its vein-cache reset");
        }
        // Diagnostic sweep (log-only — clearing registry lists would break worldgen): report every non-empty
        // Map/Collection instance field on the registered IWorldGenerators, so the cross-seed culprit shows by name.
        for (Object g : allRegisteredGenerators()) {
            sweepCollectionFields(
                "gen-" + g.getClass()
                    .getSimpleName(),
                g);
        }
        staticSweepJars();
        // RWG noise-impl saved data is bound to the first world's mapStorage.
        final java.lang.reflect.Field rwgInst = Class.forName("rwg.world.RwgWorldSavedData")
            .getDeclaredField("INSTANCE");
        rwgInst.setAccessible(true);
        rwgInst.set(null, null);
        // Fix jar virgin-terrain cache: self-invalidates on world identity change, but pins the old world until then.
        try {
            final Class<?> oracle = Class.forName("com.gtnhspeedrun.tcworldgenfix.TerrainOracle");
            final java.lang.reflect.Field cache = oracle.getDeclaredField("CACHE");
            cache.setAccessible(true);
            synchronized (oracle) {
                ((Map<?, ?>) cache.get(null)).clear();
                final java.lang.reflect.Field cw = oracle.getDeclaredField("cacheWorld");
                cw.setAccessible(true);
                cw.set(null, null);
            }
        } catch (ClassNotFoundException e) {
            LOG.warn(
                "[probe] gtnhdeterminism fix jar NOT installed — warm-mode results are only meaningful with the fixes");
        }
        LOG.info("[probe] static reset done (TC maze+nodes, witchery list, GT veins, RWG saveddata, oracle)");
    }

    /**
     * ChestGenHooks loot tables are static, add-only, and mutated at runtime (TML rewrites at ServerStarting;
     * some mods re-register per world). A table that drifts between warm slots changes chest CONTENTS while
     * leaving placement draws untouched (selection walks accumulated weights; the draw count stays the same) —
     * observed as the 2.8.4 warm-slot chest-loot contamination (same chest positions, different items). Snapshot
     * every category's contents list at the FIRST between-seed reset (post-boot state = what a cold run's walk
     * starts from) and restore before each slot, logging any drift so the mutating mod is named in the log.
     */
    private static java.util.Map<String, java.util.List<Object>> lootSnapPre; // at FMLLoadComplete (pre-TML)
    private static java.util.Map<String, java.util.List<Object>> lootSnapPost; // post-boot (post-TML)

    @SuppressWarnings("unchecked")
    private static Map<String, net.minecraftforge.common.ChestGenHooks> chestInfo() throws Exception {
        final java.lang.reflect.Field infoF = net.minecraftforge.common.ChestGenHooks.class
            .getDeclaredField("chestInfo");
        infoF.setAccessible(true);
        return (Map<String, net.minecraftforge.common.ChestGenHooks>) infoF.get(null);
    }

    private static java.lang.reflect.Field lootContentsField() throws Exception {
        final java.lang.reflect.Field f = net.minecraftforge.common.ChestGenHooks.class.getDeclaredField("contents");
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, java.util.List<Object>> captureLootTables() throws Exception {
        final java.lang.reflect.Field contentsF = lootContentsField();
        final java.util.Map<String, java.util.List<Object>> snap = new java.util.HashMap<>();
        for (Map.Entry<String, net.minecraftforge.common.ChestGenHooks> e : chestInfo().entrySet()) {
            snap.put(e.getKey(), new ArrayList<>((java.util.List<Object>) contentsF.get(e.getValue())));
        }
        return snap;
    }

    /** Ensure the post-boot snapshot exists; call once before any warm slot mutates table state. */
    private static void ensurePostBootLootSnapshot() throws Exception {
        if (lootSnapPost != null) return;
        lootSnapPost = captureLootTables();
        LOG.info("[probe] post-boot loot snapshot: {} categories", lootSnapPost.size());
        if (lootSnapPre != null) {
            for (Map.Entry<String, java.util.List<Object>> e : lootSnapPost.entrySet()) {
                final java.util.List<Object> pre = lootSnapPre.get(e.getKey());
                if (pre != null && !lootDigest(pre).equals(lootDigest(e.getValue()))) {
                    LOG.info(
                        "[probe][loot] category {} is mutated between load-complete and server-started "
                            + "({} -> {} entries) — spawn-preload chests roll the FORMER in cold boots:",
                        e.getKey(),
                        pre.size(),
                        e.getValue()
                            .size());
                    logLootDiff(e.getKey(), pre, e.getValue());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreLootTables(java.util.Map<String, java.util.List<Object>> snap, String label)
        throws Exception {
        if (snap == null) {
            LOG.warn("[probe][loot] {} snapshot missing — cannot restore", label);
            return;
        }
        final java.lang.reflect.Field contentsF = lootContentsField();
        for (Map.Entry<String, net.minecraftforge.common.ChestGenHooks> e : chestInfo().entrySet()) {
            final java.util.List<Object> live = (java.util.List<Object>) contentsF.get(e.getValue());
            final java.util.List<Object> want = snap.get(e.getKey());
            if (want == null) continue; // category appeared later; leave as-is
            live.clear();
            live.addAll(want);
        }
    }

    private static String lootDigest(java.util.List<Object> contents) {
        final StringBuilder sb = new StringBuilder();
        for (Object o : contents) sb.append(describeLootEntry(o))
            .append(";");
        return Integer.toHexString(
            sb.toString()
                .hashCode())
            + ":"
            + contents.size();
    }

    private static String describeLootEntry(Object o) {
        try {
            final net.minecraft.util.WeightedRandomChestContent c = (net.minecraft.util.WeightedRandomChestContent) o;
            final net.minecraft.item.ItemStack s = c.theItemId;
            return net.minecraft.item.Item.itemRegistry.getNameForObject(s.getItem()) + "@"
                + s.getItemDamage()
                + "w"
                + c.itemWeight
                + "n"
                + c.theMinimumChanceToGenerateItem
                + "-"
                + c.theMaximumChanceToGenerateItem
                + (o.getClass() == net.minecraft.util.WeightedRandomChestContent.class ? ""
                    : "!" + o.getClass()
                        .getSimpleName());
        } catch (Throwable t) {
            return o.getClass()
                .getName();
        }
    }

    private static void logLootDiff(String cat, java.util.List<Object> snap, java.util.List<Object> live) {
        final java.util.Map<String, Integer> a = new java.util.HashMap<>(), b = new java.util.HashMap<>();
        for (Object o : snap) a.merge(describeLootEntry(o), 1, Integer::sum);
        for (Object o : live) b.merge(describeLootEntry(o), 1, Integer::sum);
        for (Map.Entry<String, Integer> e : b.entrySet()) {
            final int extra = e.getValue() - a.getOrDefault(e.getKey(), 0);
            if (extra > 0) LOG.warn("[probe][loot]   {} +{}x {}", cat, extra, e.getKey());
        }
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            final int missing = e.getValue() - b.getOrDefault(e.getKey(), 0);
            if (missing > 0) LOG.warn("[probe][loot]   {} -{}x {}", cat, missing, e.getKey());
        }
    }

    private static void clearMapField(Object owner, String name) throws Exception {
        final java.lang.reflect.Field f = findField(owner.getClass(), name);
        f.setAccessible(true);
        ((Map<?, ?>) f.get(owner)).clear();
    }

    private static void clearCollectionField(Object owner, String name) throws Exception {
        final java.lang.reflect.Field f = findField(owner.getClass(), name);
        f.setAccessible(true);
        ((java.util.Collection<?>) f.get(owner)).clear();
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    /** Replicates the loadAllWorlds body (it is protected) minus demo/bonus-chest/initialWorldChunkLoad. */
    private static void recreateWorlds(MinecraftServer server, long seed, WorldType worldType, String genOpts) {
        final ISaveHandler sh = server.getActiveAnvilConverter()
            .getSaveLoader(server.getFolderName(), true);
        if (sh.loadWorldInfo() != null)
            throw new IllegalStateException("stale level.dat survived teardown — save dir not empty");
        final WorldSettings settings = new WorldSettings(
            seed,
            server.getGameType(),
            server.canStructuresSpawn(),
            server.isHardcore(),
            worldType);
        settings.func_82750_a(genOpts == null ? "" : genOpts);
        final WorldServer over = new WorldServer(server, sh, server.getFolderName(), 0, settings, server.theProfiler);
        // probe.dim0only: skip recreating the ~12 non-overworld static dims per slot. Overworld probing never
        // touches them, and each recreated WorldServerMulti set stays pinned by mod dim-bookkeeping (measured
        // ~12 leaked worlds/slot in the 20-cycle jmap check) — dim0-only keeps long daemon batches flat.
        final boolean dim0Only = Boolean.getBoolean("probe.dim0only");
        for (int dim : DimensionManager.getStaticDimensionIDs()) {
            if (dim0Only && dim != 0) continue;
            final WorldServer w = dim == 0 ? over
                : new WorldServerMulti(server, sh, server.getFolderName(), dim, settings, over, server.theProfiler);
            w.addWorldAccess(new WorldManager(server, w));
            if (!server.isSinglePlayer()) w.getWorldInfo()
                .setGameType(server.getGameType());
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(w));
        }
        server.getConfigurationManager()
            .setPlayerManager(new WorldServer[] { over });
        server.func_147139_a(server.func_147135_j());
        assertOregenPatternFresh();
        // Replicate initialWorldChunkLoad (it is protected): cold boots pre-generate spawn +-192 blocks in this
        // exact order before any probe walk, and 1.7.10 decoration is generation-order sensitive — skipping this
        // left warm worlds byte-different from cold ones near spawn.
        final net.minecraft.util.ChunkCoordinates spawn = over.getSpawnPoint();
        for (int k = -192; k <= 192; k += 16) {
            for (int l = -192; l <= 192; l += 16) {
                over.theChunkProviderServer.loadChunk(spawn.posX + k >> 4, spawn.posZ + l >> 4);
            }
        }
    }

    /** GT's OregenPatternSavedData reloads on WorldEvent.Load; a fresh world must yield EQUAL_SPACING (= cold boot). */
    private static void assertOregenPatternFresh() {
        try {
            final java.lang.reflect.Field f = Class.forName("gregtech.common.GTWorldgenerator")
                .getDeclaredField("oregenPattern");
            f.setAccessible(true);
            final Object v = f.get(null);
            if (v == null || !"EQUAL_SPACING".equals(((Enum<?>) v).name())) throw new IllegalStateException(
                "GT oregenPattern is " + v + " on a fresh world (expected EQUAL_SPACING)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not verify GT oregenPattern", e);
        }
    }

    private void runProbe(WorldServer world, String order, int radius, String out) throws Exception {
        final long seed = world.getSeed();
        final int walkR = radius + 1;
        LOG.info("[probe] seed={} order={} radius={} (walking r={})", seed, order, radius, walkR);

        // In search mode the region of interest is the spawn neighborhood (chest sweep is spawn-relative), so
        // default the walk center to the spawn chunk; explicit probe.cx/cz still override.
        final boolean searchMode = Boolean.getBoolean("probe.search");
        final int cx = Integer.getInteger("probe.cx", searchMode ? world.getSpawnPoint().posX >> 4 : 0);
        final int cz = Integer.getInteger("probe.cz", searchMode ? world.getSpawnPoint().posZ >> 4 : 0);
        final List<int[]> walk = buildWalk(order, walkR);
        for (int[] c : walk) {
            c[0] += cx;
            c[1] += cz;
        }
        long t0 = System.currentTimeMillis();
        int n = 0;
        for (int[] c : walk) {
            world.theChunkProviderServer.loadChunk(c[0], c[1]);
            if (++n % 100 == 0) LOG.info("[probe] generated {}/{} chunks", n, walk.size());
        }
        LOG.info("[probe] generation done in {} ms, hashing…", System.currentTimeMillis() - t0);

        // -Dprobe.nohash=true (seed-search fast path): skip the SHA-256 chunk digests — searchlib only reads the
        // "search" section. TE materialization still runs (the ore histogram needs GT ore TEs to exist).
        final boolean noHash = Boolean.getBoolean("probe.nohash");
        final Map<String, String> hashes = new TreeMap<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                materializeTileEntities(world, world.getChunkFromChunkCoords(cx + x, cz + z));
            }
        }
        if (!noHash) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    hashes.put((cx + x) + "," + (cz + z), hashChunk(world.getChunkFromChunkCoords(cx + x, cz + z)));
                }
            }
        }

        // The spawn preload region (spawn chunk ±12) is always generated — hash whatever part of it falls outside
        // the main window too, as a separate section so existing corpus comparisons stay valid. Generated-but-
        // unmeasured chunks are how the dungeon divergence hid from earlier runs.
        final Map<String, String> spawnExtra = new TreeMap<>();
        {
            final int scx = world.getSpawnPoint().posX >> 4;
            final int scz = world.getSpawnPoint().posZ >> 4;
            for (int x = scx - 12; x <= scx + 12; x++) {
                for (int z = scz - 12; z <= scz + 12; z++) {
                    if (Math.abs(x - cx) <= radius && Math.abs(z - cz) <= radius) continue; // already in main window
                    final Chunk c = world.getChunkFromChunkCoords(x, z);
                    materializeTileEntities(world, c);
                    if (!noHash) try {
                        spawnExtra.put(x + "," + z, hashChunk(c));
                    } catch (Exception ignored) {}
                }
            }
        }
        final String structures = dumpVillages(world);
        final String witchery = dumpWitcheryStructures();
        final String search = Boolean.getBoolean("probe.search") ? buildSearchReport(world, radius, cx, cz) : null;
        if (search != null) dumpGtMaterialsOnce(new File(out).getParentFile());
        final StringBuilder tedetail = new StringBuilder();
        if (Boolean.getBoolean("probe.tedetail")) {
            tedetail.append(",\n  \"tedetail\": {\n");
            boolean firstC = true;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    final Chunk c = world.getChunkFromChunkCoords(cx + x, cz + z);
                    final Map<String, String> tes = new TreeMap<>();
                    for (Object o : c.chunkTileEntityMap.values()) {
                        final TileEntity te = (TileEntity) o;
                        final NBTTagCompound tag = new NBTTagCompound();
                        String h;
                        try {
                            te.writeToNBT(tag);
                            h = hex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(canonicalNbt(tag).getBytes(StandardCharsets.UTF_8))).substring(0, 10);
                        } catch (Exception e) {
                            h = "err";
                        }
                        tes.put(
                            te.getClass()
                                .getName() + "@"
                                + te.xCoord
                                + ","
                                + te.yCoord
                                + ","
                                + te.zCoord,
                            h);
                    }
                    if (tes.isEmpty()) continue;
                    if (!firstC) tedetail.append(",\n");
                    firstC = false;
                    tedetail.append("    \"")
                        .append(cx + x)
                        .append(",")
                        .append(cz + z)
                        .append("\": {");
                    boolean firstT = true;
                    for (Map.Entry<String, String> e : tes.entrySet()) {
                        if (!firstT) tedetail.append(", ");
                        firstT = false;
                        tedetail.append("\"")
                            .append(e.getKey())
                            .append("\": \"")
                            .append(e.getValue())
                            .append("\"");
                    }
                    tedetail.append("}");
                }
            }
            tedetail.append("\n  }");
        }

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
                .append("\": ")
                .append(e.getValue());
        }
        final StringBuilder popseq = new StringBuilder("[");
        synchronized (POP_SEQ) {
            for (int i = 0; i < POP_SEQ.size(); i++) {
                if (i > 0) popseq.append(", ");
                popseq.append("\"")
                    .append(POP_SEQ.get(i))
                    .append("\"");
            }
        }
        popseq.append("]");
        final StringBuilder spx = new StringBuilder("{");
        boolean firstSx = true;
        for (Map.Entry<String, String> e : spawnExtra.entrySet()) {
            if (!firstSx) spx.append(",\n    ");
            firstSx = false;
            spx.append("\"")
                .append(e.getKey())
                .append("\": ")
                .append(e.getValue());
        }
        spx.append("}");
        sb.append("\n  },\n  \"spawnextra\": ")
            .append(spx)
            .append(",\n  \"popseq\": ")
            .append(popseq)
            .append(",\n  \"villages\": ")
            .append(structures)
            .append(",\n  \"witchery\": ")
            .append(witchery)
            .append(search == null ? "" : ",\n  \"search\": " + search)
            .append(tedetail)
            .append("\n}\n");
        final File f = new File(out);
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        }
        LOG.info("[probe] wrote {} chunk hashes to {}", hashes.size(), f.getAbsolutePath());

        final String tefiltered = System.getProperty("probe.tefiltered");
        if (tefiltered != null) {
            final String[] parts = tefiltered.split(",");
            final Chunk fc = world.getChunkFromChunkCoords(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            final Map<String, String> tes = new TreeMap<>();
            for (Object o : fc.chunkTileEntityMap.values()) {
                final TileEntity te = (TileEntity) o;
                final String key = te.xCoord + "," + te.yCoord + "," + te.zCoord;
                if (!teMatchesBlock(fc, te)) {
                    tes.put(
                        key,
                        "FILTERED " + te.getClass()
                            .getSimpleName());
                    continue;
                }
                final NBTTagCompound tag = new NBTTagCompound();
                try {
                    te.writeToNBT(tag);
                    tes.put(key, canonicalNbt(tag));
                } catch (Exception e) {
                    tes.put(key, "err " + e);
                }
            }
            try (FileWriter w = new FileWriter(out + ".tefiltered-" + parts[0] + "_" + parts[1] + ".txt")) {
                for (Map.Entry<String, String> e : tes.entrySet()) w.write(e.getKey() + " " + e.getValue() + "\n");
            }
            LOG.info("[probe] dumped filtered TE view for chunk {}", tefiltered);
        }

        final String teraw = System.getProperty("probe.teraw");
        if (teraw != null) {
            final String[] parts = teraw.split(",");
            final Chunk tc = world.getChunkFromChunkCoords(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            final Map<String, String> tes = new TreeMap<>();
            for (Object o : tc.chunkTileEntityMap.values()) {
                final TileEntity te = (TileEntity) o;
                final NBTTagCompound tag = new NBTTagCompound();
                try {
                    te.writeToNBT(tag);
                    tes.put(te.xCoord + "," + te.yCoord + "," + te.zCoord, canonicalNbt(tag));
                } catch (Exception ignored) {}
            }
            try (FileWriter w = new FileWriter(out + ".teraw-" + parts[0] + "_" + parts[1] + ".txt")) {
                for (Map.Entry<String, String> e : tes.entrySet()) w.write(e.getKey() + " " + e.getValue() + "\n");
            }
            LOG.info("[probe] dumped raw TE NBT for chunk {}", teraw);
        }

        final String dump = System.getProperty("probe.dump");
        if (dump != null) {
            final String[] parts = dump.split(",");
            final Chunk dc = world.getChunkFromChunkCoords(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            final StringBuilder db = new StringBuilder();
            for (int y = 0; y < 256; y++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final net.minecraft.block.Block bl = dc.getBlock(lx, y, lz);
                        if (bl == net.minecraft.init.Blocks.air) continue;
                        db.append(lx)
                            .append(",")
                            .append(y)
                            .append(",")
                            .append(lz)
                            .append(" ")
                            .append(net.minecraft.block.Block.blockRegistry.getNameForObject(bl))
                            .append(":")
                            .append(dc.getBlockMetadata(lx, y, lz))
                            .append("\n");
                    }
                }
            }
            try (FileWriter w = new FileWriter(out + ".dump-" + parts[0] + "_" + parts[1] + ".txt")) {
                w.write(db.toString());
            }
            LOG.info("[probe] dumped chunk {} block listing", dump);
        }
    }

    /**
     * Seed-search report (-Dprobe.search=true): everything the speedrun seed searcher filters on, readable without
     * re-running the server. Per chunk: biome, water/clay block counts, GT ore TE m-value histogram (material =
     * m%1000; thousands digit = host-stone variant / small-ore flag — decode offline), and every IInventory tile
     * entity's full contents (village/dungeon chests: tier-skip loot lives here). Spawn point at the top because
     * spawn-relative distance is the primary search criterion.
     */
    private static String buildSearchReport(WorldServer world, int radius, int cx, int cz) {
        final StringBuilder sb = new StringBuilder("{\n");
        final net.minecraft.util.ChunkCoordinates sp = world.getSpawnPoint();
        sb.append("    \"spawn\": [")
            .append(sp.posX)
            .append(", ")
            .append(sp.posY)
            .append(", ")
            .append(sp.posZ)
            .append("],\n    \"chunks\": {\n");
        boolean firstChunk = true;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                final Chunk c = world.getChunkFromChunkCoords(cx + x, cz + z);
                final net.minecraft.world.biome.BiomeGenBase biome = world
                    .getBiomeGenForCoords(((cx + x) << 4) + 8, ((cz + z) << 4) + 8);
                int water = 0, clay = 0;
                // Section-array scan (counts are order-independent): null sections skip 4096 blocks at a time.
                for (final ExtendedBlockStorage ebs : c.getBlockStorageArray()) {
                    if (ebs == null) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                final net.minecraft.block.Block b = ebs.getBlockByExtId(lx, ly, lz);
                                if (b == net.minecraft.init.Blocks.water
                                    || b == net.minecraft.init.Blocks.flowing_water) water++;
                                else if (b == net.minecraft.init.Blocks.clay) clay++;
                            }
                        }
                    }
                }
                final Map<Integer, Integer> ores = new TreeMap<>();
                final List<String> chests = new ArrayList<>();
                for (Object o : c.chunkTileEntityMap.values()) {
                    final TileEntity te = (TileEntity) o;
                    if (!teMatchesBlock(c, te)) continue; // stranded TEs would pollute chest/ore stats
                    if (te.getClass()
                        .getName()
                        .endsWith("TileEntityOres")) {
                        try {
                            final java.lang.reflect.Field f = findField(te.getClass(), "mMetaData");
                            f.setAccessible(true);
                            final int m = ((Number) f.get(te)).intValue();
                            ores.merge(m, 1, Integer::sum);
                        } catch (Exception ignored) {}
                    } else if (te instanceof net.minecraft.inventory.IInventory) {
                        chests.add(dumpInventory((net.minecraft.inventory.IInventory) te, te));
                    }
                }
                if (!firstChunk) sb.append(",\n");
                firstChunk = false;
                sb.append("      \"")
                    .append(cx + x)
                    .append(",")
                    .append(cz + z)
                    .append("\": {\"biome\": \"")
                    .append(jsonEscape(biome.biomeName))
                    .append("\", \"biomeId\": ")
                    .append(biome.biomeID)
                    .append(", \"water\": ")
                    .append(water)
                    .append(", \"clay\": ")
                    .append(clay);
                if (!ores.isEmpty()) {
                    sb.append(", \"ores\": {");
                    boolean f1 = true;
                    for (Map.Entry<Integer, Integer> e : ores.entrySet()) {
                        if (!f1) sb.append(", ");
                        f1 = false;
                        sb.append("\"")
                            .append(e.getKey())
                            .append("\": ")
                            .append(e.getValue());
                    }
                    sb.append("}");
                }
                if (!chests.isEmpty()) {
                    sb.append(", \"chests\": [")
                        .append(String.join(", ", chests))
                        .append("]");
                }
                sb.append("}");
            }
        }
        sb.append("\n    }\n  }");
        return sb.toString();
    }

    private static String dumpInventory(net.minecraft.inventory.IInventory inv, TileEntity te) {
        final StringBuilder sb = new StringBuilder("{\"pos\": [").append(te.xCoord)
            .append(", ")
            .append(te.yCoord)
            .append(", ")
            .append(te.zCoord)
            .append("], \"type\": \"")
            .append(
                te.getClass()
                    .getSimpleName())
            .append("\", \"items\": [");
        boolean first = true;
        int size;
        try {
            size = inv.getSizeInventory();
        } catch (Exception e) {
            size = 0;
        }
        for (int i = 0; i < size; i++) {
            net.minecraft.item.ItemStack st;
            try {
                st = inv.getStackInSlot(i);
            } catch (Exception e) {
                continue;
            }
            if (st == null || st.getItem() == null) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append("{\"s\": ")
                .append(i)
                .append(", \"id\": \"")
                .append(jsonEscape(String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(st.getItem()))))
                .append("\", \"d\": ")
                .append(st.getItemDamage())
                .append(", \"n\": ")
                .append(st.stackSize);
            try {
                final String disp = st.getDisplayName();
                if (disp != null && !disp.isEmpty()) sb.append(", \"name\": \"")
                    .append(jsonEscape(disp))
                    .append("\"");
            } catch (Exception ignored) {}
            if (st.hasTagCompound()) {
                sb.append(", \"tag\": \"")
                    .append(jsonEscape(canonicalNbt(st.getTagCompound())))
                    .append("\"");
            }
            sb.append("}");
        }
        return sb.append("]}")
            .toString();
    }

    private static String jsonEscape(String s) {
        return s == null ? "null"
            : s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static boolean gtMatsDumped = false;

    /** GT material id -> name map (from the static Materials fields), once per JVM, so ore m-values decode offline. */
    private static synchronized void dumpGtMaterialsOnce(File dir) {
        if (gtMatsDumped) return;
        gtMatsDumped = true;
        try {
            final Class<?> mats = Class.forName("gregtech.api.enums.Materials");
            final Map<Integer, String> byId = new TreeMap<>();
            for (java.lang.reflect.Field f : mats.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != mats) continue;
                final Object m = f.get(null);
                if (m == null) continue;
                final java.lang.reflect.Field idF = findField(mats, "mMetaItemSubID");
                idF.setAccessible(true);
                final int id = ((Number) idF.get(m)).intValue();
                if (id >= 0) byId.putIfAbsent(id, f.getName());
            }
            final File outF = new File(dir == null ? new File(".") : dir, "gtmats.json");
            final StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (Map.Entry<Integer, String> e : byId.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  \"")
                    .append(e.getKey())
                    .append("\": \"")
                    .append(jsonEscape(e.getValue()))
                    .append("\"");
            }
            sb.append("\n}\n");
            try (FileWriter w = new FileWriter(outF)) {
                w.write(sb.toString());
            }
            LOG.info("[probe] wrote {} GT material names to {}", byId.size(), outF);
        } catch (Exception e) {
            LOG.warn("[probe] could not dump GT materials: {}", e.toString());
        }
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

    /**
     * Tile entities can be created lazily on first access, so the live chunkTileEntityMap contents depend on
     * incidental access timing (observed with GT ore TEs). Force every TE-capable block to materialize its TE so the
     * hashed set is a pure function of the blocks.
     */
    private static void materializeTileEntities(WorldServer world, Chunk chunk) {
        final int baseX = chunk.xPosition << 4;
        final int baseZ = chunk.zPosition << 4;
        // Same visit order as the original per-column scan (lx, lz, y ascending) — TE materialization order
        // feeds chunkTileEntityMap iteration order and thus the TE digest. Section-array access (null sections
        // skipped wholesale, no Chunk.getBlock overhead) cut the scan cost measurably; getBlockByExtId works on
        // both the NEID raw path and EndlessIDs.
        final ExtendedBlockStorage[] arr = chunk.getBlockStorageArray();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = 0; y < 256; y++) {
                    final ExtendedBlockStorage ebs = arr[y >> 4];
                    if (ebs == null) {
                        y |= 15; // skip to the end of this empty 16-block section
                        continue;
                    }
                    final net.minecraft.block.Block b = ebs.getBlockByExtId(lx, y & 15, lz);
                    if (b == net.minecraft.init.Blocks.air) continue;
                    if (b.hasTileEntity(ebs.getExtBlockMetadata(lx, y & 15, lz))) {
                        world.getTileEntity(baseX + lx, y, baseZ + lz);
                    }
                }
            }
        }
    }

    private static String hashChunk(Chunk chunk) throws Exception {
        // v3: {"b": whole-blocks hash, "t": tile entity hash, "s": [16 per-section hashes]}
        final StringBuilder out = new StringBuilder("{\"s\": [");
        final MessageDigest all = MessageDigest.getInstance("SHA-256");
        final ExtendedBlockStorage[] arr = chunk.getBlockStorageArray();
        for (int i = 0; i < arr.length; i++) {
            final MessageDigest sec = MessageDigest.getInstance("SHA-256");
            final ExtendedBlockStorage ebs = arr[i];
            if (ebs == null) {
                sec.update((byte) 0);
                all.update((byte) 0);
            } else {
                sec.update((byte) 1);
                all.update((byte) 1);
                if (ENDLESS_IDS) {
                    // EndlessIDs hard-crashes the raw LSB/MSB array accessors; hash through the
                    // per-block API instead. Different digest values than the raw path — never
                    // compare probe JSONs across the two paths.
                    final byte[] buf = new byte[16 * 16 * 16 * 6];
                    int p = 0;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                final int id = net.minecraft.block.Block.getIdFromBlock(ebs.getBlockByExtId(x, y, z));
                                final int m = ebs.getExtBlockMetadata(x, y, z);
                                buf[p++] = (byte) (id >>> 24);
                                buf[p++] = (byte) (id >>> 16);
                                buf[p++] = (byte) (id >>> 8);
                                buf[p++] = (byte) id;
                                buf[p++] = (byte) (m >>> 8);
                                buf[p++] = (byte) m;
                            }
                        }
                    }
                    sec.update(buf);
                    all.update(buf);
                } else {
                    sec.update(ebs.getBlockLSBArray());
                    all.update(ebs.getBlockLSBArray());
                    final NibbleArray msb = ebs.getBlockMSBArray();
                    if (msb != null) {
                        sec.update(msb.data);
                        all.update(msb.data);
                    }
                    final NibbleArray meta = ebs.getMetadataArray();
                    if (meta != null) {
                        sec.update(meta.data);
                        all.update(meta.data);
                    }
                }
            }
            out.append("\"")
                .append(hex(sec.digest()).substring(0, 12))
                .append("\"")
                .append(i < arr.length - 1 ? ", " : "");
        }
        out.append("], \"b\": \"")
            .append(hex(all.digest()))
            .append("\", \"t\": \"")
            .append(hashTileEntities(chunk))
            .append("\"}");
        return out.toString();
    }

    private static String hex(byte[] d) {
        final StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * True iff this TE matches the block actually at its position. Orphan filtering by hasTileEntity alone is not
     * enough: a chest TE stranded under a position whose block was later replaced by a DIFFERENT TE-capable block
     * (observed with Roguelike carving) passes that test and injects launch-timing jitter into the hash.
     */
    private static boolean teMatchesBlock(Chunk chunk, TileEntity te) {
        final int lx = te.xCoord & 15, lz = te.zCoord & 15;
        final net.minecraft.block.Block b = chunk.getBlock(lx, te.yCoord, lz);
        final int meta = chunk.getBlockMetadata(lx, te.yCoord, lz);
        if (!b.hasTileEntity(meta)) return false;
        try {
            final TileEntity fresh = b.createTileEntity(chunk.worldObj, meta);
            return fresh == null || fresh.getClass() == te.getClass();
        } catch (Exception e) {
            return true; // cannot probe — keep rather than over-filter
        }
    }

    private static String hashTileEntities(Chunk chunk) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        // Tile entities (chest loot etc.), canonicalized: sorted by position, NBT keys sorted recursively.
        final Map<String, TileEntity> tes = new TreeMap<>();
        for (Object o : chunk.chunkTileEntityMap.values()) {
            final TileEntity te = (TileEntity) o;
            // Skip orphaned/mismatched TEs (block overwritten later in worldgen but the TE lingered in the map);
            // they don't affect the persisted block/ore state and only add launch-timing jitter.
            if (!teMatchesBlock(chunk, te)) continue;
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
        return hex(md.digest());
    }

    /**
     * -Dprobe.staticsweep=jarSubstr1,jarSubstr2: at each between-seed reset, enumerate every class in the matching
     * mod jars and log every non-empty STATIC Map/Collection field with its size and first-key class. Positional
     * cross-seed caches (Long / ChunkCoordIntPair keys) stand out by name. Diagnostic only: reading a static field
     * can trigger class init (caught per class), so don't leave this on for production runs.
     */
    private static void staticSweepJars() {
        final String spec = System.getProperty("probe.staticsweep");
        if (spec == null) return;
        final String[] wanted = "all".equals(spec) ? new String[] { "" } : spec.split(",");
        final ClassLoader loader = WorldgenProbe.class.getClassLoader();
        final File modsDir = new File("mods");
        final File[] jars = modsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return;
        int scanned = 0;
        for (File jar : jars) {
            boolean match = false;
            for (String w : wanted) if (jar.getName()
                .toLowerCase()
                .contains(w.toLowerCase())) match = true;
            if (!match) continue;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
                final java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    final String name = en.nextElement()
                        .getName();
                    if (!name.endsWith(".class") || name.contains("$$")) continue;
                    final String cls = name.substring(0, name.length() - 6)
                        .replace('/', '.');
                    try {
                        final Class<?> c = Class.forName(cls, false, loader);
                        scanned++;
                        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                final Object v = f.get(null);
                                if (v == null) continue;
                                logIfInterestingCollection(cls + "." + f.getName(), v);
                                logIfRandom(cls + "." + f.getName(), v);
                                // one-level descent: static singletons whose INSTANCE fields hold the real cache
                                final String vc = v.getClass()
                                    .getName();
                                if (!(v instanceof Map) && !(v instanceof java.util.Collection)
                                    && !vc.startsWith("java.")
                                    && !vc.startsWith("net.minecraft.")) {
                                    for (java.lang.reflect.Field g : v.getClass()
                                        .getDeclaredFields()) {
                                        if (java.lang.reflect.Modifier.isStatic(g.getModifiers())) continue;
                                        if (!Map.class.isAssignableFrom(g.getType())
                                            && !java.util.Collection.class.isAssignableFrom(g.getType())) continue;
                                        g.setAccessible(true);
                                        final Object gv = g.get(v);
                                        if (gv != null)
                                            logIfInterestingCollection(cls + "." + f.getName() + ">" + g.getName(), gv);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) {
                LOG.warn("[probe] sweep of {} failed: {}", jar.getName(), e.toString());
            }
        }
        LOG.info("[probe][staticsweep] scanned {} classes", scanned);
    }

    /** Sweep helper: log static java.util.Random-typed fields (lazily-seeded static RNGs leak across warm slots). */
    private static void logIfRandom(String label, Object v) {
        if (!(v instanceof java.util.Random)) return;
        String state = "?";
        try {
            final java.lang.reflect.Field seedF = java.util.Random.class.getDeclaredField("seed");
            seedF.setAccessible(true);
            state = String.valueOf(((java.util.concurrent.atomic.AtomicLong) seedF.get(v)).get());
        } catch (Throwable ignored) {}
        LOG.info(
            "[probe][staticsweep] RANDOM {} = {} (scrambled-seed {})",
            label,
            v.getClass()
                .getName(),
            state);
    }

    /** Sweep helper: log a Map/Collection if positional-keyed (any size) or sizeable (>=10 entries). */
    private static void logIfInterestingCollection(String label, Object v) {
        final int size;
        Object firstKey = null;
        if (v instanceof Map) {
            final Map<?, ?> m = (Map<?, ?>) v;
            size = m.size();
            if (size > 0) try {
                firstKey = m.keySet()
                    .iterator()
                    .next();
            } catch (Exception ignored) {}
        } else if (v instanceof java.util.Collection) {
            final java.util.Collection<?> col = (java.util.Collection<?>) v;
            size = col.size();
            if (size > 0) try {
                firstKey = col.iterator()
                    .next();
            } catch (Exception ignored) {}
        } else return;
        if (size == 0) return;
        final String keyCls = firstKey == null ? "null"
            : firstKey.getClass()
                .getSimpleName();
        final boolean positional = keyCls.contains("Long") || keyCls.contains("ChunkCoord")
            || keyCls.contains("ChunkPosition")
            || keyCls.contains("Coord");
        if (!positional && size < 10) return;
        LOG.info(
            "[probe][staticsweep] {} = {} entries, first {} = {}",
            label,
            size,
            keyCls,
            String.valueOf(firstKey)
                .substring(
                    0,
                    Math.min(
                        60,
                        String.valueOf(firstKey)
                            .length())));
    }

    /** Logs every non-empty Map/Collection instance field on owner (its class hierarchy) — bisect diagnostic. */
    private static void sweepCollectionFields(String label, Object owner) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                final boolean isMap = Map.class.isAssignableFrom(f.getType());
                final boolean isColl = java.util.Collection.class.isAssignableFrom(f.getType());
                if (!isMap && !isColl) continue;
                try {
                    f.setAccessible(true);
                    final Object v = f.get(owner);
                    if (v == null) continue;
                    final int size = isMap ? ((Map<?, ?>) v).size() : ((java.util.Collection<?>) v).size();
                    if (size > 0) LOG.info("[probe][sweep] {}.{} = {} entries", label, f.getName(), size);
                } catch (Exception ignored) {}
            }
        }
    }

    /** All registered IWorldGenerator instances from GameRegistry's worldGenerators set. */
    private static List<Object> allRegisteredGenerators() {
        final List<Object> out = new ArrayList<>();
        try {
            final Class<?> gr = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
            for (java.lang.reflect.Field f : gr.getDeclaredFields()) {
                if (java.util.Set.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    final Object v = f.get(null);
                    if (v instanceof java.util.Set) out.addAll((java.util.Set<Object>) v);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Finds a registered IWorldGenerator instance in GameRegistry's worldGenerators set by class-name suffix. */
    private static Object findRegisteredGenerator(String classNameSuffix) {
        try {
            final Class<?> gr = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
            for (java.lang.reflect.Field f : gr.getDeclaredFields()) {
                if (java.util.Set.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    final Object v = f.get(null);
                    if (!(v instanceof java.util.Set)) continue;
                    for (Object g : (java.util.Set<?>) v) {
                        if (g.getClass()
                            .getName()
                            .endsWith(classNameSuffix)) return g;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reflectively reads Witchery's structuresList (chunk coords of placed surface structures) if present. */
    private static String dumpWitcheryStructures() {
        try {
            final Object g = findRegisteredGenerator("WitcheryWorldGenerator");
            if (g == null) return "\"witchery generator not found\"";
            for (java.lang.reflect.Field f : g.getClass()
                .getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    final java.util.List<?> list = (java.util.List<?>) f.get(g);
                    final java.util.List<String> out = new ArrayList<>();
                    for (Object c : list) out.add("\"" + c.toString() + "\"");
                    java.util.Collections.sort(out);
                    return "[" + String.join(", ", out) + "]";
                }
            }
            return "\"no list field on witchery generator\"";
        } catch (Exception e) {
            return "\"error: " + e + "\"";
        }
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
