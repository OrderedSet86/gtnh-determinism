# Stage-0 prefilter sweep — 5000 random seeds (random:5000:42), 2026-07-25

Probe jar: worldgenprobe-v0.4-main.25 (prefilter coke% modules, commit ea089c5),
GTNH 2.8.4 template, fix jar 0.5pre. Gates:
  PREFILTER_GATE_VILLAGEDIST=12  PREFILTER_GATE_PIECES=VillageComponentPhotoshop,ComponentToolWorkshop,ComponentSmeltery
  PREFILTER_GATE_WATER=32
143.9 s total (34 seeds/s). Kills: village 4408, water 296, pieces 5 → 291 survivors.
Rank with: seedsearch/coke-rank.py sweep.jsonl --require paper,tic,furnace
(100-block village rule default; 8/5000 seeds survive the full coke% criteria)
Reminder: stage-0 predicts layout/terrain only — chest CONTENTS and dungeons
(marshmallows) need a stage-1 probe run on the finalists.
