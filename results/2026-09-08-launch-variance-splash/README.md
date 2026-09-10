# Launch-variance splash image

`docs/img/launch-variance.png`. Four cold boots of one seed on GTNH 2.9.0-beta-3 — two stock, two
with the determinism jar — cropped to five fixed windows and stacked.

The README had no images, so the central claim (*same seed should mean same world*) was only
readable as tables of block counts. This is that claim as a picture.

## Result

Inner window, chunks −44..44 (7,921 chunks), `scripts/diff-region-blocks.py`:

| | blocks differing between the two launches | chunks |
|---|---:|---:|
| stock | **2,680,337** | 5,067 |
| with `gtnhdeterminism` | **311** | 26 |

311 is not zero and is not quoted as zero anywhere. See "the residual" below.

Supporting measures, all over the same four worlds:

| measure | stock pair | fixed pair |
|---|---:|---:|
| village structure blocks placed, whole walk | 5,718 vs 4,512 | 5,291 vs 5,293 |
| y=21 slice, whole slice, blocks differing | 45,739 | 430 |
| the five image crops (11,000 surface columns) | 4,831 (43.9%) | 0 |

Per column, stock launch 1 vs launch 2: village 64%, village 65%, slime island 38%, witchery 33%,
deepslate 20%.

## Why launch variance and not route variance

Route variance (`rows` vs `spiral`) is the bigger headline number, but the fixed arm still carries
67,519 route-differing blocks, and a large part of that — decoration 38,856, dirt/gravel 7,574,
sand settling 6,087, plus the unfixed oil deposits — is surface-visible. A route-based "after" pair
would visibly differ, and the image would be claiming something the jar does not deliver. The
launch axis is the only one where the fixed pair can honestly be shown as identical.

## Method

`setup-servers.sh` clones two private server dirs from `~/.cache/gtnh-determinism/beta3` using the
hardlink recipe from `scripts/probe-farm.sh`. `splash-stock` is cloned *from* `splash-fixed`, so
"both arms have identical config" is a property of `cp -a` rather than of a script running the same
steps twice; the only difference between the dirs is that `gtnhdeterminism-*.jar` is removed from
one. `run.sh` then does four cold `run-probe.sh` runs, seed `-1636594104014467454`, order `rows`,
radius 48, `PROBE_CX=0 PROBE_CZ=0`, copying `World` out after each.

Warm mode is forbidden here, not merely slower: identity-hash iteration order — F1's mechanism — is
constant within a JVM, so a warm batch would erase the exact signal rows 1 and 2 exist to show.

Rendering is `scripts/splash-strips.py` on top of `gtnh-seedlib/tools/worldrender.py`. Crops are
sliced on absolute world coordinates against each world's own `x0`/`z0` and assert `have.all()`.
`build_world_bundle.render_blocks` is deliberately not used: it ends in `_crop_to_content(alpha)`,
a bounding box of non-transparent pixels, so a one-chunk difference in any run's cascade ring would
shift that row's crop and every column would misalign — a failure that looks exactly like the
non-determinism the picture is about.

## The amplifier jar

`splash-build/` → `gtnhsplash`. A documentation harness, never released, deliberately absent from
`.github/workflows/release.yml`. It forces two generators to attempt at fixed known chunks so the
crop windows can be hardcoded:

- **TinkersConstruct slime islands** — `@Redirect` on the single `Random.nextInt` in
  `SlimeIslandGen.generate`, returning 0 on plot chunks. GTNH ships `Slime Island Rarity = 8000`,
  about two islands in the whole walk, landing anywhere.
- **Witchery structures** — `@Inject` at RETURN on `nonInRange`, forcing a 3x3 chunk cluster.

Both go through the stock call site and both inject rather than overwrite, so the RNG stream is
bit-identical to an unforced run and each structure is still built by the real mod's real generator
carrying its real bug. The stock rarity draw is taken and discarded rather than skipped.

Forcing the *siting* is what makes the image an experiment rather than an anecdote: with placement
pinned, the only thing left that can differ between two launches is each structure's own internal
non-determinism.

### Three things that went wrong, and what they cost

**The village mixin did nothing.** `MapGenVillage.canSpawnStructureAtCoords` was forced and no
village appeared. VillageNames replaces the generator via `InitMapGenEvent` with
`astrotibs.villagenames.village.MapGenVillageVN`, which extends `MapGenVillage` and overrides both
`func_75047_a` and `func_75049_b`, so an injection into the superclass never runs. Rather than chase
it, the image uses villages where the seed already put them — siting is a pure function of the seed
(`World.setRandomSeed` overwrites `World.rand`; RWG's `areBiomesViable` ignores its biome-list
argument and is a seed-pure noise test), so the crops still align across all four runs and the
picture gets to say the villages are ordinary ones at stock density. `MapGenVillageForceMixin` was
deleted rather than left in as dead weight. F1 does still reach them: `StructureVillageVN`
`getStructureVillageWeightedPieceList` calls `VillagerRegistry.addExtraVillageComponents`, verified
by disassembly.

**The deepslate window search found a dungeon.** Selecting the y=21 window by "maximise the
stock-pair difference" picked a 2,200-cell window containing 921 air plus cobblestone and stonebrick
— a Roguelike dungeon present in the stock runs and not the fixed ones. A real defect, and not the
one the column claims to show; labelling it "deepslate" would have been a false caption. The search
now requires the window to be ≥90% undisturbed rock in all four worlds.

**Then it found my own forcing.** The next-best rock window sat directly under the forced Witchery
cluster. Witchery's `generate` draws from `World.rand` rather than the `Random` FML hands it, so a
forced cell consumes draws an unforced cell would not and shifts every later `World.rand` consumer
in that chunk — including Et Futurum's per-block shatter roll. That window would have been showing
variance this jar manufactured. Forced chunks are now excluded from the search. The one after
*that* sat at chunk −49, the outermost walked ring, which is half-populated cascade and differs run
to run for reasons unrelated to any mod's RNG; the search is now bounded to chunks ±44.

All three were caught by looking at what was actually in the window rather than trusting the score.

## Framing, disclosed

The two village windows and the deepslate window are chosen as the crop where the two **stock**
launches differ most, subject to every one of the four worlds having real content there (villages:
≥400 structure blocks; deepslate: ≥90% rock). That is a framing choice — it points the camera where
the bug is visible.

It is fair because the fixed pair is then cropped at the identical coordinates and gets no such
help: the comparison the image makes is between rows at one fixed place, not between
differently-chosen crops. The unselected whole-walk figures in the tables above are the honest
headline and are what the README caption quotes. The slime island and Witchery windows are not
selected at all — they are fixed by the forced plot coordinates.

The five windows actually used, each 44 x 50 blocks rendered at 4 px/block. `columns.json` carries
these but is not tracked (`results/**` keeps only `.md .csv .txt .sh .py`), so they are recorded
here:

| column | window |
|---|---|
| village — vanilla + VillageNames | x −7..36, z −2..47 |
| village — second site | x 180..223, z −549..−500 |
| slime island — TinkersConstruct | x −406..−363, z 151..200 |
| witchery — 3x3 forced cells | x −414..−371, z −385..−336 |
| deepslate — slice at y=21 | x −476..−433, z −680..−631 |

## The residual

311 blocks across 26 chunks in the fixed launch pair. Characterised, not dismissed:

- ~72 blocks at (−337, 75, −40): `air -> 176:0` and `air -> 169:1`, a modded surface structure
  present in one launch and not the other.
- ~110 blocks of GregTech bookkeeping: `2526 <-> 13/1/2540/2530` (stone/gravel/GT stone) and
  `3034:NNNNN -> 3039:NNNNN` (ore blocks changing host stone at matching metas). This is the
  documented "same material in a different host stone" bucket.
- ~45 blocks around (−86..−90, 76..79, 420..425): leaves and air, one tree differing.

The likely mechanism for at least some of it is on record and is *not* fixed by the jar: FML's
`IWorldGenerator` dispatch order is itself launch-varying —
`GameRegistry.computeSortedGeneratorList` stable-sorts a copy of a `HashSet`, so within a weight the
order is identity-hash order, and `results/2026-09-07-populate-stream-census` measured 19–22 of 30
positions differing between two launches. Stream B generators are immune to each other's draw-count
skew but not to write order, first writer wins.

Measured over the **whole** saved world rather than chunks ±44, the fixed pair differs by 8,906
blocks across 38 chunks — but 8,400 of those are in chunks (27,−51), (27,−50) and (28,−50), outside
the radius-48 walk entirely, where chunks are half-populated cascade. That is why the inner window
is the reported measure, and why the number is stated with its window.

## Provenance

All four worlds: `seed=-1636594104014467454`, `order=rows`, `radius=48`, `cx=cz=0`,
`level_type=rwg`, `jvmflags=-Dsplash.enable=true`, and `config_deltas` reporting the same 5 keys
differing from stock identically in both arms.

Jars, identical across all four runs except the one under test:

| jar | md5 | in |
|---|---|---|
| `worldgenprobe-v0.8-main.5+ce5dc21568-dirty` | `53bae43c…` | all four |
| `gtnhsplash-v0.8-main.6+c5c27e7d97-dirty` | `dfefe3ca0f03dc29cabb13c4703dfda5` | all four |
| `gtnhdeterminism-v0.8-main.5+ce5dc21568-dirty` | `46411be7…` | fixed rows only |

`scripts/probe-provenance.py` did not track `gtnhsplash` when these runs were made, so its md5 was
checked by hand; `JAR_PREFIXES` has since been extended so a future run stamps it. An amplifier that
differed between arms would otherwise not have announced itself, which is the whole job of that
file.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
scripts/build-jar.sh splash
results/2026-09-08-launch-variance-splash/setup-servers.sh
results/2026-09-08-launch-variance-splash/run.sh                 # 4 cold runs, ~2.5 min each
results/2026-09-08-launch-variance-splash/locate-columns.py ~/.cache/gtnh-determinism/splash-out
O=~/.cache/gtnh-determinism/splash-out
python3 scripts/splash-strips.py \
  --world "stock, launch 1=$O/world-stock-r1" --world "stock, launch 2=$O/world-stock-r2" \
  --world "gtnh-determinism, launch 1=$O/world-fixed-r1" \
  --world "gtnh-determinism, launch 2=$O/world-fixed-r2" \
  --columns results/2026-09-08-launch-variance-splash/columns.json \
  --out docs/img/launch-variance.png
```

The stock rows will not reproduce byte-for-byte — that is the point of them — so `locate-columns.py`
must be re-run to re-pick the village and deepslate windows against the new worlds. The fixed rows
should reproduce.
