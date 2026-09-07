# 2026-09-06 — warm probe runs do not reproduce cold boots on beta-3

**`docs/HANDOFF.md:224` says "Terrain is byte-identical warm vs cold — zero differing blocks", with a
tile-entity-only residual. That does not hold here. Warm vs cold is 293 of 625 chunks, blocks-only,
te-only ZERO — against a cold-vs-cold floor of 5 and a warm-vs-warm floor of 8. This is not a noise
floor and it is not the documented TE residual; it is the opposite shape.**

GTNH 2.9.0-beta-3 server (`~/.cache/gtnh-determinism/beta3`), seed `-1636594104014467454`, radius 6
centred on chunk (9,9), `rows` order, 625 chunks compared (169 walked + 456 spawn-preload).
Fix jar and probe both `v0.8-main.2+4c6e016626`. Artifacts and driver in
`~/.cache/gtnh-determinism/f9warm/`.

## The floors, which are the point

A gap only means something next to the floor for the same comparison. Both floors were measured, on
the same seed, radius, walk order and jars:

| pair | chunks differ | blocks-only | te-only |
| --- | ---: | ---: | ---: |
| cold vs cold, two launches | **5 / 625** | 4 | 0 |
| warm vs warm, two JVMs | **8 / 625** | 8 | 0 |
| **warm vs cold** | **293 / 625** | 292 | 0 |

59x the cold floor. Whatever this is, it is not launch noise: two cold boots of this pack agree to 5
chunks, and two warm runs agree to 8.

## It is not confined to the replicated preload

The warm path replicates `initialWorldChunkLoad` (spawn ±192) before walking. If the divergence were
an imperfect preload replication it would sit in `spawnextra` and leave the walked window alone. It
does not:

| pair | walked window | spawn preload |
| --- | ---: | ---: |
| cold vs cold | 1 / 169 | 4 / 456 |
| warm vs warm | 0 / 169 | 8 / 456 |
| **warm vs cold** | **103 / 169 (61%)** | **190 / 456 (42%)** |

The walked window is the *more* affected of the two.

## It is not surface decoration

Block noise by Y-section, warm vs cold:

| y | chunks |
| --- | ---: |
| 0-15 | 96 |
| 16-31 | 125 |
| 32-47 | **151** |
| 48-63 | 138 |
| 64-79 | 31 |
| 80-95 | 10 |

Depth-weighted. Only 41 of 551 section-hits are above y64, so foliage and surface decoration cannot
be the driver — those live at the surface. The peak at y32-47 is above the Et Futurum deepslate band
(y16-31), which is itself only the second-largest bucket, so the deepslate fix does not explain it
either.

**These are Y-section and category *hints*, not an inventory.** Nothing here identifies the blocks.
`scripts/inventory-region-diff.py` is the tool that accounts for 100% of differing blocks with an
explicit unclassified bucket, and it needs two persisted worlds — which this run does not have,
because `warm-probe.sh` deletes its save during teardown (`level.dat` is gone; only orphan region
files remain). Capturing a warm world needs the daemon's `{"save": "true"}` job via
`scripts/probe-queue.sh`. Until that is done, **no claim about which blocks moved is supported.**

## Why this matters

`HANDOFF.md:224` is the standing justification for measuring terrain warm rather than cold, described
there as avoiding "roughly an 8x wall-clock penalty". If it no longer holds on this pack, terrain work
measured warm since that note was written needs rechecking. Note that `HANDOFF.md:47` already
retracted an earlier warm-vs-cold figure once — this area has gone stale before.

Two things this is **not**:

- **Not the F9 detection bug** fixed the same day (`4c6e016`, `EarlyLootTables.isActive()`). The gap
  is present in the `gtnhdet.f9=on` control arm, which that change does not touch, and is unchanged
  in magnitude across it (290 before, 293 after, on different jar builds).
- **Not the documented residual.** `HANDOFF.md:220-224` measured `te-only: 165, blocks-only: 0`. This
  is `blocks-only: 292, te-only: 0` — the exact inverse. Whatever was measured there and whatever is
  happening here are different phenomena, or the world changed underneath the claim.

## Not yet answered

1. Does this reproduce on `daily-707` as well as beta-3, and on HANDOFF's seed
   (`-3312870596887951991`, radius 8)? That distinguishes "the claim went stale" from "these two
   measurements differ by configuration".
2. Which blocks. Requires the persisted-world capture above.
3. Is the warm-vs-warm floor of 8 itself expected? HANDOFF's framing implies it should be 0, and all
   8 are in the preload.
