package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * -Dprobe.gencensus=&lt;out.json&gt;: dump who is hooked to a chunk's populate RNG, in the order they will
 * actually be called, and shut down. Generates no chunks.
 *
 * <p>
 * A source scan over the pack's mod repos gives a superset — it cannot see closed-source jars, it cannot see
 * config-gated registrations that did not happen, and above all it cannot see ORDER. This dumps the two
 * runtime structures that decide order, so the source list can be reconciled against what the game will do.
 *
 * <p>
 * <b>The FML generator list.</b> {@code GameRegistry.generateWorld} reseeds its own Random before every
 * {@code IWorldGenerator}, so those generators cannot corrupt each other's draw counts — only their write
 * order matters, and first-writer-wins makes that order load-bearing. The order comes from
 * {@code computeSortedGeneratorList}: an {@code ArrayList} copy of a {@code HashSet}, stable-sorted by
 * weight. Stable sort means equal weights keep the HashSet's iteration order, which is
 * {@code System.identityHashCode} order. So every tie block is ordered by an identity hash. Per
 * {@code docs/HANDOFF.md} that order is NOT per-launch random — HotSpot's default {@code hashCode=5} is a
 * fixed-seed xorshift, identical across launches of one JVM — but it does differ by JVM VERSION. Running
 * this under Java 17 and Java 21 and diffing the two dumps settles whether the tie blocks reorder, without
 * generating a single chunk.
 *
 * <p>
 * The list is normally null until the first chunk populates, so this calls {@code computeSortedGeneratorList}
 * directly. That is the same private method FML would call and it is idempotent — it recomputes from the
 * registry rather than appending — so forcing it early changes nothing about the run that follows. It also
 * means the dump reflects any mixin that rewrites the list: GregTech's {@code GameRegistryMixin} pulls its
 * own {@code GTWorldgenerator} out and appends it, so GT appears last here rather than wherever its identity
 * hash would have put it.
 *
 * <p>
 * <b>The three terraingen busses.</b> Handlers on these share the chunk's populate {@code Random}, so unlike
 * the generators above they CAN skew each other's draws. The busses are not interchangeable and enumerating
 * two of them is a silent 1/3 miss: {@code EVENT_BUS} carries {@code PopulateChunkEvent.Pre/Post} and
 * {@code DecorateBiomeEvent.Pre/Post}, {@code TERRAIN_GEN_BUS} carries {@code PopulateChunkEvent.Populate}
 * and {@code DecorateBiomeEvent.Decorate}, {@code ORE_GEN_BUS} carries {@code OreGenEvent.*}. Dispatch order
 * is read from each event class's own {@code ListenerList}, which is what {@code EventBus.post} reads, so
 * handlers registered against a BASE event class appear here in their real position via the list's parent
 * chain — a source grep for {@code PopulateChunkEvent.Pre} cannot see those at all.
 *
 * <p>
 * {@code EventPriority} implements {@code IEventListener} and its constants sit in the dispatch array as
 * phase markers. They are emitted as {@code phase} entries rather than skipped, because a handler's position
 * only means something relative to the phase it sits in.
 */
public final class GeneratorCensus {

    private static final Logger LOG = LogManager.getLogger("worldgenprobe");

    /** Every terraingen event type, with the bus that carries it. Order here is the order of the dump. */
    private static final String[][] EVENTS = {
        { "net.minecraftforge.event.terraingen.PopulateChunkEvent", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.PopulateChunkEvent$Pre", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.PopulateChunkEvent$Post", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.PopulateChunkEvent$Populate", "TERRAIN_GEN_BUS" },
        { "net.minecraftforge.event.terraingen.DecorateBiomeEvent", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.DecorateBiomeEvent$Pre", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.DecorateBiomeEvent$Post", "EVENT_BUS" },
        { "net.minecraftforge.event.terraingen.DecorateBiomeEvent$Decorate", "TERRAIN_GEN_BUS" },
        { "net.minecraftforge.event.terraingen.OreGenEvent", "ORE_GEN_BUS" },
        { "net.minecraftforge.event.terraingen.OreGenEvent$Pre", "ORE_GEN_BUS" },
        { "net.minecraftforge.event.terraingen.OreGenEvent$Post", "ORE_GEN_BUS" },
        { "net.minecraftforge.event.terraingen.OreGenEvent$GenerateMinable", "ORE_GEN_BUS" }, };

    private GeneratorCensus() {}

    public static String outPath() {
        return System.getProperty("probe.gencensus");
    }

    public static void dump(File out) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"jvm\": ")
            .append(json(System.getProperty("java.vm.version") + " / " + System.getProperty("java.version")))
            .append(",\n  \"hashCodeMode\": ")
            .append(json(hashCodeMode()))
            .append(",\n  \"generators\": [\n");
        appendGenerators(sb);
        sb.append("\n  ],\n  \"busIds\": {");
        appendBusIds(sb);
        sb.append("},\n  \"listeners\": {\n");
        appendListeners(sb);
        sb.append("\n  }\n}\n");
        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        LOG.info("[probe][gencensus] wrote {}", out.getAbsolutePath());
    }

    // ---------------------------------------------------------------- generators

    private static void appendGenerators(StringBuilder sb) {
        final List<Object> sorted = new ArrayList<>();
        final Map<Object, Integer> weights = new IdentityHashMap<>();
        try {
            final Class<?> gr = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
            final Method compute = gr.getDeclaredMethod("computeSortedGeneratorList");
            compute.setAccessible(true);
            compute.invoke(null);
            final Field listField = gr.getDeclaredField("sortedGeneratorList");
            listField.setAccessible(true);
            final Object list = listField.get(null);
            if (list instanceof Iterable) for (Object g : (Iterable<?>) list) sorted.add(g);
            final Field idx = gr.getDeclaredField("worldGeneratorIndex");
            idx.setAccessible(true);
            final Object m = idx.get(null);
            if (m instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) m).entrySet()) {
                if (e.getValue() instanceof Integer) weights.put(e.getKey(), (Integer) e.getValue());
            }
        } catch (Exception e) {
            LOG.error("[probe][gencensus] could not read GameRegistry's generator list", e);
        }
        for (int i = 0; i < sorted.size(); i++) {
            final Object g = sorted.get(i);
            final Integer w = weights.get(g);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"i\": ")
                .append(i)
                .append(", \"weight\": ")
                .append(w == null ? "null" : w.toString())
                .append(", \"class\": ")
                .append(
                    json(
                        g.getClass()
                            .getName()))
                // The identity hash is the tie-break input. Emitting it turns "the order changed" into
                // "the order changed BECAUSE these hashes changed", which is the difference between a
                // suspicion and an explanation.
                .append(", \"identityHash\": ")
                .append(System.identityHashCode(g))
                .append("}");
        }
    }

    // ---------------------------------------------------------------- listeners

    private static void appendBusIds(StringBuilder sb) {
        boolean first = true;
        for (String name : new String[] { "EVENT_BUS", "TERRAIN_GEN_BUS", "ORE_GEN_BUS" }) {
            final int id = busId(name);
            if (!first) sb.append(", ");
            first = false;
            sb.append(json(name))
                .append(": ")
                .append(id);
        }
    }

    private static void appendListeners(StringBuilder sb) {
        boolean firstEvent = true;
        for (String[] ev : EVENTS) {
            final int id = busId(ev[1]);
            final List<String[]> entries = listenersFor(ev[0], id);
            if (entries == null) continue;
            if (!firstEvent) sb.append(",\n");
            firstEvent = false;
            sb.append("    ")
                .append(json(ev[0].substring(ev[0].lastIndexOf('.') + 1)))
                .append(": {\"bus\": ")
                .append(json(ev[1]))
                .append(", \"dispatch\": [");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("{\"i\": ")
                    .append(i)
                    .append(", \"kind\": ")
                    .append(json(entries.get(i)[0]))
                    .append(", \"who\": ")
                    .append(json(entries.get(i)[1]))
                    .append("}");
            }
            sb.append("]}");
        }
    }

    /** The exact array {@code EventBus.post} would walk, or null if the class or its list is missing. */
    private static List<String[]> listenersFor(String eventClass, int busId) {
        if (busId < 0) return null;
        try {
            final Class<?> c = Class.forName(eventClass);
            Object list = null;
            for (Field f : c.getDeclaredFields()) {
                if ("cpw.mods.fml.common.eventhandler.ListenerList".equals(
                    f.getType()
                        .getName())) {
                    f.setAccessible(true);
                    list = f.get(null);
                    break;
                }
            }
            // An event class nobody has ever constructed has no ASM-generated LISTENER_LIST field yet.
            // Constructing one to force it would need real constructor arguments, so report the absence
            // instead of faking it — a missing entry here is a real "nothing can be dispatched".
            if (list == null) return null;
            final Method get = list.getClass()
                .getMethod("getListeners", int.class);
            final Object[] arr = (Object[]) get.invoke(list, busId);
            final List<String[]> out = new ArrayList<>();
            for (Object l : arr) {
                final boolean phase = l instanceof Enum;
                out.add(new String[] { phase ? "phase" : "listener", String.valueOf(l) });
            }
            return out;
        } catch (Throwable t) {
            LOG.warn("[probe][gencensus] no listener list for {}: {}", eventClass, t.toString());
            return null;
        }
    }

    private static int busId(String name) {
        try {
            final Field bf = MinecraftForge.class.getDeclaredField(name);
            bf.setAccessible(true);
            final Object bus = bf.get(null);
            final Field id = bus.getClass()
                .getDeclaredField("busID");
            id.setAccessible(true);
            return (Integer) id.get(bus);
        } catch (Exception e) {
            LOG.warn("[probe][gencensus] no bus {}: {}", name, e.toString());
            return -1;
        }
    }

    // ---------------------------------------------------------------- misc

    /**
     * Which identity-hash generator this JVM is using. Only reported, never assumed: the whole point of the
     * two-JVM comparison is that the DEFAULT differs between versions, and a dump that does not say which
     * mode produced it cannot be compared to another one.
     */
    private static String hashCodeMode() {
        // Read back the launch flag rather than query HotSpotDiagnosticMXBean. Two attempts at the
        // MXBean (sun.management internals, then the platform interface) both threw under the Forge
        // class loader and reported "unknown" on a JVM that answers fine from the command line, and a
        // provenance field that lies by omission is worse than one that reports only what it saw.
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments()) {
                if (a.startsWith("-XX:hashCode=")) return a.substring("-XX:hashCode=".length());
            }
            return "default";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String json(String s) {
        if (s == null) return "null";
        final StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\')
                .append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"')
            .toString();
    }
}
