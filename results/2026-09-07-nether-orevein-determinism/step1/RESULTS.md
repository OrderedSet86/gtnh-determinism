# Step 1 — regression gate: N2 must not move the whitelisted dimensions

Jar `gtnhdeterminism-v0.8-main.2+4c6e016626-dirty` md5 `0b225526d6a68142fd2c4da6324efb27`
(N0+N1+N2+N3), default `gtnhdet.orepin.dims=0,7`, r60, seed `-1636594104014467454`, centre (0,0),
`PROBE_SEARCH=false`.

| dimension | mix differs | geometry differs | only in one |
| --- | ---: | ---: | ---: |
| overworld (dim 0) | **0 / 1766** | 0 | 6 |
| Twilight Forest (dim 7) | **0 / 1764** | 0 | 0 |

Unfiltered gives the same numbers over 1865 and 1965 common regions.

N2 gates the F4-family virgin reads on the dimension whitelist for the first time. In dims 0 and 7
the whitelist admits them, so the pin's measured result is expected to be unchanged, and it is.

## The 6 overworld only-in-one regions are a walk-boundary artifact, not a regression

Decoding each key's oreseed chunk from `(worldSeed << 16) ^ (dim << 56 | osX << 28 | osZ)`:

```
B-only dim=0 oreseed chunk=(  7,-65)      A-only dim=0 oreseed chunk=( 25,-65)
B-only dim=0 oreseed chunk=( 10,-65)      A-only dim=0 oreseed chunk=( 28,-65)
                                          A-only dim=0 oreseed chunk=(-65, 43)
                                          A-only dim=0 oreseed chunk=(-65, 46)
```

Every one is at |chunk| = 65, i.e. **outside the r60 walk box** (-60..60). These are regions triggered
by population cascading past the walk edge, and which of them a walk reaches depends on the order it
arrives at the boundary.

Not caused by N2: the shipped jar shows the identical pattern in step 0's Nether arm — 6 only-in-one,
all at |chunk| 65 or 71, all outside the box. The F4d headline's `only-in-one 0` was measured on a
spawn-centred walk rather than this (0,0)-centred one.

The pin's own metric — mix identity inside the walked region — is 0 in both dimensions.
