package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileWriter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Client-side driver for {@code -Dprobe.escbench=<path>} — the one measurement the headless server harness
 * cannot make.
 *
 * The open question in docs/world-rand-siting-leak.md is whether opening the ESC menu re-pins {@code
 * world.rand}. GoG players report that it does, and that it rolls them back to the first bag of the world.
 * The pin itself is measured and reproduced; the ESC trigger is not, because ESC is a client action on the
 * integrated server and the probe has no client.
 *
 * This runs the client under {@code gamescope --backend headless} (see obj2littletiles/mod/fps-bench.sh for
 * the same pattern) and scripts a pause cycle while logging {@link WorldgenProbe.TracingRandom} counters on
 * every server tick. A pause shows up as a gap in the tick column, because {@code IntegratedServer.tick()}
 * skips {@code super.tick()} while {@code isGamePaused}. The question is whether {@code setSeedCalls} jumps
 * across that gap by more than the per-tick baseline.
 *
 * Deliberately creates its own world rather than opening an existing save, so it never touches a real one.
 *
 * Never referenced from common code: it imports client-only classes and must not load on a dedicated server.
 */
public final class EscBench {

    private static final String WORLD = "escbench";
    private static final int SETTLE_TICKS = 600; // let worldgen and the population queue quiesce first
    private static final int PHASE_TICKS = 100; // ticks held in each of paused / unpaused
    private static final int CYCLES = 6;

    private final File out;
    private final StringBuilder log = new StringBuilder();
    private WorldgenProbe.TracingRandom tracer;
    private int clientTicks;
    private int serverTicks;
    private int installClientTick;
    private int cycle;
    private boolean worldRequested;
    private boolean installed;
    private boolean finished;
    private long lastSeeds = -1;

    private EscBench(File out) {
        this.out = out;
        // clientTick is the load-bearing column. serverTick is our own counter and is therefore always
        // contiguous, so it cannot show the pause. Client ticks keep running while the integrated server is
        // stopped, so a pause appears as a JUMP in clientTick between two adjacent rows.
        log.append(
            "# serverTick,clientTick,cycle,paused,setSeedCalls,deltaSetSeed,lastSetSeedArg,drawsSinceSeed,totalNext\n");
    }

    /** Registered from {@link WorldgenProbe} only when the side is CLIENT and the property is set. */
    public static void install(String path) {
        final EscBench b = new EscBench(new File(path));
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(b);
        WorldgenProbe.LOG.info("[escbench] armed, writing {}", path);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || finished) return;
        final Minecraft mc = Minecraft.getMinecraft();
        clientTicks++;

        // Boot: wait a fixed number of client ticks, then make a fresh world of our own.
        //
        // Deliberately does NOT gate on `currentScreen instanceof GuiMainMenu`. GTNH ships a custom main
        // menu, so that check never matched and the driver sat at the menu indefinitely. Waiting on tick
        // count and "no world loaded yet" is independent of whatever screen the pack puts up.
        if (!worldRequested) {
            if (clientTicks == 100) {
                WorldgenProbe.LOG.info(
                    "[escbench] waiting at menu; screen={}",
                    mc.currentScreen == null ? "null"
                        : mc.currentScreen.getClass()
                            .getName());
            }
            if (clientTicks > 300 && mc.theWorld == null) {
                WorldType wt = WorldType.parseWorldType("rwg");
                if (wt == null) wt = WorldType.DEFAULT;
                final WorldSettings ws = new WorldSettings(
                    4242L,
                    WorldSettings.GameType.CREATIVE,
                    true, // mapFeatures — GotG generates with structures allowed
                    false,
                    wt);
                ws.enableCommands();
                WorldgenProbe.LOG.info("[escbench] creating world {} type={}", WORLD, wt.getWorldTypeName());
                mc.launchIntegratedServer(WORLD, WORLD, ws);
                worldRequested = true;
            }
            return;
        }

        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || mc.theWorld == null) return;

        if (!installed) {
            final net.minecraft.world.WorldServer ws = server.worldServers[0];
            tracer = new WorldgenProbe.TracingRandom(ws.rand.nextLong());
            ws.rand = tracer;
            installed = true;
            serverTicks = 0;
            installClientTick = clientTicks;
            WorldgenProbe.LOG.info("[escbench] tracer installed on world.rand");
            return;
        }

        // Settle, then alternate: PHASE_TICKS unpaused, PHASE_TICKS with the ESC menu open.
        //
        // Scheduled on CLIENT ticks, not server ticks. Pausing stops the integrated server, so a schedule
        // driven by serverTicks freezes the moment it opens the menu and never reaches the unpause branch —
        // a deadlock of its own making. Client ticks keep running while paused, which is the whole reason
        // the pause is observable as a gap in the server tick column.
        final int elapsed = clientTicks - installClientTick;
        if (elapsed < SETTLE_TICKS) return;
        final int since = elapsed - SETTLE_TICKS;
        final int phase = (since / PHASE_TICKS);
        cycle = phase / 2;
        if (cycle >= CYCLES) {
            finish(mc);
            return;
        }
        final boolean wantPaused = (phase % 2) == 1;
        final boolean isPaused = mc.currentScreen instanceof GuiIngameMenu;
        if (wantPaused && !isPaused) {
            mc.displayGuiScreen(new GuiIngameMenu());
        } else if (!wantPaused && isPaused) {
            mc.displayGuiScreen(null);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || tracer == null || finished) return;
        serverTicks++;
        final long seeds = tracer.seeds;
        final long delta = lastSeeds < 0 ? 0 : seeds - lastSeeds;
        lastSeeds = seeds;
        final boolean paused = Minecraft.getMinecraft().currentScreen instanceof GuiIngameMenu;
        log.append(serverTicks)
            .append(',')
            .append(clientTicks)
            .append(',')
            .append(cycle)
            .append(',')
            .append(paused)
            .append(',')
            .append(seeds)
            .append(',')
            .append(delta)
            .append(',')
            .append(tracer.lastSeedArg)
            .append(',')
            .append(tracer.drawsSinceSeed)
            .append(',')
            .append(tracer.draws)
            .append('\n');
    }

    private void finish(Minecraft mc) {
        finished = true;
        try (FileWriter w = new FileWriter(out)) {
            w.write(log.toString());
        } catch (Exception ex) {
            WorldgenProbe.LOG.error("[escbench] write failed", ex);
        }
        WorldgenProbe.LOG.info("[escbench] done after {} server ticks -> {}", serverTicks, out);
        mc.shutdown();
    }
}
