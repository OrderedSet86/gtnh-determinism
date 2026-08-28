# WorldgenProbe

Source of the `worldgenprobe` jar. Minecraft 1.7.10, for GT: New Horizons 2.7.4+.

This is the measurement mod, not a gameplay mod. It generates a region on a dedicated server and dumps
region-file blocks and full tile-entity NBT, so that two runs of the same seed can be compared byte for byte.
It is what produces the launch-test and route-test evidence quoted in the repo root README.

The jar is inert unless driven. Every entry point is gated behind a `-Dprobe.*` system property, so installing
it and starting the game normally does nothing. It is attached to releases for reproducibility only; a player
does not need it.

## What lives here

* `com.gtnhspeedrun.worldgenprobe.WorldgenProbe` — the `@Mod` entry point and the whole driver: seed loop,
  generation order, world dumping, comparison, and the CRIU and daemon modes the seed searches run under.
* `com.gtnhspeedrun.worldgenprobe.Prefilter` — cheap seed rejection, used to skip seeds before paying for a
  full generation.
* `com.gtnhspeedrun.worldgenprobe.mixins` — two RWG mixins that expose terrain generation to the probe.

Read the source for the switch list. There are around twenty `-Dprobe.*` properties and they change with the
experiment, so enumerating them here would go stale; `WorldgenProbe.java` and `Prefilter.java` are the
reference.

## Building and running

Build through the repo script, which builds clean, verifies that every source class reached the jar, and
prints the md5:

```bash
../scripts/build-jar.sh probe
```

`../scripts/build-probe.sh` is a back-compat wrapper for the same target. The harness that drives the built
jar is documented under "Headless verification harness" in the repo root README.
