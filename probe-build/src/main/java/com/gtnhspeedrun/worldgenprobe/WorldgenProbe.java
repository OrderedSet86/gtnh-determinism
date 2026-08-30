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
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
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
 * ./probe-<order>.json) -Dprobe.dim=N (dimension to walk, default 0 = overworld; 7 is the Twilight Forest in
 * GTNH 2.8.4) -Dprobe.tffeatures=N (Twilight Forest feature map, region radius in 16-chunk regions; -1 or absent
 * disables — independent of probe.dim, because the map reads biomes only and generates no chunks)
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
    /**
     * Population-order trace: records the exact sequence chunks get populated, for cascade forensics.
     *
     * Scoped to {@link #probeDim()}, so the trace always describes the dimension under test. At the default
     * probe.dim=0 both the guard and the emitted "P:x,z"/"G:x,z" strings are unchanged, and every stored corpus
     * stays comparable. With probe.dim=7 the overworld spawn preload drops out of the trace, which is the
     * desired reading — the report's "dim" field says which dimension the sequence belongs to.
     */
    public static final List<String> POP_SEQ = new ArrayList<>();

    /** FML's ASMEventHandler needs a public named class — anonymous listeners throw during event dispatch. */
    public static final class PopSeqHandler {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onPopulate(net.minecraftforge.event.terraingen.PopulateChunkEvent.Pre e) {
            if (e.world == null || e.world.provider.dimensionId != probeDim()) return;
            synchronized (POP_SEQ) {
                POP_SEQ.add("P:" + e.chunkX + "," + e.chunkZ);
            }
        }

        /** Fires when a chunk enters the provider — for fresh chunks this is generation order (incl. cascades). */
        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onChunkLoad(net.minecraftforge.event.world.ChunkEvent.Load e) {
            if (e.world == null || e.world.provider.dimensionId != probeDim()) return;
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

    /**
     * Report schema version, bumped on any consumer-visible change so corpora can be versioned instead of
     * regenerated wholesale. Absent field (pre-versioning corpora) = format 1. History:
     * 1 — water/clay totals, ores, chests, villages, witchery, populated flag.
     * 2 — + sand/gravel totals, waterY/clayY/sandY/gravelY per-height histograms, hardenedclay/stainedclay,
     * "surf" terrain heightmap (slime-island aware), search."eldritch" TC ring-site TE list.
     * 3 — + per-column dig metrics "sandRun"/"gravelBurial"/"clayBurial" (+ "sandRunHist"). The per-height
     * histograms cannot express single-column depth, so consumers were inferring it from the sandY span and
     * reading a thin blanket on a hillside as a deep pit; these measure it directly.
     */
    /**
     * 4: adds per-chunk "o" (orphaned-tile-entity digest, omitted when the chunk has none) and makes the block
     * digest self-delimiting via MSB/metadata presence markers. "b"/"s" therefore differ from format 3 — do not
     * compare across the boundary.
     */
    /**
     * 5: adds the opt-in "villagers", "entities" and "villagepieces" sections (-Dprobe.entities=true). These are
     * top-level and additive: "b", "s", "t" and "o" are byte-identical to format 4, and omitting the flag
     * reproduces a format-4 report exactly. Entities are deliberately NOT folded into the per-chunk digest —
     * worldgen animal spawns carry a live world.rand defect (SpawnerAnimals.performWorldGenSpawning picks the
     * species off world.rand while every other draw uses the populate-seeded Random), so a per-chunk entity key
     * would differ between any two runs and swamp the villager signal it was added to measure.
     */
    /**
     * 6: adds top-level "dim" (0 = overworld; absent in every earlier corpus), "center"/"centerSource" (walk-centre
     * provenance, never recorded before), and the opt-in "tffeatures" section (-Dprobe.tffeatures=N). "dim" is
     * load-bearing rather than informational: with -Dprobe.dim != 0 the "chunks" and "search" sections describe
     * that dimension, so a format-5 reader pooling a directory would blend dimensions silently. "spawnextra" is
     * empty for dim != 0 — recreateWorlds replicates initialWorldChunkLoad for the overworld only, so there is no
     * preload region elsewhere to hash. At probe.dim=0 every other section is byte-identical to format 5.
     */
    /**
     * 7: adds per-chunk "biomeCounts" to the "search" section — the full 256-column biome census from
     * Chunk.getBiomeArray(), raw ids including the 255 "unset" sentinel. The existing "biome"/"biomeId" fields
     * sample one column (the chunk centre) and are unchanged, so a format-6 reader is unaffected; what is new is
     * that "is every column here a no-rain biome" becomes answerable at all, which the centre sample cannot do
     * under RWG's per-column biome draw and river painting. Also emits the biomes.json sidecar next to the
     * report. Every other section is byte-identical to format 6.
     */
    public static final int REPORT_FORMAT = 7;

    /**
     * Dimension the probe walks and reports. 0 = overworld, which is what every pre-format-6 corpus contains.
     *
     * Read live rather than cached: the daemon rewrites probe.dim between jobs, long after PopSeqHandler was
     * registered, so a cached static would answer for the wrong job.
     */
    static int probeDim() {
        return Integer.getInteger("probe.dim", 0);
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
            setOrClear("probe.search", jsonField(json, "search"));
            setOrClear("probe.dim", jsonField(json, "dim"));
            setOrClear("probe.dim0only", jsonField(json, "dim0only"));
            setOrClear("probe.nohash", jsonField(json, "nohash"));
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

    /**
     * Fires on the world's first populated chunk and captures the loot table exactly once. Registered here rather
     * than folded into {@link PopSeqHandler}, which is only registered at server-started and so would miss the
     * whole spawn preload — and kept separate so POP_SEQ's contents stay comparable with existing corpora.
     */
    public static final class FirstPopulateLootHandler {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onPopulate(net.minecraftforge.event.terraingen.PopulateChunkEvent.Pre e) {
            // Deliberately dim 0, NOT probeDim(): the question this answers is "what table did the spawn preload
            // roll", and the preload is a loadAllWorlds step that only ever touches the overworld. It also
            // captures once per JVM, so retargeting it would replace a correct answer with a worse one and
            // silently change chestloot.csv for every -Dprobe.lootcsv run. Twilight Forest gains nothing from a
            // change here anyway: TFTreasure fills its chests directly and never touches ChestGenHooks.
            if (e.world == null || e.world.provider.dimensionId != 0) return;
            ChestLootExport.captureFirstPopulate();
        }
    }

    /**
     * Last hook before {@code startServer()}. On a stock instance this is also the table the spawn preload rolls,
     * because TooMuchLoot does not run until {@code FMLServerStartingEvent}, which is after the preload. That
     * equality does NOT hold once a mod moves the rewrite earlier — the determinism jar's F9 applies it at
     * {@code MinecraftServer.loadAllWorlds} — so the phase that actually answers "what did the preload roll" is
     * {@code firstpopulate}, captured by the handler above. See {@link ChestLootExport#captureFirstPopulate()}.
     */
    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        ChestLootExport.captureChestGenHooks("pre");
        if (ChestLootExport.dir() != null) MinecraftForge.EVENT_BUS.register(new FirstPopulateLootHandler());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (ChestLootExport.dir() != null) {
            final File cfg = new File("config");
            ChestLootExport.captureChestGenHooks("post");
            ChestLootExport.captureRoguelike(cfg);
            ChestLootExport.captureTfTreasure();
            // Witchery needs no capture of its own: its stone-circle refilling chests and its worldgen components
            // read the dungeonChest and mineshaftCorridor categories rather than registering a table.
            ChestLootExport.writeCombined(ChestLootExport.dir());
            ChestLootExport.writeLootBags(cfg, ChestLootExport.dir());
        }
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
        final String prefilter = System.getProperty("probe.prefilter");
        if (prefilter != null) {
            try {
                Prefilter.run(prefilter, System.getProperty("probe.prefilter.out", "prefilter.jsonl"));
            } catch (Exception e) {
                LOG.error("Prefilter failed", e);
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
                    probeTargetWorld(
                        FMLCommonHandler.instance()
                            .getMinecraftServerInstance().worldServers[0]),
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
     * "teraw": "cx,cz", "cx": N, "cz": N, "dim": N} — only seed and out are required. "dim" selects the dimension
     * to walk (0 = overworld, 7 = Twilight Forest); it must not name a dimension that "dim0only" excluded. Jobs
     * run in filename sort order, each as
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
                setOrClear("probe.dim", jsonField(json, "dim"));
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
                restoreLootTables(lootSnapForPreload(), "preload");
                recreateWorlds(server, seed, worldType, genOpts);
                restoreLootTables(lootSnapPost, "post-boot");
                final WorldServer over = DimensionManager.getWorld(0);
                if (over == null || over.getSeed() != seed)
                    throw new IllegalStateException("recreated overworld missing or wrong seed");
                runProbe(probeTargetWorld(over), order, radius, outS);
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
            // one seed per METHOD CALL: the previous slot's WorldServer local must not linger in this
            // frame's stack slots (JIT keeps loop locals live until overwritten — the last bounded
            // ~390MB retainer the path hunter could not reach from any static/thread root)
            runWarmSlot(server, seeds, i, order, radius, outTemplate, worldType, genOpts);
        }
    }

    private void runWarmSlot(MinecraftServer server, long[] seeds, int i, String order, int radius, String outTemplate,
        WorldType worldType, String genOpts) throws Exception {
        {
            final long t0 = System.currentTimeMillis();
            clearPopSeq();
            teardownAllWorlds(server);
            resetStatics();
            // -Dprobe.leakcheck=true: measure heap RETAINED after the old world is fully torn down and
            // statics reset — a growing per-slot value is the warm-batch leak (~0.5G/slot on 2.8.4).
            if (Boolean.getBoolean("probe.leakcheck")) {
                System.gc();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                System.gc();
                final Runtime rt = Runtime.getRuntime();
                LOG.info(
                    "[probe][leak] slot {}: retained heap after teardown+reset+GC = {} MB",
                    i + 1,
                    (rt.totalMemory() - rt.freeMemory()) >> 20);
                leakForensics();
                // -Dprobe.leakpaths=true: BFS the live reference graph from every static field (all
                // jars incl. forge/minecraft) + thread fields/ThreadLocals to any WEAKLY-TRACKED dead
                // world that survived GC, and print the exact retention chain.
                if (Boolean.getBoolean("probe.leakpaths")) {
                    final List<Object> alive = new ArrayList<>();
                    for (java.lang.ref.WeakReference<Object> wr : LEAK_TRACK) {
                        final Object o = wr.get();
                        if (o != null) alive.add(o);
                    }
                    LOG.info("[probe][leak] {} dead worlds still strongly reachable — hunting paths…", alive.size());
                    if (!alive.isEmpty()) findPathsToTargets(alive);
                }
                // -Dprobe.leakdump=/path.hprof: write a LIVE heap dump at this exact point (post-
                // teardown/reset/GC) on slot 3 — reflective walkers can't see every GC root; MAT can.
                final String dumpPath = System.getProperty("probe.leakdump");
                if (dumpPath != null && i + 1 == 3) {
                    try {
                        final com.sun.management.HotSpotDiagnosticMXBean hs = java.lang.management.ManagementFactory
                            .newPlatformMXBeanProxy(
                                java.lang.management.ManagementFactory.getPlatformMBeanServer(),
                                "com.sun.management:type=HotSpotDiagnostic",
                                com.sun.management.HotSpotDiagnosticMXBean.class);
                        hs.dumpHeap(dumpPath, true);
                        LOG.info("[probe][leak] heap dump written: {}", dumpPath);
                    } catch (Throwable t) {
                        LOG.warn("[probe][leak] heap dump failed: {}", t.toString());
                    }
                }
            }
            // -Dprobe.emulateclient=true: headless replica of SSP "create world #2 of a session" — do NOT
            // restore the pre-server snapshot ourselves; instead fire the real FMLServerAboutToStartEvent
            // (IntegratedServer.startServer dispatches it right before loadAllWorlds). With a fix jar lacking
            // F7 the recreate preloads from the mutated post-ServerStarting tables (the client bug); with F7
            // the jar's own event handler restores them. A/B on the resulting chest reports = headless F7 test.
            final String emu = System.getProperty("probe.emulateclient");
            if (emu != null && !emu.isEmpty() && !"false".equals(emu)) {
                // Emulate SSP "create world #N of a session": invoke @EventHandler(FMLServerAboutToStartEvent)
                // methods DIRECTLY (value "true" = every subscriber; else comma list of modids for bisection).
                // Never route through FMLCommonHandler.handleServerAboutToStart on a running server — the
                // Loader state machine is already past that state, so it QUEUES the dispatch and flushes it
                // at shutdown, after every world has generated (cost us a full phantom-mutator hunt).
                final java.util.Set<String> only = "true".equals(emu) ? null
                    : new java.util.HashSet<>(java.util.Arrays.asList(emu.split(",")));
                final Object event = new cpw.mods.fml.common.event.FMLServerAboutToStartEvent(server);
                for (cpw.mods.fml.common.ModContainer mc : cpw.mods.fml.common.Loader.instance()
                    .getModList()) {
                    if (only != null && !only.contains(mc.getModId())) continue;
                    final Object mod = mc.getMod();
                    if (mod == null) continue;
                    for (java.lang.reflect.Method meth : mod.getClass()
                        .getMethods()) {
                        if (meth.getParameterTypes().length == 1
                            && meth.getParameterTypes()[0] == cpw.mods.fml.common.event.FMLServerAboutToStartEvent.class
                            && meth.isAnnotationPresent(cpw.mods.fml.common.Mod.EventHandler.class)) {
                            try {
                                meth.invoke(mod, event);
                                LOG.info("[probe] emulateclient bisect: fired {}.{}", mc.getModId(), meth.getName());
                            } catch (Throwable t) {
                                LOG.warn(
                                    "[probe] emulateclient bisect: {} handler failed: {}",
                                    mc.getModId(),
                                    t.toString());
                            }
                        }
                    }
                }
            } else {
                restoreLootTables(lootSnapForPreload(), "preload"); // must match what a cold boot's preload used
            }
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
            runProbe(probeTargetWorld(over), order, radius, slotOut);
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
    /**
     * leakcheck: weakly track torn-down dim0 worlds so surviving ones can be path-hunted. The probe dimension is
     * tracked too when it is not dim 0: a probe.dim=7 warm batch builds one extra full WorldServer and chunk map
     * per slot, which is exactly the population this hunter exists to find.
     */
    private static final List<java.lang.ref.WeakReference<Object>> LEAK_TRACK = new ArrayList<>();

    private static void teardownAllWorlds(MinecraftServer server) throws Exception {
        final File worldDir = new File(server.getFolderName());
        for (WorldServer ws : DimensionManager.getWorlds()) {
            if (Boolean.getBoolean("probe.leakcheck")
                && (ws.provider.dimensionId == 0 || ws.provider.dimensionId == probeDim())) {
                LEAK_TRACK.add(new java.lang.ref.WeakReference<Object>(ws));
            }
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(ws));
            ws.flush();
            DimensionManager.setWorld(ws.provider.dimensionId, null);
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        warnIfChunkIOPending();
        deleteRecursively(worldDir);
        if (worldDir.exists()) throw new IllegalStateException("could not delete " + worldDir.getAbsolutePath());
        // The stale-level.dat guard in recreateWorlds proves the save directory is gone; this proves the probe
        // dimension is actually deregistered, so the next slot cannot walk the previous seed's world.
        if (DimensionManager.getWorld(probeDim()) != null)
            throw new IllegalStateException("dimension " + probeDim() + " survived teardown");
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
     * results.
     *
     * <p>
     * Verified against the shipped 2.7.4 jars (TC 4.2.3.5a, witchery 0.24.1, GT 5.09.50.119, RWG alpha-1.5.0), the
     * 2.8.4 jars (GT 5.09.51.482), and the 2.9/daily jars (GT 5.09.54.115, RWG alpha-1.5.2). Fields that GT deleted
     * in the 5.09.54.x worldgen rework are handled by {@link #clearOptionalStaticCollection}: still hard-failing
     * where the field exists, skipped with a log line where it does not. Everything else stays unconditional.
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
        // GT 5.09.54.x deleted mList and ProcChunks along with the deferred-worldgen queue they served, so both are
        // optional; on 2.7.4-2.8.4 they still exist and a non-empty mList at teardown is still a contamination signal.
        clearOptionalStaticCollection(gt, "mList", "queued containers");
        clearOptionalStaticCollection(gt, "ProcChunks", "processed chunks");
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
        // upstream-reportable. Cleared per seed.
        //
        // Optional by CLASS, not just by field: GregTech 5.09.54.x absorbed BartWorks, and the 2.9/daily packs ship
        // no bartworks jar at all. An unguarded Class.forName here killed every warm batch on daily — the second
        // 54.x casualty in this method after mList/ProcChunks. Where the class exists the reset stays mandatory.
        // Non-empty is normal here rather than a contamination signal: the set is add-only across slots by design.
        clearOptionalStaticCollection(
            "bartworks.system.oregen.BWWordGenerator$WorldGenContainer",
            "mGenerated",
            "stale BartWorks mGenerated chunk entries",
            false);
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
        // === Dead-world retainers (2026-07-24 leak forensics: ~372 MB/slot on 2.8.4, one full dead
        // WorldServer chunk map per slot). Named by the leakcheck bus-walker; pruned every slot. ===
        // THE dominant leak (found by the in-JVM path hunter): SpecialMobs queues every worldgen-
        // spawned mob for replacement in a static ArrayDeque processed on server ticks — the probe
        // barely ticks, so ~190 entries/slot accumulate, each pinning entity -> world -> chunk map.
        try {
            final java.lang.reflect.Field esF = Class.forName("toast.specialMobs.TickHandler")
                .getDeclaredField("entityStack");
            esF.setAccessible(true);
            final java.util.Collection<?> es = (java.util.Collection<?>) esF.get(null);
            if (!es.isEmpty()) {
                LOG.info("[probe] clearing {} queued SpecialMobs entity replacements (world retainers)", es.size());
                es.clear();
            }
        } catch (ClassNotFoundException e) {
            LOG.warn("[probe] SpecialMobs TickHandler not present — skipping entityStack reset");
        }
        // AE2's tick handler keeps a Map<World, Queue> of scheduled callbacks and never evicts
        // unloaded worlds — secondary retainer.
        final Object aeTick = findBusListenerInstance("appeng.hooks.TickHandler");
        if (aeTick != null) {
            final int nAe = pruneDeadWorldKeys(aeTick.getClass(), aeTick, "callQueue");
            if (nAe > 0) LOG.info("[probe] pruned {} dead-world entries from AE2 TickHandler.callQueue", nAe);
        }
        // ForgeChunkManager's world-keyed statics accumulate dead-world keys across warm slots.
        try {
            final Class<?> fcm = Class.forName("net.minecraftforge.common.ForgeChunkManager");
            for (String fn : new String[] { "tickets", "forcedChunks", "dormantChunkCache" }) {
                final int nF = pruneDeadWorldKeys(fcm, null, fn);
                if (nF > 0) LOG.info("[probe] pruned {} dead-world keys from ForgeChunkManager.{}", nF, fn);
            }
        } catch (ClassNotFoundException ignored) {}
        // ServerUtilities' Universe pins the BOOT overworld forever (never re-pointed on our warm
        // recreate). Null it; the probe never ticks ServerUtilities logic between recreate and walk.
        final Object universe = findBusListenerInstance("serverutils.lib.data.Universe");
        if (universe != null) {
            try {
                final java.lang.reflect.Field wf = universe.getClass()
                    .getDeclaredField("world");
                wf.setAccessible(true);
                if (wf.get(universe) != null) {
                    wf.set(universe, null);
                    LOG.info("[probe] released ServerUtilities Universe.world (pinned boot overworld)");
                }
            } catch (Throwable t) {
                LOG.warn("[probe] Universe.world release failed: {}", t.toString());
            }
        }
        // Gadomancy's maze-generation fake world (static GEN) buffers every generated maze chunk in a
        // plain HashMap and never clears it — steady per-slot growth in warm batches.
        try {
            final java.lang.reflect.Field genF = Class.forName("makeo.gadomancy.common.utils.world.TCMazeHandler")
                .getDeclaredField("GEN");
            genF.setAccessible(true);
            final Object gen = genF.get(null);
            if (gen != null) {
                clearMapField(gen, "chunks");
                clearMapField(gen, "gettedTE");
            }
        } catch (ClassNotFoundException e) {
            LOG.warn("[probe] Gadomancy TCMazeHandler not present — skipping fake-world buffer reset");
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
        // Two package names: the current one, and the pre-rename one that jars up to 0.4 shipped (commit 1340ae3).
        final Class<?> oracle = findClass(
            "com.gtnhspeedrun.determinism.worldgen.TerrainOracle",
            "com.gtnhspeedrun.tcworldgenfix.TerrainOracle");
        if (oracle == null) {
            LOG.warn(
                "[probe] gtnhdeterminism fix jar NOT installed — warm-mode results are only meaningful with the fixes");
        } else {
            final java.lang.reflect.Field cache = oracle.getDeclaredField("CACHE");
            cache.setAccessible(true);
            // TerrainOracle guards its cache with `static synchronized`, i.e. the Class monitor.
            synchronized (oracle) {
                ((Map<?, ?>) cache.get(null)).clear();
                final java.lang.reflect.Field cw = oracle.getDeclaredField("cacheWorld");
                cw.setAccessible(true);
                cw.set(null, null);
            }
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
    private static java.util.Map<String, LootSnap> lootSnapPre; // at FMLLoadComplete (pre-TML)
    private static java.util.Map<String, LootSnap> lootSnapPost; // post-boot (post-TML)

    /**
     * One category's full restorable state. Restoring only {@code contents} rebuilds a table that never existed:
     * TooMuchLoot moves {@code countMin}/{@code countMax} as well as the item pool ({@code villageBlacksmith} is
     * 3-9 pre and 4-11 post), so a contents-only restore pairs the pristine pool with the mutated roll count, and
     * the extra {@code generateChestContents} iterations then shift every later draw in that chunk.
     */
    private static final class LootSnap {

        final java.util.List<Object> contents;
        final int min;
        final int max;

        LootSnap(java.util.List<Object> contents, int min, int max) {
            this.contents = contents;
            this.min = min;
            this.max = max;
        }
    }

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

    /**
     * Report the determinism jar's F10 counters, so a run says how many structure chests were re-derived from
     * position and how many fell back to stock's roll. A fix that silently does nothing looks exactly like a fix
     * that works, and the fallback path is the one that would hide.
     */
    private static void logChestFillStats() {
        try {
            LOG.info(
                "[probe][loot] F10 structure chests: {}",
                Class.forName("com.gtnhspeedrun.determinism.worldgen.ChestFillContext")
                    .getMethod("stats")
                    .invoke(null));
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            // determinism jar not installed, or older than F10
        } catch (Exception e) {
            LOG.warn("[probe][loot] could not read F10 counters: {}", e.toString());
        }
    }

    /**
     * Which snapshot a replicated spawn preload must generate against, i.e. what a real cold boot would have used.
     *
     * <p>
     * On a stock instance that is the pre-TooMuchLoot table: a cold boot preloads inside {@code loadAllWorlds},
     * before {@code FMLServerStartingEvent}. Restoring the post table there was the 2.8.4 warm-slot contamination —
     * 17 wrong chests per seed, all inside the preload radius, deterministic and therefore invisible to
     * warm[A-&gt;A] self-tests.
     *
     * <p>
     * With the determinism jar's F9 the split no longer exists: TooMuchLoot is applied at {@code loadAllWorlds}
     * before any chunk, so a cold boot's preload rolls the post table and the warm path must match. Restoring
     * {@code pre} under F9 would be the same contamination with the sign flipped.
     */
    private static java.util.Map<String, LootSnap> lootSnapForPreload() {
        if (f9Active()) return lootSnapPost;
        return lootSnapPre;
    }

    private static Boolean f9Cache;

    private static boolean f9Active() {
        if (f9Cache == null) {
            try {
                Class.forName("com.gtnhspeedrun.determinism.worldgen.EarlyLootTables");
                f9Cache = Boolean.TRUE;
                LOG.info("[probe][loot] determinism jar F9 present — replicated preloads use the post-TML table");
            } catch (ClassNotFoundException absent) {
                f9Cache = Boolean.FALSE;
            }
        }
        return f9Cache;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, LootSnap> captureLootTables() throws Exception {
        final java.lang.reflect.Field contentsF = lootContentsField();
        final java.util.Map<String, LootSnap> snap = new java.util.HashMap<>();
        for (Map.Entry<String, net.minecraftforge.common.ChestGenHooks> e : chestInfo().entrySet()) {
            final net.minecraftforge.common.ChestGenHooks hooks = e.getValue();
            snap.put(
                e.getKey(),
                new LootSnap(
                    new ArrayList<>((java.util.List<Object>) contentsF.get(hooks)),
                    hooks.getMin(),
                    hooks.getMax()));
        }
        return snap;
    }

    /** Ensure the post-boot snapshot exists; call once before any warm slot mutates table state. */
    private static void ensurePostBootLootSnapshot() throws Exception {
        if (lootSnapPost != null) return;
        lootSnapPost = captureLootTables();
        LOG.info("[probe] post-boot loot snapshot: {} categories", lootSnapPost.size());
        if (lootSnapPre != null) {
            for (Map.Entry<String, LootSnap> e : lootSnapPost.entrySet()) {
                final LootSnap pre = lootSnapPre.get(e.getKey());
                final LootSnap post = e.getValue();
                if (pre == null) continue;
                final boolean rolls = pre.min != post.min || pre.max != post.max;
                if (!lootDigest(pre.contents).equals(lootDigest(post.contents)) || rolls) {
                    LOG.info(
                        "[probe][loot] category {} is mutated between load-complete and server-started "
                            + "({} -> {} entries, rolls {}-{} -> {}-{}) — spawn-preload chests roll the FORMER "
                            + "in cold boots:",
                        e.getKey(),
                        pre.contents.size(),
                        post.contents.size(),
                        pre.min,
                        pre.max,
                        post.min,
                        post.max);
                    logLootDiff(e.getKey(), pre.contents, post.contents);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreLootTables(java.util.Map<String, LootSnap> snap, String label) throws Exception {
        if (snap == null) {
            LOG.warn("[probe][loot] {} snapshot missing — cannot restore", label);
            return;
        }
        final java.lang.reflect.Field contentsF = lootContentsField();
        for (Map.Entry<String, net.minecraftforge.common.ChestGenHooks> e : chestInfo().entrySet()) {
            final LootSnap want = snap.get(e.getKey());
            if (want == null) continue; // category appeared later; leave as-is
            final net.minecraftforge.common.ChestGenHooks hooks = e.getValue();
            final java.util.List<Object> live = (java.util.List<Object>) contentsF.get(hooks);
            live.clear();
            live.addAll(want.contents);
            // Roll counts are part of the table. villageBlacksmith is the category this actually moves.
            hooks.setMin(want.min);
            hooks.setMax(want.max);
        }
        final net.minecraftforge.common.ChestGenHooks bs = chestInfo().get("villageBlacksmith");
        if (bs != null) {
            LOG.info("[probe][loot] restored {} — villageBlacksmith rolls {}-{}", label, bs.getMin(), bs.getMax());
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

    private static final java.util.Set<String> ABSENT_STATICS_LOGGED = new java.util.HashSet<>();

    /**
     * Clears a static collection that only some supported mod versions declare. Present means the same hard-failing
     * contract as the rest of {@link #resetStatics}: a non-empty collection at teardown is a contamination signal and
     * is warned about, and any other reflective failure propagates. Absent means the mod version dropped the field,
     * which is logged once so a warm run never quietly loses a reset it used to perform.
     */
    private static void clearOptionalStaticCollection(Class<?> owner, String name, String what) throws Exception {
        clearOptionalStaticCollection(owner, name, what, true);
    }

    /**
     * As above, but the owning CLASS is optional too — for statics belonging to a mod that some supported pack
     * versions do not ship at all. Absence of the class is a skip; absence of the field on a class that IS present
     * is still a skip, but anything else propagates.
     */
    private static void clearOptionalStaticCollection(String className, String name, String what,
        boolean warnIfNonEmpty) throws Exception {
        final Class<?> owner = findClass(className);
        if (owner == null) {
            if (ABSENT_STATICS_LOGGED.add(className)) {
                LOG.info("[probe] {} not installed in this pack — reset skipped", className);
            }
            return;
        }
        clearOptionalStaticCollection(owner, name, what, warnIfNonEmpty);
    }

    private static void clearOptionalStaticCollection(Class<?> owner, String name, String what, boolean warnIfNonEmpty)
        throws Exception {
        final java.lang.reflect.Field f;
        try {
            f = owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (ABSENT_STATICS_LOGGED.add(owner.getName() + "." + name)) {
                LOG.info("[probe] {}.{} absent in this version — reset skipped", owner.getSimpleName(), name);
            }
            return;
        }
        f.setAccessible(true);
        final java.util.Collection<?> c = (java.util.Collection<?>) f.get(null);
        if (!c.isEmpty()) {
            if (warnIfNonEmpty) LOG.warn("[probe] {} had {} {} at teardown!", name, c.size(), what);
            else LOG.info("[probe] clearing {} {}", c.size(), what);
        }
        c.clear();
    }

    /** First of {@code names} that is loadable, or null if none are. */
    private static Class<?> findClass(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
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
        // Scope the fix jar's worldgen traces to the world we are about to generate. A warm run also generates
        // the server's own boot world, and instrumentation cannot otherwise tell the two apart — a trace corpus
        // that mixed them produced two wrong findings before it was caught. A system property keeps this
        // dependency-free in both directions; the fix jar treats "unset" as "trace everything", so cold runs and
        // standalone use are unaffected.
        System.setProperty("gtnhdet.tracescope", Long.toString(seed));
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
        //
        // The probe dimension is always exempt, so the flag now reads "dim 0 plus the dimension under test". The
        // name is kept because renaming it would touch warm-probe.sh, seed-search.sh, probe-queue.sh, the daemon
        // job parser and the CRIU handler at once. At probe.dim=0 the loop is bit-identical to before.
        final boolean dim0Only = Boolean.getBoolean("probe.dim0only");
        final int probeDim = probeDim();
        for (int dim : DimensionManager.getStaticDimensionIDs()) {
            if (dim0Only && dim != 0 && dim != probeDim) continue;
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

    /**
     * Resolves the world the walk runs in from -Dprobe.dim, defaulting to the overworld it is handed.
     *
     * The seed equality check is the generic form of the Twilight Forest seed hazard: Forge's
     * {@code World.getSeed()} delegates to {@code provider.getSeed()}, so any provider that overrides it can
     * decouple its dimension from the world seed and make every seed in a batch generate the same world.
     * {@link #assertTwilightForestPreconditions} names the TF case specifically so the error is actionable; this
     * catches every other one structurally.
     */
    private static WorldServer probeTargetWorld(WorldServer over) {
        final int dim = probeDim();
        if (dim == 0) return over;
        final WorldServer target = DimensionManager.getWorld(dim);
        if (target == null) throw new IllegalStateException(
            "-Dprobe.dim=" + dim
                + " but that dimension does not exist. Either the id is wrong, or -Dprobe.dim0only skipped it "
                + "(it exempts the probe dimension, so this means the flag was read before probe.dim was set).");
        if (target.getSeed() != over.getSeed()) {
            // Give the dimension-specific check the first word: it names the config key responsible,
            // which this generic message cannot. It throws when it recognises the cause; if it
            // returns, fall through and report what we do know.
            assertTwilightForestPreconditions();
            throw new IllegalStateException(
                "dimension " + dim
                    + " reports seed "
                    + target.getSeed()
                    + " but the overworld is "
                    + over.getSeed()
                    + " — its WorldProvider overrides getSeed(), so this dimension does not follow the world seed");
        }
        return target;
    }

    /**
     * Hard-fails the run when the Twilight Forest is configured in a way that silently invalidates the report.
     *
     * Runs before the walk so a misconfiguration costs milliseconds rather than minutes of generation. Reads the
     * live statics rather than parsing config/TwilightForest.cfg: {@code registerWorldChunkManager} copies
     * {@code TwilightForestMod.dimensionID} into {@code provider.dimensionId}, so the static is the runtime truth.
     */
    private static void assertTwilightForestPreconditions() {
        final Class<?> mod = TwilightForestProbe.modClass();
        if (mod == null) {
            if (TwilightForestProbe.regionRadius() >= 0)
                throw new IllegalStateException("-Dprobe.tffeatures is set but Twilight Forest is not loaded");
            return; // probe.dim names some non-TF dimension in a pack without TF: nothing to check.
        }
        // Only when TF data is actually being produced. Probing some other dimension (say the Nether) with TF
        // merely installed must not fail here.
        if (probeDim() != TwilightForestProbe.dimensionId() && TwilightForestProbe.regionRadius() < 0) return;
        try {
            final Object seedOverride = mod.getField("twilightForestSeed")
                .get(null);
            if (seedOverride != null && !seedOverride.toString()
                .isEmpty())
                throw new IllegalStateException(
                    "config/TwilightForest.cfg S:TwilightForestSeed is set to \"" + seedOverride
                        + "\". WorldProviderTwilightForest.getSeed() then returns that string's hashCode instead of "
                        + "the world seed, and Forge's World.getSeed() delegates to the provider — so TF terrain, "
                        + "TF features and every GT ore-vein seed in TF become independent of the world seed. "
                        + "Every seed in this batch would produce an identical Twilight Forest.");
            if (mod.getField("oldMapGen")
                .getBoolean(null))
                throw new IllegalStateException(
                    "config/TwilightForest.cfg B:OldMapGen=true selects the pre-1.7 feature grid "
                        + "(generateFeatureForOldMapGen / getNearestCenterXYZOld). The emitted feature map would "
                        + "describe geometry this world does not have.");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read TwilightForestMod configuration statics", e);
        }
    }

    private void runProbe(WorldServer world, String order, int radius, String out) throws Exception {
        final long seed = world.getSeed();
        final int dim = probeDim();
        final int walkR = radius + 1;
        LOG.info("[probe] seed={} dim={} order={} radius={} (walking r={})", seed, dim, order, radius, walkR);
        if (dim != 0 || TwilightForestProbe.regionRadius() >= 0) assertTwilightForestPreconditions();

        // In search mode the region of interest is the spawn neighborhood (chest sweep is spawn-relative), so
        // default the walk center to the spawn chunk; explicit probe.cx/cz still override.
        //
        // This is also the right centre for a Twilight Forest walk and needs no special case: WorldServerMulti
        // gives dim 7 a DerivedWorldInfo, and TF adds no getSpawnPoint override, so getSpawnPoint() there IS the
        // overworld spawn. TF is 1:1 with the overworld in X/Z (WorldProviderTwilightForest does not override
        // getMovementFactor, unlike the Nether's 8.0), so a portal built at the overworld spawn arrives here.
        final boolean searchMode = Boolean.getBoolean("probe.search");
        final String centerSource = System.getProperty("probe.cx") != null || System.getProperty("probe.cz") != null
            ? "explicit"
            : searchMode ? "spawn" : "origin";
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
        // The search report covers ALL loaded chunks (cascade ring included), so their TEs must exist too.
        // Runs AFTER the window loop, in sorted order, so the established materialize sequence — and therefore
        // the hash section — is byte-unchanged.
        {
            final List<Chunk> extra = new ArrayList<>();
            for (Object o : world.theChunkProviderServer.loadedChunks) {
                final Chunk c = (Chunk) o;
                if (Math.abs(c.xPosition - cx) <= radius && Math.abs(c.zPosition - cz) <= radius) continue;
                extra.add(c);
            }
            extra.sort(
                java.util.Comparator.<Chunk>comparingInt(ch -> ch.xPosition)
                    .thenComparingInt(ch -> ch.zPosition));
            for (Chunk c : extra) materializeTileEntities(world, c);
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
        //
        // Dim 0 only: recreateWorlds replicates initialWorldChunkLoad for the overworld alone, so in any other
        // dimension there is no preload region and this loop would GENERATE up to 625 fresh chunks and hash them
        // into a section named for a preload that never ran.
        final Map<String, String> spawnExtra = new TreeMap<>();
        if (dim == 0) {
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
        // Villages are an overworld generator. In any other dimension villageStructureMap finds no MapGenVillage
        // and returns its "not found" string, which reads as a failure rather than a not-applicable. Emit an
        // empty array instead so the key keeps its type for every consumer.
        final String structures = dim == 0 ? dumpVillages(world) : "[]";
        final String witchery = dumpWitcheryStructures();
        // Opt-in: entity sections are absent from format-4 corpora, and the worldgen animal-species defect makes
        // the census differ between any two runs until that is fixed.
        String villagers = null, entityCensus = null, villagePieces = null;
        if (Boolean.getBoolean("probe.entities")) {
            final java.util.List<net.minecraft.entity.Entity> ents = collectEntities(world);
            villagers = dumpVillagers(ents);
            entityCensus = dumpEntityCensus(ents);
            villagePieces = dumpVillagePieces(world, ents);
            LOG.info("[probe] entities: {} in window, villager lines follow in report", ents.size());
        }
        final String search = Boolean.getBoolean("probe.search") ? buildSearchReport(world, radius, cx, cz) : null;
        // Independent of probe.dim: the map reads biomes only, and World.getBiomeGenForCoords falls through to
        // the chunk manager for unloaded chunks, so an ordinary dim-0 run emits it without generating anything.
        final String tfFeatures = TwilightForestProbe.buildFeatureMap(radius, cx, cz);
        if (search != null) {
            dumpGtMaterialsOnce(new File(out).getParentFile());
            // Same sidecar contract as gtmats.json: seed-independent, written once, read back by the Python
            // side. Corpora need it so a biome id resolves to rain/humidity without hardcoding a pack's table.
            BiomeTable.dumpOnce(new File(out).getParentFile(), world.getWorldChunkManager());
        }
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
        sb.append("{\n  \"format\": ")
            .append(REPORT_FORMAT)
            .append(",\n  \"seed\": ")
            .append(seed)
            .append(",\n  \"dim\": ")
            .append(dim)
            .append(",\n  \"order\": \"")
            .append(order)
            .append("\",\n  \"radius\": ")
            .append(radius)
            .append(",\n  \"center\": [")
            .append(cx)
            .append(", ")
            .append(cz)
            .append("],\n  \"centerSource\": \"")
            .append(centerSource)
            .append("\",\n  \"chunks\": {\n");
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
            .append(villagers == null ? "" : ",\n  \"villagers\": " + villagers)
            .append(villagePieces == null ? "" : ",\n  \"villagepieces\": " + villagePieces)
            .append(entityCensus == null ? "" : ",\n  \"entities\": " + entityCensus)
            .append(search == null ? "" : ",\n  \"search\": " + search)
            .append(tfFeatures == null ? "" : ",\n  \"tffeatures\": " + tfFeatures)
            .append(tedetail)
            .append("\n}\n");
        final File f = new File(out);
        try (FileWriter w = new FileWriter(f)) {
            w.write(sb.toString());
        }
        LOG.info("[probe] wrote {} chunk hashes to {}", hashes.size(), f.getAbsolutePath());
        logChestFillStats();

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
     * re-running the server. Per chunk: biome, water/clay/sand/gravel block counts plus per-height histograms
     * (waterY/clayY/sandY/gravelY, sparse {y: count}), hardened/stained clay counts (bbf% clay-dust source), a
     * slime-island-aware terrain heightmap ("surf", 256 columns hex-encoded — see surfaceY), GT ore TE m-value
     * histogram (material = m%1000; thousands digit = host-stone variant / small-ore flag — decode offline), and
     * every IInventory tile entity's full contents (village/dungeon chests: tier-skip loot lives here). Spawn
     * point at the top because spawn-relative distance is the primary search criterion.
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
        // Every chunk that exists in the world at walk time is reported (spec: generated data is never thrown
        // away). The walk is radius+1 and population cascade generates terrain beyond that; those chunks are
        // already paid for. Decoration-pending chunks are flagged "populated": false (terrain-only — no
        // ores/chests yet) so "empty" is distinguishable from "not decorated". Sorted by (x,z): the report is
        // independent of loadedChunks iteration order.
        final List<String> eldritch = new ArrayList<>();
        boolean oreTilesSeen = false;
        final List<Chunk> loadedChunks = new ArrayList<>();
        for (Object o : world.theChunkProviderServer.loadedChunks) loadedChunks.add((Chunk) o);
        loadedChunks.sort(
            java.util.Comparator.<Chunk>comparingInt(ch -> ch.xPosition)
                .thenComparingInt(ch -> ch.zPosition));
        boolean firstChunk = true;
        {
            for (final Chunk c : loadedChunks) {
                final int ccx = c.xPosition, ccz = c.zPosition;
                final net.minecraft.world.biome.BiomeGenBase biome = world
                    .getBiomeGenForCoords((ccx << 4) + 8, (ccz << 4) + 8);
                // Full per-column biome census, because "biome" above is ONE column of 256 (the chunk centre).
                // A chunk is rarely uniform under RWG: the generator draws each column from a blended
                // distribution and then paints river biomes over it, so a predicate like "every column here is a
                // no-rain biome" is unanswerable from the centre sample alone.
                //
                // Read column by column, NOT through Chunk.getBiomeArray(): EndlessIDs replaces the vanilla
                // byte array and throws from that accessor ("Crashing in fear of potential world corruption"),
                // which takes the whole run down on a 2.9/daily pack. Same constraint hashChunk already works
                // around for the section arrays. getBiomeGenForWorldCoords takes chunk-LOCAL coordinates and
                // resolves the 255 "unset" sentinel itself, so no sentinel reaches the report.
                final Map<Integer, Integer> biomeCounts = new TreeMap<>();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        biomeCounts.merge(
                            c.getBiomeGenForWorldCoords(lx, lz, world.getWorldChunkManager()).biomeID,
                            1,
                            Integer::sum);
                    }
                }
                int water = 0, clay = 0, sand = 0, gravel = 0, hclay = 0;
                final Map<Integer, Integer> waterY = new TreeMap<>();
                final Map<Integer, Integer> clayY = new TreeMap<>();
                final Map<Integer, Integer> sandY = new TreeMap<>();
                final Map<Integer, Integer> gravelY = new TreeMap<>();
                final Map<Integer, Integer> stainedClay = new TreeMap<>(); // dye meta -> count
                // Section-array scan (counts are order-independent): null sections skip 4096 blocks at a time.
                // Per-y histograms because flat totals mislead routing: 2000 "water" may be a deep ocean column,
                // not a swimmable lake — density at a given height is what the seed searcher needs. Same for
                // clay/sand/gravel (dig targets), and hardened/stained clay grinds to clay dust (bbf%).
                for (final ExtendedBlockStorage ebs : c.getBlockStorageArray()) {
                    if (ebs == null) continue;
                    final int baseY = ebs.getYLocation();
                    for (int ly = 0; ly < 16; ly++) {
                        final int wy = baseY + ly;
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                final net.minecraft.block.Block b = ebs.getBlockByExtId(lx, ly, lz);
                                if (b == net.minecraft.init.Blocks.water
                                    || b == net.minecraft.init.Blocks.flowing_water) {
                                    water++;
                                    waterY.merge(wy, 1, Integer::sum);
                                } else if (b == net.minecraft.init.Blocks.clay) {
                                    clay++;
                                    clayY.merge(wy, 1, Integer::sum);
                                } else if (b == net.minecraft.init.Blocks.sand) {
                                    sand++;
                                    sandY.merge(wy, 1, Integer::sum);
                                } else if (b == net.minecraft.init.Blocks.gravel) {
                                    gravel++;
                                    gravelY.merge(wy, 1, Integer::sum);
                                } else if (b == net.minecraft.init.Blocks.hardened_clay) {
                                    hclay++;
                                } else if (b == net.minecraft.init.Blocks.stained_hardened_clay) {
                                    stainedClay.merge(ebs.getExtBlockMetadata(lx, ly, lz), 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
                // Terrain heightmap, one byte per column (row-major z*16+x, hex-encoded). Not the sky-visible
                // heightmap: vegetation/structures don't count as ground, and thin runs floating over a big air
                // gap (TiC slime islands) are skipped — see surfaceY().
                //
                // Alongside it, three per-column dig metrics on the same row-major layout. A per-height
                // histogram cannot express how deep any single column is: a 4-block sand blanket draped over a
                // 13-block hillside has a 13-level sandY span, and every span- or footprint-based estimate
                // reads that as a deep pit. Measured per column instead, so gravity-farm depth and overburden
                // are exact rather than inferred:
                // sandRun — consecutive sand downward from the surface (0 = surface is not sand)
                // gravelBurial — overburden above the topmost gravel (0 = exposed at the surface)
                // clayBurial — same for clay (0 = riverbed/surface clay)
                // Burial scans stop DIG_PROBE_DEPTH below the surface (deeper is not a speedrun dig target)
                // and report NO_REACH there.
                final StringBuilder surfHex = new StringBuilder(512);
                final StringBuilder sandRunHex = new StringBuilder(512);
                final StringBuilder gravelBurialHex = new StringBuilder(512);
                final StringBuilder clayBurialHex = new StringBuilder(512);
                final Map<Integer, Integer> sandRunHist = new TreeMap<>();
                int maxSandRun = 0, minGravelBurial = NO_REACH, minClayBurial = NO_REACH;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        final int ts = surfaceY(c, lx, lz);
                        surfHex.append(String.format("%02x", ts));
                        int run = 0;
                        while (run < NO_REACH - 1 && ts - run >= 0
                            && c.getBlock(lx, ts - run, lz) == net.minecraft.init.Blocks.sand) {
                            run++;
                        }
                        if (run > maxSandRun) maxSandRun = run;
                        if (run > 0) sandRunHist.merge(run, 1, Integer::sum);
                        sandRunHex.append(String.format("%02x", run));
                        int gb = NO_REACH, cb = NO_REACH;
                        final int floor = Math.max(0, ts - DIG_PROBE_DEPTH);
                        for (int y = ts; y >= floor && (gb == NO_REACH || cb == NO_REACH); y--) {
                            final net.minecraft.block.Block b = c.getBlock(lx, y, lz);
                            if (gb == NO_REACH && b == net.minecraft.init.Blocks.gravel) gb = ts - y;
                            if (cb == NO_REACH && b == net.minecraft.init.Blocks.clay) cb = ts - y;
                        }
                        if (gb < minGravelBurial) minGravelBurial = gb;
                        if (cb < minClayBurial) minClayBurial = cb;
                        gravelBurialHex.append(String.format("%02x", gb));
                        clayBurialHex.append(String.format("%02x", cb));
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
                    } else if (te.getClass()
                        .getSimpleName()
                        .startsWith("TileEldritch")) {
                            // TC eldritch ring/obelisk sites (obelisk/altar/crab-spawner TEs) — recorded so
                            // corpora can validate lottery-v2 density against stock and route to obelisks.
                            eldritch.add(
                                "\"" + te.getClass()
                                    .getSimpleName() + "@" + te.xCoord + "," + te.yCoord + "," + te.zCoord + "\"");
                        }
                }
                if (!firstChunk) sb.append(",\n");
                firstChunk = false;
                // Attribution goes on the chunk, not on the chest: every chest in a chunk belongs to the same
                // feature, and dumpInventory's output must stay byte-stable because the stage-0 prefilter emits
                // chests through that same serializer. Empty string when the chunk is outside any feature.
                final String tfFeature = TwilightForestProbe.chunkFeatureJson(world, ccx, ccz);
                sb.append("      \"")
                    .append(ccx)
                    .append(",")
                    .append(ccz)
                    .append("\": {\"biome\": \"")
                    .append(jsonEscape(biome.biomeName))
                    .append("\", \"biomeId\": ")
                    .append(biome.biomeID)
                    .append(", \"biomeCounts\": ")
                    .append(jsonIntMap(biomeCounts))
                    .append(tfFeature)
                    .append(", \"water\": ")
                    .append(water)
                    .append(", \"clay\": ")
                    .append(clay)
                    .append(", \"sand\": ")
                    .append(sand)
                    .append(", \"gravel\": ")
                    .append(gravel);
                if (hclay > 0) sb.append(", \"hardenedclay\": ")
                    .append(hclay);
                if (!stainedClay.isEmpty()) sb.append(", \"stainedclay\": ")
                    .append(jsonIntMap(stainedClay));
                if (!waterY.isEmpty()) sb.append(", \"waterY\": ")
                    .append(jsonIntMap(waterY));
                if (!clayY.isEmpty()) sb.append(", \"clayY\": ")
                    .append(jsonIntMap(clayY));
                if (!sandY.isEmpty()) sb.append(", \"sandY\": ")
                    .append(jsonIntMap(sandY));
                if (!gravelY.isEmpty()) sb.append(", \"gravelY\": ")
                    .append(jsonIntMap(gravelY));
                sb.append(", \"surf\": \"")
                    .append(surfHex)
                    .append("\"");
                // Emitted only where they carry signal — an all-sentinel array costs 512 chars per chunk and
                // says nothing. The histogram is always cheap and is enough to rank a chunk; the full array is
                // for adjacency (gravity farming needs a contiguous block of columns, not scattered depth).
                if (!sandRunHist.isEmpty()) sb.append(", \"sandRunHist\": ")
                    .append(jsonIntMap(sandRunHist));
                if (maxSandRun >= 2) sb.append(", \"sandRun\": \"")
                    .append(sandRunHex)
                    .append("\"");
                if (minGravelBurial <= GRAVEL_REPORT_BURIAL) sb.append(", \"gravelBurial\": \"")
                    .append(gravelBurialHex)
                    .append("\"");
                if (minClayBurial < NO_REACH) sb.append(", \"clayBurial\": \"")
                    .append(clayBurialHex)
                    .append("\"");
                if (!c.isTerrainPopulated) sb.append(", \"populated\": false");
                if (!ores.isEmpty()) {
                    oreTilesSeen = true;
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
        // An empty ore census reads as "this seed has no ores", which is indistinguishable from "the census cannot
        // see ores on this pack". GT 5.09.54.x placed worldgen ores as plain blocks — OreManager.setOreForWorldGen
        // calls world.setBlock — so the TileEntityOres walk above finds nothing. Say which one happened. Block-level
        // ore reporting for 54.x is not implemented; chunk hashes and region-block diffs still cover ores, so
        // determinism testing is unaffected and only the human-readable per-seed ore census is missing.
        if (!oreTilesSeen && cpw.mods.fml.common.Loader.isModLoaded("gregtech")) {
            LOG.warn(
                "[probe] no ore tile entities found — GT 5.09.54.x stores worldgen ores as blocks, so the per-chunk"
                    + " ore census in this report is EMPTY, not zero. Use region-block diffs for ore comparisons.");
        }
        sb.append("\n    },\n    \"eldritch\": [")
            .append(String.join(", ", eldritch))
            .append("]\n  }");
        return sb.toString();
    }

    private static String jsonIntMap(Map<Integer, Integer> m) {
        final StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"")
                .append(e.getKey())
                .append("\": ")
                .append(e.getValue());
        }
        return sb.append("}")
            .toString();
    }

    /**
     * Ground, for the surface heightmap: solid AND not vegetation/structure material. Wood excludes tree trunks,
     * chests and village-house planks; leaves/cactus/gourd exclude canopies and desert props; glass excludes
     * greenhouse roofs. Water is not ground (a seabed chest is reachable without digging — its burial depth
     * should be 0, the water column is visible separately in waterY).
     */
    private static boolean isTerrain(net.minecraft.block.Block b) {
        if (b == net.minecraft.init.Blocks.air) return false;
        final net.minecraft.block.material.Material m = b.getMaterial();
        if (!m.isSolid()) return false;
        return m != net.minecraft.block.material.Material.wood && m != net.minecraft.block.material.Material.leaves
            && m != net.minecraft.block.material.Material.cactus
            && m != net.minecraft.block.material.Material.gourd
            && m != net.minecraft.block.material.Material.glass;
    }

    // Per-column dig metrics (format 3). Burial scans stop DIG_PROBE_DEPTH below the surface: gravel 20 blocks
    // down is not a flint source in a sub-10-minute run, and scanning to bedrock on every column would cost
    // ~4x the getBlock calls for data nobody routes on. NO_REACH marks "nothing within reach" and doubles as
    // the byte cap, so every metric fits one hex pair. GRAVEL_REPORT_BURIAL gates emission of the full gravel
    // array — nearly every chunk has gravel somewhere, but only near-surface gravel is worth 512 chars.
    private static final int DIG_PROBE_DEPTH = 64;
    private static final int NO_REACH = 0xff;
    private static final int GRAVEL_REPORT_BURIAL = 16;

    // Surface detection: a terrain run counts as the surface unless it is BOTH thin (< SURF_THICK) and floating
    // over a big air gap (>= SURF_GAP) — that combination is a TiC slime island (or a stray floating ledge),
    // where the true surface is whatever lies below. Mountains over caves/ravines keep their summit: the summit
    // run is thick. Values in blocks; islands are ~5-10 thick and hover 30+ over terrain, caves under a summit
    // leave >16 of rock above them.
    private static final int SURF_THICK = 16;
    private static final int SURF_GAP = 16;

    private static int surfaceY(Chunk c, int lx, int lz) {
        int y = 255;
        while (y >= 0) {
            while (y >= 0 && !isTerrain(c.getBlock(lx, y, lz))) y--;
            if (y < 0) return 0;
            final int top = y;
            while (y >= 0 && isTerrain(c.getBlock(lx, y, lz))) y--;
            final int thickness = top - y;
            int below = y;
            while (below >= 0 && !isTerrain(c.getBlock(lx, below, lz))) below--;
            if (thickness >= SURF_THICK || y - below < SURF_GAP || below < 0) return top;
            y = below; // thin run floating over a big gap: slime island — keep scanning underneath
        }
        return 0;
    }

    /**
     * Package-visible so the stage-0 prefilter's Roguelike module emits chests through the SAME serializer as a
     * full-gen run. Anything else makes a prefilter-vs-corpus diff report formatting noise as a finding.
     */
    static String dumpInventory(net.minecraft.inventory.IInventory inv, TileEntity te) {
        return dumpInventory(
            inv,
            te.xCoord,
            te.yCoord,
            te.zCoord,
            te.getClass()
                .getSimpleName());
    }

    /**
     * Position and type given explicitly, for an inventory that is not a tile entity.
     *
     * <p>
     * The stage-0 village chest module rolls into a bare {@link SizedInventory} rather than a
     * {@code TileEntityChest}, because the slot count has to match the real container. It still emits through
     * this serializer so a prefilter-vs-corpus diff cannot report formatting as a finding.
     */
    static String dumpInventory(net.minecraft.inventory.IInventory inv, int x, int y, int z, String type) {
        final StringBuilder sb = new StringBuilder("{\"pos\": [").append(x)
            .append(", ")
            .append(y)
            .append(", ")
            .append(z)
            .append("], \"type\": \"")
            .append(type)
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

    /** Package-visible so sibling emitters (TwilightForestProbe) escape identically — see dumpInventory. */
    static String jsonEscape(String s) {
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
        dumpGtDims(dir);
    }

    /**
     * Runtime dimension id -> GT ore-mix dimension token, written beside gtmats.json so the vein predictor can
     * resolve a report's "dim" without a hardcoded table. A pack may move the Twilight Forest off id 7.
     *
     * <p>
     * Restricted to the four names GT accepts. {@code GTWorldgen.isGenerationAllowed} reads
     * {@code provider.getDimensionName()} and hard-rejects anything outside {Overworld, Nether, The End, Twilight
     * Forest}, so those are the only dimensions a GT ore vein can ever occupy. The End is spelled "The End" at
     * runtime but "TheEnd" in {@code OreMixBuilder}, so it is normalised here rather than at every reader.
     */
    private static void dumpGtDims(File dir) {
        try {
            final Map<String, String> byId = new TreeMap<>();
            for (int dim : DimensionManager.getStaticDimensionIDs()) {
                String name;
                try {
                    // Prefer the live provider; fall back to a throwaway one for dimensions dim0only skipped.
                    final WorldServer live = DimensionManager.getWorld(dim);
                    name = live != null ? live.provider.getDimensionName()
                        : DimensionManager.createProviderFor(dim)
                            .getDimensionName();
                } catch (Throwable t) {
                    continue; // a provider that cannot be built standalone is not one GT generates into
                }
                if ("The End".equals(name)) name = "TheEnd";
                if ("Overworld".equals(name) || "Nether".equals(name)
                    || "TheEnd".equals(name)
                    || "Twilight Forest".equals(name)) {
                    byId.put(Integer.toString(dim), name);
                }
            }
            final File outF = new File(dir == null ? new File(".") : dir, "gtdims.json");
            final StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (Map.Entry<String, String> e : byId.entrySet()) {
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
            LOG.info("[probe] wrote {} GT-eligible dimension names to {}", byId.size(), outF);
        } catch (Exception e) {
            LOG.warn("[probe] could not dump GT dimensions: {}", e.toString());
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
                    // Presence markers keep the digest stream SELF-DELIMITING. Without them a section carrying
                    // metadata but no MSB and one carrying MSB but no metadata feed byte-identical streams, so two
                    // genuinely different chunks can collide. Format 5 added these; digests differ from format 4.
                    final NibbleArray msb = ebs.getBlockMSBArray();
                    sec.update((byte) (msb != null ? 1 : 0));
                    all.update((byte) (msb != null ? 1 : 0));
                    if (msb != null) {
                        sec.update(msb.data);
                        all.update(msb.data);
                    }
                    final NibbleArray meta = ebs.getMetadataArray();
                    sec.update((byte) (meta != null ? 1 : 0));
                    all.update((byte) (meta != null ? 1 : 0));
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
            .append("\"");
        final String orphans = hashOrphanTileEntities(chunk);
        if (orphans != null) {
            out.append(", \"o\": \"")
                .append(orphans)
                .append("\"");
        }
        out.append("}");
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
        return digestTileEntities(chunk, false);
    }

    /**
     * Digest of the chunk's ORPHANED tile entities — those whose block was overwritten later in worldgen while the
     * TE lingered in the map — or null when the chunk has none.
     *
     * <p>
     * These used to be dropped silently by {@link #teMatchesBlock} on the grounds that they only add launch-timing
     * jitter. That was wrong in a way that matters: {@code Chunk.writeToNBT} persists the whole
     * {@code chunkTileEntityMap}, so orphans are real state the player receives, and {@code diff-region-tes.py} —
     * the persisted-world ground truth — does not filter them. The probe could therefore report IDENTICAL while
     * the saved worlds differed. Roguelike chest carving is exactly this case, which is why
     * {@code TreasureChestMixin.gtnhdet$isLive()} has to ask the block rather than the TE.
     *
     * <p>
     * They stay OUT of {@code "t"} so that digest keeps its established meaning, and land in {@code "o"} instead:
     * visible as a diff rather than silence, without conflating the two populations.
     */
    private static String hashOrphanTileEntities(Chunk chunk) throws Exception {
        return digestTileEntities(chunk, true);
    }

    private static String digestTileEntities(Chunk chunk, boolean orphansOnly) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        // Tile entities (chest loot etc.), canonicalized: sorted by position, NBT keys sorted recursively.
        final Map<String, TileEntity> tes = new TreeMap<>();
        for (Object o : chunk.chunkTileEntityMap.values()) {
            final TileEntity te = (TileEntity) o;
            if (teMatchesBlock(chunk, te) == orphansOnly) continue;
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
        if (orphansOnly && tes.isEmpty()) return null;
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
                                logIfWorldRetainer(cls + "." + f.getName(), v);
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

    /**
     * In-JVM path-to-root hunter: BFS the reference graph from every static field of every class in
     * every jar (mods + forge + minecraft_server) plus live Thread fields and ThreadLocals, looking for
     * identity matches with the targets. Prints the field-by-field retention chain. Bounded (5M nodes).
     */
    private static void findPathsToTargets(List<Object> targets) {
        final java.util.IdentityHashMap<Object, Object> visited = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<Object, Object[]> parent = new java.util.IdentityHashMap<>(); // obj ->
                                                                                                      // {parentObj,
                                                                                                      // edgeLabel}
        final java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        final java.util.IdentityHashMap<Object, Object> targetSet = new java.util.IdentityHashMap<>();
        for (Object t : targets) targetSet.put(t, t);

        final ClassLoader loader = WorldgenProbe.class.getClassLoader();
        final List<File> jars = new ArrayList<>();
        final File[] modJars = new File("mods").listFiles((d, n) -> n.endsWith(".jar"));
        if (modJars != null) jars.addAll(java.util.Arrays.asList(modJars));
        final File[] rootJars = new File(".").listFiles((d, n) -> n.endsWith(".jar"));
        if (rootJars != null) jars.addAll(java.util.Arrays.asList(rootJars));
        int rootFields = 0;
        for (File jar : jars) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
                final java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    final String name = en.nextElement()
                        .getName();
                    if (!name.endsWith(".class") || name.contains("$$")) continue;
                    try {
                        final Class<?> c = Class.forName(
                            name.substring(0, name.length() - 6)
                                .replace('/', '.'),
                            false,
                            loader);
                        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType()
                                .isPrimitive()) continue;
                            try {
                                f.setAccessible(true);
                                final Object v = f.get(null);
                                if (v == null || visited.containsKey(v)) continue;
                                visited.put(v, v);
                                parent.put(v, new Object[] { null, c.getName() + "." + f.getName() });
                                queue.add(v);
                                rootFields++;
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // thread roots (fields + ThreadLocals)
        for (Thread t : Thread.getAllStackTraces()
            .keySet()) {
            if (!visited.containsKey(t)) {
                visited.put(t, t);
                parent.put(t, new Object[] { null, "THREAD:" + t.getName() });
                queue.add(t);
                rootFields++;
            }
        }
        LOG.info("[probe][leak] BFS from {} static/thread roots…", rootFields);

        int expanded = 0;
        final int NODE_CAP = 5_000_000;
        while (!queue.isEmpty() && visited.size() < NODE_CAP) {
            final Object cur = queue.poll();
            expanded++;
            for (Object[] edge : probeReferencesOf(cur)) {
                final Object next = edge[0];
                if (next == null || visited.containsKey(next)) continue;
                visited.put(next, next);
                parent.put(next, new Object[] { cur, edge[1] });
                if (targetSet.containsKey(next)) {
                    final StringBuilder path = new StringBuilder(
                        "TARGET " + next.getClass()
                            .getSimpleName());
                    Object p = next;
                    int hops = 0;
                    while (p != null && hops++ < 30) {
                        final Object[] pe = parent.get(p);
                        if (pe == null) break;
                        path.insert(0, pe[1] + " -> ");
                        p = pe[0];
                    }
                    LOG.info("[probe][leak] PATH: {}", path);
                    targetSet.remove(next);
                    if (targetSet.isEmpty()) {
                        LOG.info("[probe][leak] all paths found ({} nodes visited)", visited.size());
                        return;
                    }
                } else {
                    queue.add(next);
                }
            }
        }
        LOG.info(
            "[probe][leak] BFS finished: {} nodes visited, {} targets NOT reached (JNI/stack-local roots?)",
            visited.size(),
            targetSet.size());
    }

    /** Outgoing references of an object: instance fields (all superclasses) + array elements. */
    private static List<Object[]> probeReferencesOf(Object o) {
        final List<Object[]> out = new ArrayList<>();
        try {
            final Class<?> c = o.getClass();
            if (c == String.class || c == Class.class || o instanceof java.lang.ref.Reference) return out;
            if (c.isArray()) {
                if (!c.getComponentType()
                    .isPrimitive()) {
                    final Object[] arr = (Object[]) o;
                    for (int i = 0; i < arr.length; i++) {
                        if (arr[i] != null) out.add(new Object[] { arr[i], "[" + i + "]" });
                    }
                }
                return out;
            }
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType()
                        .isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        final Object v = f.get(o);
                        if (v != null) out.add(new Object[] { v, k.getSimpleName() + "." + f.getName() });
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Find a registered event-bus listener instance by exact class name (any of the four busses). */
    private static Object findBusListenerInstance(String className) {
        try {
            final Object[] busses = { MinecraftForge.EVENT_BUS, MinecraftForge.ORE_GEN_BUS,
                MinecraftForge.TERRAIN_GEN_BUS, cpw.mods.fml.common.FMLCommonHandler.instance()
                    .bus() };
            for (Object bus : busses) {
                final java.lang.reflect.Field lf = bus.getClass()
                    .getDeclaredField("listeners");
                lf.setAccessible(true);
                for (Object owner : ((Map<?, ?>) lf.get(bus)).keySet()) {
                    if (owner != null && owner.getClass()
                        .getName()
                        .equals(className)) return owner;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Remove map entries keyed by DEAD worlds from a (static or instance) Map field. Returns removals. */
    private static int pruneDeadWorldKeys(Class<?> cls, Object instance, String fieldName)
        throws NoSuchFieldException, IllegalAccessException {
        final java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        final Object v = f.get(instance);
        if (!(v instanceof Map)) return 0;
        int removed = 0;
        final java.util.Iterator<?> it = ((Map<?, ?>) v).keySet()
            .iterator();
        while (it.hasNext()) {
            final Object k = it.next();
            if (k instanceof net.minecraft.world.World && probeWorldDead((net.minecraft.world.World) k)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** A world is dead if the DimensionManager no longer maps its dimension id to this exact instance. */
    private static boolean probeWorldDead(net.minecraft.world.World w) {
        try {
            return DimensionManager.getWorld(w.provider.dimensionId) != w;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Deep leak forensics (leakcheck mode): the static sweep only sees mod-jar statics — the usual
     * strong holders of dead worlds are EVENT-BUS LISTENER INSTANCES (registered per world, never
     * unregistered) and Forge's world-keyed chunk-manager maps. Walk both and name names.
     */
    private static void leakForensics() {
        // 1) event-bus listener owners whose instance fields reference a DEAD world
        try {
            final Object[] busses = { MinecraftForge.EVENT_BUS, MinecraftForge.ORE_GEN_BUS,
                MinecraftForge.TERRAIN_GEN_BUS, cpw.mods.fml.common.FMLCommonHandler.instance()
                    .bus() };
            for (Object bus : busses) {
                final java.lang.reflect.Field lf = bus.getClass()
                    .getDeclaredField("listeners");
                lf.setAccessible(true);
                final Map<?, ?> listeners = (Map<?, ?>) lf.get(bus);
                for (Object owner : listeners.keySet()) {
                    if (owner == null) continue;
                    for (java.lang.reflect.Field f : owner.getClass()
                        .getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (!net.minecraft.world.World.class.isAssignableFrom(f.getType())
                            && !Map.class.isAssignableFrom(f.getType())
                            && !java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        final Object v = f.get(owner);
                        if (v instanceof net.minecraft.world.World && probeWorldDead((net.minecraft.world.World) v)) {
                            LOG.info(
                                "[probe][leak] BUS-LISTENER {} field {} holds DEAD world dim {}",
                                owner.getClass()
                                    .getName(),
                                f.getName(),
                                ((net.minecraft.world.World) v).provider.dimensionId);
                        } else if (v instanceof Map) {
                            int dead = 0;
                            for (Object k : ((Map<?, ?>) v).keySet()) {
                                if (k instanceof net.minecraft.world.World
                                    && probeWorldDead((net.minecraft.world.World) k)) dead++;
                            }
                            if (dead > 0) LOG.info(
                                "[probe][leak] BUS-LISTENER {} field {} maps {} DEAD worlds",
                                owner.getClass()
                                    .getName(),
                                f.getName(),
                                dead);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[probe][leak] bus walk failed: {}", t.toString());
        }
        // 2) ForgeChunkManager world-keyed statics
        try {
            final Class<?> fcm = Class.forName("net.minecraftforge.common.ForgeChunkManager");
            for (String fn : new String[] { "tickets", "forcedChunks", "dormantChunkCache" }) {
                try {
                    final java.lang.reflect.Field f = fcm.getDeclaredField(fn);
                    f.setAccessible(true);
                    final Object v = f.get(null);
                    if (v instanceof Map) {
                        int dead = 0;
                        for (Object k : ((Map<?, ?>) v).keySet()) {
                            if (k instanceof net.minecraft.world.World && probeWorldDead((net.minecraft.world.World) k))
                                dead++;
                        }
                        LOG.info(
                            "[probe][leak] ForgeChunkManager.{}: size {} ({} dead-world keys)",
                            fn,
                            ((Map<?, ?>) v).size(),
                            dead);
                    }
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable t) {
            LOG.warn("[probe][leak] ForgeChunkManager audit failed: {}", t.toString());
        }
    }

    /**
     * Sweep helper: flag static fields that RETAIN WORLD OBJECTS — a direct World reference, or a
     * collection/map whose sampled keys/values are World/Chunk/TileEntity/Entity instances. One
     * retained WorldServer keeps its whole chunk map alive: the ~0.5G/slot warm-batch leak signature.
     */
    private static void logIfWorldRetainer(String label, Object v) {
        try {
            if (v instanceof net.minecraft.world.World) {
                LOG.info(
                    "[probe][sweep] WORLD-RETAINER {} = direct {}",
                    label,
                    v.getClass()
                        .getName());
                return;
            }
            java.util.List<Iterable<?>> sides = new ArrayList<>();
            int size = -1;
            if (v instanceof Map) {
                sides.add(((Map<?, ?>) v).keySet());
                sides.add(((Map<?, ?>) v).values());
                size = ((Map<?, ?>) v).size();
            } else if (v instanceof java.util.Collection) {
                sides.add((java.util.Collection<?>) v);
                size = ((java.util.Collection<?>) v).size();
            } else return;
            if (size == 0) return;
            for (Iterable<?> side : sides) {
                int n = 0;
                for (Object o : side) {
                    if (o instanceof net.minecraft.world.World || o instanceof net.minecraft.world.chunk.Chunk
                        || o instanceof net.minecraft.tileentity.TileEntity
                        || o instanceof net.minecraft.entity.Entity) {
                        LOG.info(
                            "[probe][sweep] WORLD-RETAINER {} (size {}) holds {}",
                            label,
                            size,
                            o.getClass()
                                .getName());
                        return;
                    }
                    if (++n >= 3) break;
                }
            }
        } catch (Throwable ignored) {}
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
            final Map<?, ?> structureMap = villageStructureMap(world);
            if (structureMap == null) return "\"no MapGenVillage structureMap found\"";
            final java.util.List<String> villages = new ArrayList<>();
            for (Object start : structureMap.values()) {
                final java.util.List<?> components = componentsOf(start);
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

    /**
     * The village generator's structureMap of StructureStarts, reached by TYPE rather than by name so it survives
     * SRG/MCP renames, RWG's modded chunk provider, and VillageNames' MapGenVillageVN (which subclasses
     * MapGenVillage and is installed over InitMapGenEvent). Returns null when either lookup fails.
     */
    private static Map<?, ?> villageStructureMap(WorldServer world) throws Exception {
        final Object provider = world.theChunkProviderServer.currentChunkProvider;
        Object villageGen = null;
        for (java.lang.reflect.Field f : provider.getClass()
            .getDeclaredFields()) {
            if (net.minecraft.world.gen.structure.MapGenVillage.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                villageGen = f.get(provider);
                break;
            }
        }
        if (villageGen == null) return null;
        // Keep walking superclasses when the first Map-typed field holds null — the pre-refactor loop did, and
        // dropping that would silently return no villages on any generator that declares an unused Map.
        for (Class<?> c = villageGen.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    final Object v = f.get(villageGen);
                    if (v != null) return (Map<?, ?>) v;
                }
            }
        }
        return null;
    }

    /** The component list of one StructureStart, found by type like {@link #villageStructureMap}. */
    private static java.util.List<?> componentsOf(Object start) throws Exception {
        for (Class<?> sc = start.getClass(); sc != null; sc = sc.getSuperclass()) {
            for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    final Object v = f.get(start);
                    if (v != null) return (java.util.List<?>) v;
                }
            }
        }
        return null;
    }

    static String bboxOf(Object component) {
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

    /** Expected villager count per village piece class, from the spawnVillagers call site in each. -1 = unknown. */
    private static int expectedVillagers(String simpleName) {
        switch (simpleName) {
            // vanilla StructureVillagePieces, count = last arg of spawnVillagers
            case "Church": // :387
            case "House1": // :868
            case "House2": // :1001, the blacksmith
            case "House4Garden": // :1308
            case "WoodHut": // :2013
                return 1;
            case "Hall": // :736, butcher + farmer
            case "House3": // :1174, two farmers
                return 2;
            // mod pieces that call the same base method
            case "ComponentVillageBeeHouse": // Forestry, spawnVillagers(..., 7, 1, 1, 2)
            case "ComponentWorkshop": // Railcraft, spawnVillagers(..., 0, 0, 0, 2)
                return 2;
            case "ComponentToolWorkshop": // TiC, spawnVillagers(..., 3, 1, 3, 1)
                return 1;
            default:
                return -1;
        }
    }

    /**
     * The persisted villager high-water mark ("VCount") of one village component, read by invoking the component's
     * own NBT writer. The method has no MCP name, so func_143012_a is identical in the deobfuscated and SRG
     * environments; matching on the numeric part keeps it working if either mapping ever gains one. Returns -1
     * when the component is not a StructureVillagePieces.Village (roads, wells, VillageNames pieces).
     */
    private static int persistedVCount(Object component) {
        try {
            for (Class<?> sc = component.getClass(); sc != null; sc = sc.getSuperclass()) {
                for (java.lang.reflect.Method m : sc.getDeclaredMethods()) {
                    if (!m.getName()
                        .contains("143012")) continue;
                    final Class<?>[] p = m.getParameterTypes();
                    if (p.length != 1 || !p[0].isAssignableFrom(NBTTagCompound.class)) continue;
                    m.setAccessible(true);
                    final NBTTagCompound tag = new NBTTagCompound();
                    m.invoke(component, tag);
                    return tag.hasKey("VCount") ? tag.getInteger("VCount") : -1;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Every entity in every LOADED chunk — deliberately not just the walked window.
     * <p>
     * The village structureMap that {@link #dumpVillagePieces} reads is global: it holds every start the generator
     * has created, including those from the spawn preload, which for a typical seed sits hundreds of blocks from
     * the probe's default centre of chunk 0,0. Scoping entities to the walk window while scoring pieces from that
     * global map made every out-of-window village report {@code vcount=1 actual=0} — a scope mismatch that reads
     * exactly like a total villager loss. Collecting from the loaded set keeps the two scopes comparable.
     * <p>
     * Loaded-chunk scope is also what makes counts comparable across walk orders: a village straddling the walk
     * edge would otherwise contribute a different number of entities per arm for reasons unrelated to the defect
     * being measured.
     */
    private static java.util.List<net.minecraft.entity.Entity> collectEntities(WorldServer world) {
        final java.util.List<net.minecraft.entity.Entity> out = new ArrayList<>();
        for (Object o : world.loadedEntityList) {
            if (o instanceof net.minecraft.entity.Entity) out.add((net.minecraft.entity.Entity) o);
        }
        return out;
    }

    /**
     * Canonical NBT of one entity, with the entity UUID removed. UUIDMost/UUIDLeast come from UUID.randomUUID()
     * in the Entity constructor, carry no gameplay meaning, and would otherwise make every entity differ in every
     * pair. Nothing else is dropped: at zero ticks Motion, Age, Rotation, Fire, Air and HealF are all constants,
     * so keeping them costs nothing and catches anything that moves them.
     */
    private static String canonicalEntityNbt(net.minecraft.entity.Entity e) {
        try {
            final NBTTagCompound tag = new NBTTagCompound();
            if (!e.writeToNBTOptional(tag)) return "unsaved:" + e.getClass()
                .getSimpleName();
            tag.removeTag("UUIDMost");
            tag.removeTag("UUIDLeast");
            return canonicalNbt(tag);
        } catch (Exception ex) {
            return "err:" + ex;
        }
    }

    private static String shortDigest(String s) throws Exception {
        return hex(
            MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
    }

    /**
     * Villager census: one sorted line per villager-class entity. Everything reported here is decided at
     * generation time, so two arms of the same seed must agree exactly. Profession comes from the piece class
     * (a compile-time constant in StructureVillagePieces.getVillagerType), position from getXWithOffset, and
     * vanilla rotation is the literal 0,0 — none of it is a random draw.
     */
    private static String dumpVillagers(java.util.List<net.minecraft.entity.Entity> ents) {
        final java.util.List<String> lines = new ArrayList<>();
        for (net.minecraft.entity.Entity e : ents) {
            final String cls = e.getClass()
                .getSimpleName();
            final boolean villagerish = e instanceof net.minecraft.entity.passive.EntityVillager
                || cls.contains("Villager")
                || cls.contains("VillageGuard");
            if (!villagerish) continue;
            String prof = "-";
            if (e instanceof net.minecraft.entity.passive.EntityVillager) {
                prof = Integer.toString(((net.minecraft.entity.passive.EntityVillager) e).getProfession());
            }
            final NBTTagCompound tag = new NBTTagCompound();
            boolean offers = false;
            int age = 0;
            try {
                if (e.writeToNBTOptional(tag)) {
                    offers = tag.hasKey("Offers");
                    age = tag.getInteger("Age");
                }
            } catch (Exception ignored) {}
            String dig;
            try {
                dig = shortDigest(canonicalEntityNbt(e));
            } catch (Exception ex) {
                dig = "err";
            }
            lines.add(
                String.format(
                    java.util.Locale.ROOT,
                    "\"%s@%.4f,%.4f,%.4f prof=%s age=%d rot=%.1f,%.1f offers=%s nbt=%s\"",
                    cls,
                    e.posX,
                    e.posY,
                    e.posZ,
                    prof,
                    age,
                    e.rotationYaw,
                    e.rotationPitch,
                    offers ? "y" : "n",
                    dig));
        }
        java.util.Collections.sort(lines);
        if (lines.isEmpty()) return "[]";
        return "[\n    " + String.join(",\n    ", lines) + "\n  ]";
    }

    /**
     * Per-class entity census: count and a content digest over the sorted canonical NBT of that class. Kept
     * separate from "villagers" so the known worldgen animal-species defect stays quarantined and separately
     * reportable instead of drowning the villager signal.
     */
    private static String dumpEntityCensus(java.util.List<net.minecraft.entity.Entity> ents) {
        final Map<String, java.util.List<String>> byClass = new TreeMap<>();
        for (net.minecraft.entity.Entity e : ents) {
            byClass.computeIfAbsent(
                e.getClass()
                    .getSimpleName(),
                k -> new ArrayList<>())
                .add(canonicalEntityNbt(e));
        }
        final java.util.List<String> parts = new ArrayList<>();
        for (Map.Entry<String, java.util.List<String>> en : byClass.entrySet()) {
            final java.util.List<String> v = en.getValue();
            java.util.Collections.sort(v);
            String dig;
            try {
                dig = shortDigest(String.join(" ", v));
            } catch (Exception ex) {
                dig = "err";
            }
            parts.add("\"" + en.getKey() + "\": \"" + v.size() + "/" + dig + "\"");
        }
        if (parts.isEmpty()) return "{}";
        return "{\n    " + String.join(",\n    ", parts) + "\n  }";
    }

    /**
     * Per village component: the persisted villager high-water mark, the villagers actually present inside its
     * bounding box, and the count its spawnVillagers call site asks for. The three-way split is the whole point —
     * it separates the failure modes that look identical in a raw entity diff:
     * <ul>
     * <li>vcount &lt; expect — the spawnVillagers break-ordering loss. The loop breaks rather than continues when
     * a villager falls outside the current populate window, and bumps the persisted counter before spawning, so a
     * multi-villager piece straddling a window boundary at x or z congruent to 8 mod 16 permanently loses a
     * villager when the far window populates first. Route-dependent, no RNG.</li>
     * <li>actual &lt; vcount — the villager was counted but never entered the world: either the chunkExists guard
     * in World.spawnEntityInWorld or an EntityJoinWorldEvent cancel by another mod.</li>
     * <li>both equal but positions differ between arms — something new, and worth stopping for.</li>
     * </ul>
     */
    private static String dumpVillagePieces(WorldServer world, java.util.List<net.minecraft.entity.Entity> ents) {
        try {
            final Map<?, ?> structureMap = villageStructureMap(world);
            if (structureMap == null) return "\"no MapGenVillage structureMap found\"";
            final java.util.List<String> lines = new ArrayList<>();
            for (Object start : structureMap.values()) {
                final java.util.List<?> components = componentsOf(start);
                if (components == null) continue;
                for (Object comp : components) {
                    final String cls = comp.getClass()
                        .getSimpleName();
                    final int expect = expectedVillagers(cls);
                    final int vcount = persistedVCount(comp);
                    if (expect < 0 && vcount < 0) continue; // roads, wells, and anything that spawns nobody
                    net.minecraft.world.gen.structure.StructureBoundingBox bb = null;
                    for (Class<?> sc = comp.getClass(); sc != null && bb == null; sc = sc.getSuperclass()) {
                        for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                            if (net.minecraft.world.gen.structure.StructureBoundingBox.class
                                .isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                bb = (net.minecraft.world.gen.structure.StructureBoundingBox) f.get(comp);
                                break;
                            }
                        }
                    }
                    int actual = 0;
                    if (bb != null) {
                        for (net.minecraft.entity.Entity e : ents) {
                            if (!(e instanceof net.minecraft.entity.passive.EntityVillager)) continue;
                            if (bb.isVecInside(
                                net.minecraft.util.MathHelper.floor_double(e.posX),
                                net.minecraft.util.MathHelper.floor_double(e.posY),
                                net.minecraft.util.MathHelper.floor_double(e.posZ))) actual++;
                        }
                    }
                    lines.add(
                        "\"" + cls
                            + "@"
                            + bboxOf(comp)
                            + " vcount="
                            + vcount
                            + " actual="
                            + actual
                            + " expect="
                            + expect
                            + "\"");
                }
            }
            java.util.Collections.sort(lines);
            if (lines.isEmpty()) return "[]";
            return "[\n    " + String.join(",\n    ", lines) + "\n  ]";
        } catch (Exception e) {
            return "\"error: " + e + "\"";
        }
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
