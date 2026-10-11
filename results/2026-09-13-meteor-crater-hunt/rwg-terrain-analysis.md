# RWG terrain structural analysis — carve ceiling

Question: can RWG produce geometry that beats the found 404k carve — specifically a deep narrow
floor ringed by 100+ walls inside the 96-block carve disc? Source: GTNewHorizons/Realistic-World-Gen
clone at alpha-1.3.3.0, verified formula-identical to the shipped RWG-alpha-1.5.2 jar (only the
noise backend is pluggable between them; all amplitude/frequency constants byte-identical —
checked on TerrainMountainSpikes bytecode).

## The three structural facts

1. **Walls can be much taller than what the hunt found.** Mountain terrain squares its perlin
   (`m = (n(300)·135·river)²/32`) then hard-clamps: TerrainMountain/MountainRiver clamp 150
   (+67 base → ~217 absolute; biomes: Tundra Hills, Wood Mountains, Snow Rivers, Taiga Hills);
   TerrainMountainSpikes clamps 155 with +30 cell spikes at 25-block wavelength (~283 absolute;
   biome: Snow Hills). Rise of 100+ blocks over 50-90 horizontal blocks is routine at massif
   edges. The 90k-sweep best had relief 121 — the terrain offers ~158 sustained.

2. **Every deep low is either a through-cut channel or an unringed bowl.** Rivers/canyons are
   Voronoi cell borders (cell scale 1250, domain-warped): the carve slot (~68-140 blocks wide)
   bottoms at ~59 **regardless of surrounding terrain height** (`59 + small noise` clamp in
   `calculateRiver`), and the river-strength field suppresses the `·river` multiplier in every
   mountain formula for ~150 blocks each side — so channels are always open on >= 2 bearings and
   mountains lean AWAY from them. Lake bowls (`TerrainHilly` subtractive lake term, Redwood
   depth 90) are closed on all bearings but wide (~130 blocks) and their perlin is independent
   of the mountain perlin — no guaranteed high ring. The mesa/canyon concentric terracing is
   blend-weight-gated plateau construction: rings that step UP toward the biome core — the wrong
   polarity for a crater.

3. **Biome blending only smooths.** The parabolic kernel convolves neighbor heights over ~64
   blocks; it can juxtapose a 217 massif against a 59 river floor (the one-sided ~158 drop) but
   never builds a wall steeper than the steeper component, and never a ring.

## Ceiling arithmetic

Carve model V = f · 2π ∫ (A − 0.02 d²)+ d dd over the disc, calibrated on the verified 404k /
relief-121 site → effective azimuth fraction f ≈ 0.31 (one wedge; river suppression eats the
rest). Then:

| config | wall in disc | f | est. carve |
|---|---|---|---|
| found best (90k sweep) | 121 | 0.31 | 404k (verified) |
| tallest-tier mountain biome against a river edge | 150-158 | 0.31-0.4 | **600-750k** |
| 3-way river Voronoi vertex inside a tall mountain biome | 121-150 | 0.45-0.55 | **550-800k** |
| full-ring basin (best any single biome offers) | ~50 | ~1.0 | ~176k (found: the 15/16 bowl) |
| hypothetical 200-wall closed basin | — | — | structurally absent |

**Verdict: 600-800k is reachable — +50-100% over the current champion — via ONE-to-THREE-wedge
massif sites in the tallest biomes, not via enclosure.** The 15/16 and 16/16 bowls the sweep
found (176k, 127k) are already at the closed-basin ceiling; seven-figure carves would need
geometry RWG does not generate.

## Targeting spec for a follow-up hunt

- Biome pre-gate (chunk-manager lookup, no chunkgen): impact surface <= 75 (channel floor) AND
  >= 1 of 8 ring samples at ~56 blocks in {Snow Hills, Tundra Hills, Wood Mountains, Snow
  Rivers, Taiga Hills} (the clamp-150/155 terrain classes).
- Relief gate 100+, volume bar 300k, burial <= 9 (the burial-10 knife edge measured in-game).
- The enclosure metric stays as a tiebreak (3-way vertices show as 8-10/16), not a target.
