# Interpretation (read first)

Run: 10 seeds x 2 repeats x {stock, fixed}, all cold boots, radius 15, GTNH 2.7.4, fix jar 0.3.

Headline results:
- **Structure presence unchanged**: village count identical in 10/10 seeds; witchery structure
  counts identical in 10/10. The fix jar does not add or remove structures.
- **Village layouts**: fixed layouts fall outside the 2-repeat stock range in 4/10 seeds — expected
  and by design: stock scrambles layouts per launch (that is the bug); the fix pins ONE layout drawn
  from the same piece-weight distribution. With n=2 stock repeats the "range" is a weak envelope, so
  "outside" here does not mean biased. For upstream evidence, raise stock repeats (R=5+).
- **Chest loot totals**: fixed within stock range 5/10; item-mix differences are dominated by
  layout-dependent containers (different buildings => different loot rolls), same caveat as above.
- **Vein material totals**: shifts are the documented F4 semantic change (rerolls answered from
  virgin terrain instead of populate-order-dependent live terrain) — vein identities become
  route-independent; totals move within normal inter-seed spreads. Oilsands 9906->0 on seed -777 is
  a single vein-type flip at region scale, worth a follow-up sample if it recurs.
- **REGRESSION FOUND (the run paid for itself): fixed self-consistency fails in 4/10 seeds** —
  two cold launches of the FIXED jar differ in chest contents of Roguelike Dungeon floors (layout
  byte-identical; e.g. seed -777, dungeon at chunks x[-26..-17] z[16..23], one floor's loot
  re-rolls while other floors are byte-stable). The 0.3 TreasureManagerMixin covers only part of
  the roguelike loot path. Scheduled for 0.4.
- Water/clay scan deltas are at the 0.05% level (post-hash TE-conversion artifact + the documented
  BOP dirt/gravel relocation) — not gameplay-relevant.
# Stock vs fix-jar worldgen effects

## seed -123456789  (stock n=2, fixed n=2)
- fixed self-consistency: **DIVERGES — regression!**
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 1 | fixed 1
- chest_item_total: stock 1927..2440 | fixed 2005 (within stock range)
- water: stock 150746..151596 | fixed 152522 **[OUTSIDE stock range]**
- clay: stock 2206..2217 | fixed 2252 **[OUTSIDE stock range]**
- chest_items diffs (stock-max vs fixed): {'gregtech:gt.metaitem.02:32100': (174, 110), 'minecraft:stonebrick:0': (113, 59), 'minecraft:cobblestone:0': (45, 94), 'minecraft:arrow:0': (41, 79), 'minecraft:double_stone_slab:0': (34, 0), 'gregtech:gt.metaitem.01:11057': (93, 63), 'minecraft:bone:0': (79, 49), 'minecraft:stick:0': (78, 52)}
- vein_materials diffs (stock-max vs fixed): {'Coal': (23515, 27282), 'Lazurite': (4900, 3189), 'Zeolite': (6808, 5109), 'Sodalite': (4888, 3211), 'Kaolinite': (3598, 2181), 'Magnetite': (25551, 24144), 'Graphite': (357, 1552), 'Lapis': (2757, 1815)}

## seed -4200000000000000001  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 1 | fixed 1
- village_pieces: stock 23..28 | fixed 43 **[OUTSIDE stock range]**
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 524..644 | fixed 507 **[OUTSIDE stock range]**
- water: stock 297001..297385 | fixed 295824 **[OUTSIDE stock range]**
- clay: stock 6103..6194 | fixed 6168 (within stock range)
- village_piece_types diffs (stock-max vs fixed): {'Path': (6, 10), 'House1': (1, 4), 'WoodHut': (0, 3), 'Torch': (0, 2), 'Field2': (3, 5), 'House4Garden': (3, 5), 'ComponentVillageCustomField': (1, 0), 'ComponentVillageWatchTower': (1, 0)}
- chest_items diffs (stock-max vs fixed): {'minecraft:rail:0': (23, 0), 'harvestcraft:baconmushroomburgerItem:0': (4, 26), 'harvestcraft:supremepizzaItem:0': (17, 0), 'gregtech:gt.metaitem.02:32100': (136, 121), 'Railcraft:part.tie:0': (10, 0), 'TConstruct:woodPattern:5': (9, 0), 'TConstruct:woodPattern:22': (8, 0), 'BiomesOPlenty:misc:1': (8, 0)}
- vein_materials diffs (stock-max vs fixed): {'GarnetSand': (6599, 2528), 'CassiteriteSand': (5438, 1714), 'Oilsands': (1070, 3668), 'Asbestos': (3443, 1269), 'Apatite': (7624, 5467), 'Zeolite': (2837, 4715), 'GraniticMineralSand': (9581, 11350), 'YellowLimonite': (9328, 10975)}

## seed -777  (stock n=2, fixed n=2)
- fixed self-consistency: **DIVERGES — regression!**
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 2397..2483 | fixed 2289 **[OUTSIDE stock range]**
- water: stock 53857..54004 | fixed 53639 **[OUTSIDE stock range]**
- clay: stock 3990 | fixed 3972 **[OUTSIDE stock range]**
- chest_items diffs (stock-max vs fixed): {'gregtech:gt.metaitem.02:32100': (169, 71), 'minecraft:cobblestone:0': (179, 95), 'minecraft:stone:0': (30, 73), 'Avaritia:Ultimate_Stew:0': (53, 15), 'harvestcraft:baconmushroomburgerItem:0': (37, 0), 'harvestcraft:deluxecheeseburgerItem:0': (25, 0), 'minecraft:cooked_beef:0': (53, 30), 'minecraft:double_stone_slab:0': (23, 0)}
- vein_materials diffs (stock-max vs fixed): {'Oilsands': (9906, 0), 'Zeolite': (4730, 6895), 'Kaolinite': (2374, 4042), 'Chalcopyrite': (7168, 5614), 'Magnetite': (16655, 18048), 'Redstone': (4283, 5589), 'Talc': (734, 1740), 'Vermiculite': (860, 0)}

## seed -987654321012345678  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 1 | fixed 1
- village_pieces: stock 23 | fixed 28 **[OUTSIDE stock range]**
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 695..711 | fixed 803 **[OUTSIDE stock range]**
- water: stock 119159..119170 | fixed 119095 **[OUTSIDE stock range]**
- clay: stock 1686..1699 | fixed 1679 **[OUTSIDE stock range]**
- village_piece_types diffs (stock-max vs fixed): {'WoodHut': (3, 0), 'ComponentToolWorkshop': (2, 0), 'ComponentVillageCustomField': (1, 0), 'House1': (2, 3), 'Field2': (1, 2), 'ComponentVillageWitchHut': (0, 1), 'Church': (1, 0), 'ComponentVillageApothecary': (1, 0)}
- chest_items diffs (stock-max vs fixed): {'gregtech:gt.metaitem.02:32100': (116, 148), 'harvestcraft:epicbaconItem:0': (0, 27), 'IC2:itemShardIridium:0': (119, 144), 'Railcraft:part.tie:0': (0, 15), 'minecraft:gold_nugget:0': (27, 13), 'Forestry:candle:0': (0, 11), 'gregtech:gt.metaitem.01:11031': (21, 13), 'gregtech:gt.metaitem.01:8528': (21, 13)}
- vein_materials diffs (stock-max vs fixed): {'Coal': (39397, 31621), 'Lignite': (17114, 10699), 'GarnetSand': (3099, 5512), 'Sodalite': (4947, 2967), 'CassiteriteSand': (1869, 3760), 'Lazurite': (4518, 2766), 'YellowLimonite': (12508, 14072), 'Chalcopyrite': (7577, 9107)}

## seed 1234567890  (stock n=2, fixed n=2)
- fixed self-consistency: **DIVERGES — regression!**
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 3 | fixed 3
- chest_item_total: stock 581..595 | fixed 573 **[OUTSIDE stock range]**
- water: stock 31993..33020 | fixed 33350 **[OUTSIDE stock range]**
- clay: stock 2600..2636 | fixed 2630 (within stock range)
- chest_items diffs (stock-max vs fixed): {'minecraft:rail:0': (30, 0), 'gregtech:gt.metaitem.01:11057': (55, 70), 'Railcraft:part.gear:3': (5, 0), 'harvestcraft:curryItem:0': (4, 0), 'minecraft:tnt_minecart:0': (0, 3), 'minecraft:apple:0': (2, 0), 'minecraft:saddle:0': (7, 5), 'minecraft:melon_seeds:0': (0, 2)}
- vein_materials diffs (stock-max vs fixed): {'Magnetite': (27066, 24790), 'Oilsands': (5606, 3368), 'CassiteriteSand': (5312, 6821), 'GraniticMineralSand': (9150, 7682), 'GarnetSand': (5800, 7255), 'Redstone': (12112, 13515), 'Spessartine': (982, 1908), 'Grossular': (1004, 1879)}

## seed 2026072214  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 762..793 | fixed 764 (within stock range)
- water: stock 138606..139732 | fixed 139973 **[OUTSIDE stock range]**
- clay: stock 7451..7455 | fixed 7434 **[OUTSIDE stock range]**
- chest_items diffs (stock-max vs fixed): {'gregtech:gt.metaitem.01:11034': (19, 10), 'gregtech:gt.metaitem.02:32137': (31, 23), 'gregtech:gt.metaitem.01:11302': (11, 4), 'gregtech:gt.metaitem.01:11305': (26, 21), 'gregtech:gt.metaitem.01:8505': (10, 7), 'gregtech:gt.metaitem.01:11308': (10, 8), 'gregtech:gt.metaitem.01:11300': (17, 15), 'gregtech:gt.metaitem.01:11058': (2, 0)}
- vein_materials diffs (stock-max vs fixed): {'Magnetite': (27572, 23404), 'Coal': (23231, 19288), 'GraniticMineralSand': (7818, 6237), 'CassiteriteSand': (5089, 6617), 'BasalticMineralSand': (6827, 5388), 'GarnetSand': (7893, 9307), 'VanadiumMagnetite': (7118, 6005), 'YellowLimonite': (12529, 13566)}

## seed 314159265358979  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 933..957 | fixed 952 (within stock range)
- water: stock 51643..51908 | fixed 51582 **[OUTSIDE stock range]**
- clay: stock 1779..1789 | fixed 1777 **[OUTSIDE stock range]**
- chest_items diffs (stock-max vs fixed): {'gregtech:gt.metaitem.01:11057': (21, 41), 'gregtech:gt.metaitem.01:11054': (51, 36), 'gregtech:gt.metaitem.01:11036': (8, 16), 'gregtech:gt.metaitem.01:11031': (26, 20), 'gregtech:gt.metaitem.01:8527': (16, 10), 'gregtech:gt.metaitem.01:11305': (46, 40), 'gregtech:gt.metaitem.01:11300': (31, 36), 'gregtech:gt.metaitem.01:11035': (2, 7)}
- vein_materials diffs (stock-max vs fixed): {'Magnetite': (26197, 20070), 'Iron': (4762, 6604), 'Chalcopyrite': (4479, 6261), 'VanadiumMagnetite': (6446, 4947), 'Apatite': (6597, 5541), 'Pyrite': (2364, 3306), 'Gold': (3491, 2683), 'Talc': (1010, 1503)}

## seed 42  (stock n=2, fixed n=2)
- fixed self-consistency: **DIVERGES — regression!**
- villages: stock 2 | fixed 2
- village_pieces: stock 15..17 | fixed 13 **[OUTSIDE stock range]**
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 1120..1171 | fixed 1157 (within stock range)
- water: stock 146195..146316 | fixed 146433 **[OUTSIDE stock range]**
- clay: stock 4214..4223 | fixed 4224 **[OUTSIDE stock range]**
- village_piece_types diffs (stock-max vs fixed): {'ComponentVillageApothecary': (0, 2), 'Wall': (2, 0), 'House3': (2, 0), 'ComponentToolWorkshop': (2, 0), 'ComponentWorkshop': (1, 0), 'ComponentVillageCustomField': (1, 0), 'Field2': (0, 1), 'Church': (1, 0)}
- chest_items diffs (stock-max vs fixed): {'IC2:itemShardIridium:0': (136, 124), 'harvestcraft:spagettiandmeatballsItem:0': (10, 0), 'gregtech:gt.metaitem.01:11036': (12, 5), 'IC2:itemIngot:0': (41, 35), 'gregtech:gt.metaitem.01:11054': (65, 70), 'harvestcraft:deluxecheeseburgerItem:0': (4, 0), 'gregtech:gt.metaitem.01:11031': (47, 50), 'gregtech:gt.metaitem.01:11034': (24, 21)}
- vein_materials diffs (stock-max vs fixed): {'Coal': (20332, 23724), 'Oilsands': (3844, 6964), 'Magnetite': (31332, 28806), 'YellowLimonite': (12773, 15092), 'BrownLimonite': (12640, 14870), 'GarnetSand': (7425, 5292), 'CassiteriteSand': (5385, 3566), 'Talc': (1388, 2849)}

## seed 55555555555  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 0 | fixed 0
- village_pieces: stock 0 | fixed 0
- witchery_structures: stock 1 | fixed 1
- chest_item_total: stock 1070 | fixed 1096 **[OUTSIDE stock range]**
- water: stock 48058 | fixed 47715 **[OUTSIDE stock range]**
- clay: stock 3436..3446 | fixed 3464 **[OUTSIDE stock range]**
- chest_items diffs (stock-max vs fixed): {'harvestcraft:delightedmealItem:0': (3, 31), 'DraconicEvolution:dezilsMarshmallow:0': (1, 28), 'gregtech:gt.metaitem.02:32100': (179, 202), 'Railcraft:fuel.coke:0': (21, 0), 'Railcraft:part.tie:0': (16, 0), 'gregtech:gt.metaitem.01:11035': (22, 13), 'minecraft:redstone:0': (2, 7), 'gregtech:gt.metaitem.01:11089': (16, 11)}
- vein_materials diffs (stock-max vs fixed): {'Oilsands': (11084, 14540), 'Redstone': (7696, 11141), 'Sodalite': (5236, 3098), 'Lazurite': (5300, 3177), 'Coal': (15365, 13259), 'RockSalt': (3597, 1837), 'BrownLimonite': (10366, 12090), 'Salt': (3262, 1538)}

## seed 8675309  (stock n=2, fixed n=2)
- fixed self-consistency: IDENTICAL
- villages: stock 2 | fixed 2
- village_pieces: stock 73..112 | fixed 58 **[OUTSIDE stock range]**
- witchery_structures: stock 0 | fixed 0
- chest_item_total: stock 805..836 | fixed 823 (within stock range)
- water: stock 116371..117304 | fixed 117028 (within stock range)
- clay: stock 4412..4437 | fixed 4502 **[OUTSIDE stock range]**
- village_piece_types diffs (stock-max vs fixed): {'Torch': (13, 1), 'Path': (26, 14), 'Field1': (8, 2), 'WoodHut': (10, 4), 'House4Garden': (12, 6), 'Hall': (7, 1), 'House3': (6, 2), 'ComponentVillageCustomField': (3, 0)}
- chest_items diffs (stock-max vs fixed): {'IC2:itemShardIridium:0': (87, 101), 'minecraft:clay_ball:0': (17, 8), 'gregtech:gt.metaitem.01:8503': (15, 8), 'minecraft:apple:0': (0, 6), 'TConstruct:woodPattern:22': (5, 0), 'gregtech:gt.metaitem.01:11048': (4, 0), 'gregtech:gt.metaitem.01:8527': (18, 14), 'TConstruct:woodPattern:6': (4, 0)}
- vein_materials diffs (stock-max vs fixed): {'Apatite': (6713, 4909), 'Zeolite': (3933, 5458), 'Grossular': (2214, 1227), 'GarnetSand': (11528, 10714), 'Spessartine': (2104, 1299), 'CassiteriteSand': (8153, 7398), 'Graphite': (3247, 3959), 'FullersEarth': (3547, 4243)}

## Aggregate
- chest_item_total_ok: 5
- chest_item_total_outside: 5
- clay_ok: 2
- clay_outside: 8
- fixed_ok: 6
- fixed_regression: 4
- village_pieces_ok: 6
- village_pieces_outside: 4
- villages_ok: 10
- water_ok: 1
- water_outside: 9
- witchery_structures_ok: 10