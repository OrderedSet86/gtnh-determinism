package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraftforge.common.ChestGenHooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Exports the pack's chest loot to CSV. Enable with {@code -Dprobe.lootcsv=<dir>}.
 *
 * <p>
 * Writes two files:
 * <ul>
 * <li>{@code chestloot.csv} — Forge {@code ChestGenHooks}, Roguelike Dungeons and the Twilight Forest, tagged by a
 * {@code source} column.
 * <li>{@code lootbags.csv} — EnhancedLootBags, which uses per-item percentages instead of weights and so cannot share
 * the other schema without making both misleading.
 * </ul>
 *
 * <p>
 * ChestGenHooks is captured twice. TooMuchLoot replaces whole categories at {@code FMLServerStartingEvent}, but a cold
 * boot generates the spawn region inside {@code startServer()}, which runs earlier. Chests in the spawn preload
 * therefore roll the {@code pre} table and every later chest rolls {@code post}. In 1.7.10 a chest is filled when it is
 * placed, so the difference is permanent.
 *
 * <p>
 * Roguelike Dungeons does not use ChestGenHooks at all. It reads its own rules from
 * {@code config/roguelike_dungeons/settings}, which is why its loot is absent from the ChestGenHooks tables.
 *
 * <p>
 * The Twilight Forest does not use it either. {@code TFTreasure.generate} places a vanilla chest and fills it from
 * five static pools of its own, so TF loot is invisible to both the ChestGenHooks capture and the determinism jar's
 * {@code WeightedRandomChestContent} chest fix. See {@link #captureTfTreasure()}.
 */
public final class ChestLootExport {

    private static final Logger LOG = LogManager.getLogger("worldgenprobe");

    /** Rows accumulate here because "pre" and "post" are captured at different events but share one file. */
    private static final List<String> ROWS = new ArrayList<>();
    /** (registry name, meta) pairs seen, tab-joined — the input to writeItemAttributes. */
    private static final java.util.Set<String> ATTR_KEYS = new java.util.HashSet<>();

    /**
     * Reading order: what the chest is, then what drops and how often, then the ids needed to look an item up.
     *
     * <p>
     * {@code enchant_level} is blank for every source but {@code tftreasure}. It cannot fold into {@code nbt},
     * because TF applies it at draw time via {@code EnchantmentHelper.addRandomEnchantment} and so it is
     * genuinely absent from the stored stack's tag. Appending a column rather than overloading one is safe here:
     * the only consumer, {@code scripts/tc-chest-materials.py}, reads with {@code csv.DictReader}.
     */
    private static final String HEADER = "source,phase,table,category,level,rolls_min,rolls_max,to_each,"
        + "display_name,weight,stack_min,stack_max,pool_total_weight,pick_chance_per_roll,"
        + "registry_name,meta,entry_class,nbt,enchant_level";

    private ChestLootExport() {}

    public static File dir() {
        final String d = System.getProperty("probe.lootcsv");
        return d == null ? null : new File(d);
    }

    // ---------------------------------------------------------------- ChestGenHooks

    private static boolean firstPopulateCaptured;

    /**
     * Capture the table as it stands when the world's FIRST chunk is populated — the only phase that answers "what
     * did the spawn preload actually roll", independent of where in the server lifecycle a mod moves the rewrite.
     *
     * <p>
     * {@code pre} is a lifecycle snapshot taken at {@code FMLServerAboutToStartEvent}. On a stock instance that is
     * also what the preload rolls, so the two coincide and {@code pre} was a usable proxy. The determinism jar's F9
     * applies TooMuchLoot at {@code MinecraftServer.loadAllWorlds} — later than {@code AboutToStart}, earlier than
     * the first chunk — at which point {@code pre} still reports the old table while no chest ever uses it. Compare
     * {@code firstpopulate} against {@code post}: equal means one table for the whole world.
     */
    public static synchronized void captureFirstPopulate() {
        if (firstPopulateCaptured || dir() == null) return;
        firstPopulateCaptured = true;
        captureChestGenHooks("firstpopulate");
    }

    /**
     * @param phase {@code pre} at FMLServerAboutToStart, {@code firstpopulate} at the world's first populated chunk,
     *              {@code post} after server start. See {@link #captureFirstPopulate()} for why the middle one
     *              exists.
     */
    public static void captureChestGenHooks(String phase) {
        if (dir() == null) return;
        try {
            final Map<String, ChestGenHooks> live = chestInfo();
            final List<String> cats = new ArrayList<>(live.keySet());
            Collections.sort(cats); // stable order, so two runs diff cleanly
            int rows = 0;
            for (String cat : cats) {
                final ChestGenHooks hooks = live.get(cat);
                final List<WeightedRandomChestContent> items = contents(hooks);
                int total = 0;
                for (WeightedRandomChestContent c : items) total += c.itemWeight;
                for (WeightedRandomChestContent c : items) {
                    final String cls = c.getClass() == WeightedRandomChestContent.class ? ""
                        : c.getClass()
                            .getSimpleName();
                    ROWS.add(
                        row(
                            "chestgenhooks",
                            phase,
                            "",
                            cat,
                            "",
                            String.valueOf(hooks.getMin()),
                            String.valueOf(hooks.getMax()),
                            "",
                            c.theItemId,
                            c.itemWeight,
                            c.theMinimumChanceToGenerateItem,
                            c.theMaximumChanceToGenerateItem,
                            total,
                            cls));
                    rows++;
                }
            }
            LOG.info("[probe][lootcsv] chestgenhooks {}: {} categories, {} rows", phase, cats.size(), rows);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] chestgenhooks {} capture failed", phase, t);
        }
    }

    // ---------------------------------------------------------------- Roguelike Dungeons

    /**
     * Reads {@code config/roguelike_dungeons/settings/loot_*.json}. Each file holds {@code loot_rules}, and each rule
     * gives a chest type, the dungeon levels it applies to, how many items are drawn, and a weighted pool. One row is
     * written per level per pool entry, so a rule covering five levels produces five copies — that keeps the level
     * column filterable rather than requiring the reader to expand a list.
     */
    public static void captureRoguelike(File configDir) {
        if (dir() == null) return;
        final File settings = new File(configDir, "roguelike_dungeons/settings");
        if (!settings.isDirectory()) {
            LOG.warn("[probe][lootcsv] roguelike settings not found at {}", settings);
            return;
        }
        final File[] files = settings.listFiles();
        if (files == null) return;
        final List<File> sorted = new ArrayList<>();
        for (File f : files) if (f.getName()
            .startsWith("loot_")
            && f.getName()
                .endsWith(".json"))
            sorted.add(f);
        Collections.sort(sorted);
        int rows = 0, tables = 0;
        for (File f : sorted) {
            try (FileReader r = new FileReader(f)) {
                final JsonElement parsed = new JsonParser().parse(r);
                if (!parsed.isJsonObject()) continue;
                final JsonObject root = parsed.getAsJsonObject();
                if (!root.has("loot_rules")) continue; // inherit-only file, e.g. loot_all
                final String table = root.has("name") ? root.get("name")
                    .getAsString() : f.getName();
                tables++;
                for (JsonElement re : root.getAsJsonArray("loot_rules")) {
                    rows += rule(table, re.getAsJsonObject());
                }
            } catch (Throwable t) {
                LOG.error("[probe][lootcsv] roguelike parse failed for {}", f.getName(), t);
            }
        }
        LOG.info("[probe][lootcsv] roguelike: {} tables, {} rows", tables, rows);
    }

    private static int rule(String table, JsonObject rule) {
        // Mirrors LootRuleManager.getLootPools: a rule may carry several pools, each sending the same item list to a
        // different chest type with its own quantity. When loot_pools is absent the rule object is itself the pool.
        final List<JsonObject> pools = new ArrayList<>();
        if (rule.has("loot_pools") && rule.get("loot_pools")
            .isJsonArray()) {
            for (JsonElement e : rule.getAsJsonArray("loot_pools"))
                if (e.isJsonObject()) pools.add(e.getAsJsonObject());
        }
        if (pools.isEmpty()) pools.add(rule);

        final List<String> levels = new ArrayList<>();
        if (rule.has("level")) {
            final JsonElement lv = rule.get("level");
            if (lv.isJsonArray()) for (JsonElement e : lv.getAsJsonArray()) levels.add(e.getAsString());
            else levels.add(lv.getAsString());
        } else levels.add("");

        if (!rule.has("loot")) return 0;
        final JsonArray loot = rule.getAsJsonArray("loot");
        int total = 0;
        for (JsonElement e : loot) {
            final JsonObject o = e.getAsJsonObject();
            if (o.has("weight")) total += o.get("weight")
                .getAsInt();
        }
        int n = 0;
        for (JsonObject pool : pools) {
            final String type = pool.has("type") ? pool.get("type")
                .getAsString() : "";
            final String each = pool.has("each") ? pool.get("each")
                .getAsString() : "";
            final String qty = pool.has("quantity") ? pool.get("quantity")
                .getAsString() : "";
            for (String level : levels) {
                for (JsonElement e : loot) {
                    final JsonObject o = e.getAsJsonObject();
                    final int weight = o.has("weight") ? o.get("weight")
                        .getAsInt() : 0;
                    final JsonObject data = o.has("data") && o.get("data")
                        .isJsonObject() ? o.getAsJsonObject("data") : null;
                    String name = "", nbt = "";
                    int meta = 0, min = 1, max = 1;
                    if (data != null) {
                        if (data.has("name")) name = data.get("name")
                            .getAsString();
                        if (data.has("meta")) meta = data.get("meta")
                            .getAsInt();
                        if (data.has("min")) min = data.get("min")
                            .getAsInt();
                        if (data.has("max")) max = data.get("max")
                            .getAsInt();
                        if (data.has("nbt")) nbt = data.get("nbt")
                            .getAsString();
                    }
                    // A provider entry (enchanted book, potion, ...) has no plain item; keep the row and let the empty
                    // display name say so rather than dropping loot from the table.
                    final ItemStack stack = resolve(name, meta);
                    ROWS.add(
                        rowRaw(
                            "roguelike",
                            "",
                            table,
                            type,
                            level,
                            qty,
                            qty,
                            each,
                            name,
                            meta,
                            stack == null ? "<not a plain item, or id not in this build>" : display(stack),
                            String.valueOf(weight),
                            String.valueOf(min),
                            String.valueOf(max),
                            String.valueOf(total),
                            total > 0 ? String.format("%.6f", (double) weight / total) : "0",
                            "",
                            nbt));
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- Twilight Forest

    /**
     * Exports the static {@code TFTreasure} tables. Like Roguelike, the Twilight Forest never touches
     * {@code ChestGenHooks}: {@code TFTreasure.generate} places a vanilla chest and fills it directly from its own
     * five pools, which is also why the determinism jar's structure-chest fix — a mixin on
     * {@code WeightedRandomChestContent} — does not reach a TF chest. It does not need to. TF's fill RNG is
     * already position-derived and seed-pure: {@code treasureRNG.setSeed(world.getSeed() * x + y ^ z)}.
     *
     * <p>
     * These tables are seed-independent, so one capture describes every world the pack generates. There is no
     * {@code pre} phase for the same reason there is none for Roguelike: TooMuchLoot rewrites ChestGenHooks
     * categories, not TFTreasure.
     *
     * <p>
     * Roll counts and pool-selection odds are read out of {@code TFTreasure.generate}, {@code getCommonItem} and
     * {@code getRareItem} rather than guessed. Each chest draws 4 common, 2 uncommon and 1 rare item, and the two
     * paired pools are chosen by {@code !other.isEmpty() && rand.nextInt(4) == 0} — so {@code useless} and
     * {@code ultrarare} take a quarter of their group's draws when they are populated, and {@code common} and
     * {@code rare} take the rest.
     */
    public static void captureTfTreasure() {
        if (dir() == null) return;
        try {
            final Map<String, Map<String, Object>> tables = TwilightForestProbe.treasureTables();
            if (tables == null) {
                LOG.info("[probe][lootcsv] Twilight Forest not loaded, skipping tftreasure");
                return;
            }
            int rows = 0;
            for (Map.Entry<String, Map<String, Object>> table : tables.entrySet()) {
                final Map<String, Object> pools = table.getValue();
                final boolean uselessFilled = !isEmptyPool(pools.get("useless"));
                final boolean ultrarareFilled = !isEmptyPool(pools.get("ultrarare"));
                for (String cat : TwilightForestProbe.TREASURE_CATEGORIES) {
                    final Object pool = pools.get(cat);
                    if (pool == null) continue;
                    final List<?> items = TwilightForestProbe.treasureItems(pool);
                    int total = 0;
                    for (Object it : items) total += (Integer) TwilightForestProbe.treasureItem(it)[1];
                    final String rolls = String.valueOf(rollsFor(cat));
                    final String each = String.format("%.6f", toEachFor(cat, uselessFilled, ultrarareFilled));
                    for (Object it : items) {
                        final Object[] parts = TwilightForestProbe.treasureItem(it);
                        final ItemStack stack = (ItemStack) parts[0];
                        final int weight = (Integer) parts[1];
                        final int ench = (Integer) parts[2];
                        String reg = "", disp = "", nbt = "";
                        int meta = 0, stackMax = 1;
                        try {
                            final Object name = Item.itemRegistry.getNameForObject(stack.getItem());
                            reg = name != null ? name.toString() : stack.getUnlocalizedName();
                            meta = stack.getItemDamage();
                            disp = stack.getDisplayName();
                            // getItemStack rolls nextInt(stackSize)+1 into a copy, so the stored size is the
                            // maximum of a uniform 1..max draw, not a fixed count.
                            stackMax = stack.stackSize;
                            if (stack.getTagCompound() != null) nbt = stack.getTagCompound()
                                .toString();
                        } catch (Throwable t) {
                            disp = "<item id does not resolve in this build>";
                        }
                        ROWS.add(
                            rowRaw(
                                "tftreasure",
                                "",
                                table.getKey(),
                                cat,
                                "",
                                rolls,
                                rolls,
                                each,
                                reg,
                                meta,
                                disp,
                                String.valueOf(weight),
                                "1",
                                String.valueOf(stackMax),
                                String.valueOf(total),
                                total > 0 ? String.format("%.6f", (double) weight / total) : "0",
                                "",
                                nbt,
                                ench > 0 ? String.valueOf(ench) : ""));
                        rows++;
                    }
                }
            }
            LOG.info("[probe][lootcsv] tftreasure: {} tables, {} rows", tables.size(), rows);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] tftreasure capture failed", t);
        }
    }

    /** Draws per chest for a pool's slot group — the hardcoded loop bounds in {@code TFTreasure.generate}. */
    private static int rollsFor(String category) {
        switch (category) {
            case "useless":
            case "common":
                return 4;
            case "uncommon":
                return 2;
            case "rare":
            case "ultrarare":
                return 1;
            default:
                throw new IllegalArgumentException("unknown TFTreasure pool: " + category);
        }
    }

    /** Probability this pool wins a draw of its slot group. See {@code getCommonItem}/{@code getRareItem}. */
    private static double toEachFor(String category, boolean uselessFilled, boolean ultrarareFilled) {
        switch (category) {
            case "useless":
                return uselessFilled ? 0.25 : 0;
            case "common":
                return uselessFilled ? 0.75 : 1;
            case "uncommon":
                return 1;
            case "rare":
                return ultrarareFilled ? 0.75 : 1;
            case "ultrarare":
                return ultrarareFilled ? 0.25 : 0;
            default:
                throw new IllegalArgumentException("unknown TFTreasure pool: " + category);
        }
    }

    private static boolean isEmptyPool(Object pool) {
        return pool == null || TwilightForestProbe.treasureItems(pool)
            .isEmpty();
    }

    // ---------------------------------------------------------------- EnhancedLootBags

    /**
     * Reads {@code config/EnhancedLootBags/LootBags.xml}. Its {@code Chance} is an independent percentage per entry,
     * not a share of a pool, so these rows are kept in their own file to avoid implying weight semantics.
     */
    public static void writeLootBags(File configDir, File out) {
        final File xml = new File(configDir, "EnhancedLootBags/LootBags.xml");
        if (!xml.isFile()) {
            LOG.warn("[probe][lootcsv] LootBags.xml not found at {}", xml);
            return;
        }
        try {
            final org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(xml);
            final NodeList groups = doc.getElementsByTagName("LootGroup");
            final File file = new File(out, "lootbags.csv");
            int rows = 0;
            try (PrintWriter w = new PrintWriter(file, "UTF-8")) {
                w.println(
                    "group_meta_id,group_name,rarity,min_items,max_items,registry_name,meta,display_name,"
                        + "amount,random_amount,chance_percent,item_group,limited_drop_count,nbt");
                for (int i = 0; i < groups.getLength(); i++) {
                    final Element g = (Element) groups.item(i);
                    final NodeList loot = g.getElementsByTagName("Loot");
                    for (int j = 0; j < loot.getLength(); j++) {
                        final Element l = (Element) loot.item(j);
                        final String itemName = l.getAttribute("ItemName");
                        final int[] parsed = splitMeta(itemName);
                        final String reg = itemName.substring(0, itemName.length() - parsed[1]);
                        final ItemStack stack = resolve(reg, parsed[0]);
                        w.println(
                            String.join(
                                ",",
                                q(g.getAttribute("GroupMetaID")),
                                q(g.getAttribute("GroupName")),
                                q(g.getAttribute("Rarity")),
                                q(g.getAttribute("MinItems")),
                                q(g.getAttribute("MaxItems")),
                                q(reg),
                                String.valueOf(parsed[0]),
                                q(stack == null ? "<id does not resolve in this build>" : display(stack)),
                                q(l.getAttribute("Amount")),
                                q(l.getAttribute("RandomAmount")),
                                q(l.getAttribute("Chance")),
                                q(l.getAttribute("ItemGroup")),
                                q(l.getAttribute("LimitedDropCount")),
                                q(l.getAttribute("NBTTag"))));
                        rows++;
                    }
                }
            }
            LOG.info("[probe][lootcsv] lootbags: {} groups, {} rows -> {}", groups.getLength(), rows, file);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] lootbags export failed", t);
        }
    }

    /**
     * LootBags writes an item as {@code mod:name} or {@code mod:name:meta}, and the name itself may contain spaces (for
     * example {@code Natura:Rare Sapling:1}). Only a trailing all-digit segment is a meta.
     *
     * @return {meta, number of characters to strip from the end of the string}
     */
    private static int[] splitMeta(String itemName) {
        final int last = itemName.lastIndexOf(':');
        if (last <= 0) return new int[] { 0, 0 };
        final String tail = itemName.substring(last + 1);
        if (tail.isEmpty()) return new int[] { 0, 0 };
        for (int i = 0; i < tail.length(); i++) if (!Character.isDigit(tail.charAt(i))) return new int[] { 0, 0 };
        return new int[] { Integer.parseInt(tail), tail.length() + 1 };
    }

    // ---------------------------------------------------------------- output

    public static void writeCombined(File out) {
        if (out == null) return;
        try {
            if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
            final File file = new File(out, "chestloot.csv");
            try (PrintWriter w = new PrintWriter(file, "UTF-8")) {
                w.println(HEADER);
                for (String r : ROWS) w.println(r);
            }
            LOG.info("[probe][lootcsv] wrote {} rows -> {}", ROWS.size(), file);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] combined write failed", t);
        }
    }

    /**
     * Base attack damage per (registry name, meta) for everything the tables can roll, so an offline
     * reader can work out a weapon's hit damage. Written as its own file rather than a column on
     * chestloot.csv: that schema already has consumers, and this is a per-item property rather than
     * a per-table-entry one — the same sword in eight categories would repeat it eight times.
     *
     * <p>
     * The number is the {@code attackDamage} attribute modifier, which is what the tooltip shows,
     * in health points. Hearts are half that. Tinkers' tools carry their own {@code InfiTool.Attack}
     * in NBT and do not need this.
     */
    public static void writeItemAttributes(File out) {
        if (out == null) return;
        try {
            if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
            final File file = new File(out, "item-attributes.csv");
            int n = 0;
            try (PrintWriter w = new PrintWriter(file, "UTF-8")) {
                w.println("registry_name,meta,attack_damage,armor,armor_slot,max_damage");
                final String attr = net.minecraft.entity.SharedMonsterAttributes.attackDamage
                    .getAttributeUnlocalizedName();
                for (final String key : new java.util.TreeSet<>(ATTR_KEYS)) {
                    final int tab = key.indexOf('\t');
                    if (tab <= 0) continue;
                    final String reg = key.substring(0, tab);
                    final int meta = Integer.parseInt(key.substring(tab + 1));
                    double dmg = 0;
                    boolean found = false; // true once either the attack or the armour lookup hits
                    try {
                        final ItemStack st = resolve(reg, meta);
                        if (st != null && st.getItem() != null) {
                            // Raw Multimap on purpose: getItemAttributeModifiers is declared raw-ish in 1.7.10
                            // and a wildcard capture rejects get(String).
                            @SuppressWarnings("rawtypes")
                            final com.google.common.collect.Multimap mm = st.getItem()
                                .getItemAttributeModifiers();
                            if (mm != null) {
                                for (final Object o : mm.get(attr)) {
                                    dmg += ((net.minecraft.entity.ai.attributes.AttributeModifier) o).getAmount();
                                    found = true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // an id this build does not ship, or an item whose modifiers throw — leave it blank
                    }
                    // Armour points and slot come off ItemArmor directly. Forge's ISpecialArmor items
                    // compute their protection at damage time and are NOT covered — they report their
                    // base field, which can understate. None of daily-707's loot armour is one, but a
                    // reader must not assume that holds for another pack.
                    String armor = "", slot = "", maxDmg = "";
                    try {
                        final ItemStack st2 = resolve(reg, meta);
                        if (st2 != null && st2.getItem() != null) {
                            if (st2.getItem() instanceof net.minecraft.item.ItemArmor) {
                                final net.minecraft.item.ItemArmor ia = (net.minecraft.item.ItemArmor) st2.getItem();
                                armor = String.valueOf(ia.damageReduceAmount);
                                slot = String.valueOf(ia.armorType);
                                found = true;
                            }
                            final int md = st2.getMaxDamage();
                            if (md > 0) maxDmg = String.valueOf(md);
                        }
                    } catch (Throwable ignored) {
                        // same tolerance as the attack lookup: an unresolvable id leaves the cells blank
                    }
                    if (!found) continue;
                    w.println(
                        String.join(
                            ",",
                            q(reg),
                            String.valueOf(meta),
                            dmg == 0 ? "" : String.format("%.4f", dmg),
                            armor,
                            slot,
                            maxDmg));
                    n++;
                }
            }
            LOG.info("[probe][lootcsv] wrote {} item attack values -> {}", n, file);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] item attribute write failed", t);
        }
    }

    /**
     * Enchantment id to name, so an offline reader can turn {@code ench:[{id:34,lvl:3}]} into
     * "Unbreaking III". Ids are assigned at registration and are pack- and version-specific — the
     * daily-707 tables use 43 distinct ids and only about half are vanilla — so the map has to come
     * from the running game rather than a hardcoded vanilla table.
     */
    public static void writeEnchantments(File out) {
        if (out == null) return;
        try {
            if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
            final File file = new File(out, "enchantments.csv");
            int n = 0;
            try (PrintWriter w = new PrintWriter(file, "UTF-8")) {
                w.println("id,name,translation_key,max_level,weight,type");
                for (final net.minecraft.enchantment.Enchantment e : net.minecraft.enchantment.Enchantment.enchantmentsList) {
                    if (e == null) continue;
                    String name;
                    try {
                        // getTranslatedName appends the level numeral; level 1 gives a trailing " I".
                        name = e.getTranslatedName(1);
                        if (name.endsWith(" I")) name = name.substring(0, name.length() - 2);
                    } catch (Throwable t) {
                        name = "<untranslated>";
                    }
                    String type;
                    try {
                        type = String.valueOf(e.type);
                    } catch (Throwable t) {
                        type = "";
                    }
                    w.println(
                        String.join(
                            ",",
                            String.valueOf(e.effectId),
                            q(name),
                            q(e.getName()),
                            String.valueOf(e.getMaxLevel()),
                            String.valueOf(e.getWeight()),
                            q(type)));
                    n++;
                }
            }
            LOG.info("[probe][lootcsv] wrote {} enchantments -> {}", n, file);
        } catch (Throwable t) {
            LOG.error("[probe][lootcsv] enchantment write failed", t);
        }
    }

    private static ItemStack resolve(String name, int meta) {
        try {
            final int c = name.indexOf(':');
            if (c <= 0) return null;
            final Item item = GameRegistry.findItem(name.substring(0, c), name.substring(c + 1));
            if (item == null) return null;
            return new ItemStack(item, 1, meta);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String display(ItemStack s) {
        try {
            return s.getDisplayName();
        } catch (Throwable t) {
            return "<name unavailable>";
        }
    }

    private static String row(String source, String phase, String table, String cat, String level, String rmin,
        String rmax, String each, ItemStack stack, int weight, int smin, int smax, int total, String cls) {
        String reg = "", disp = "", nbt = "";
        int meta = 0;
        try {
            final Object name = Item.itemRegistry.getNameForObject(stack.getItem());
            reg = name != null ? name.toString() : stack.getUnlocalizedName();
            meta = stack.getItemDamage();
            disp = stack.getDisplayName();
            if (stack.getTagCompound() != null) nbt = stack.getTagCompound()
                .toString();
        } catch (Throwable t) {
            // The table names an id that does not exist in this build. The entry still holds weight and still
            // consumes a roll, so it stays in the output as a blank rather than being dropped.
            disp = "<item id does not resolve in this build>";
        }
        if (!reg.isEmpty()) ATTR_KEYS.add(reg + "\t" + meta);
        return rowRaw(
            source,
            phase,
            table,
            cat,
            level,
            rmin,
            rmax,
            each,
            reg,
            meta,
            disp,
            String.valueOf(weight),
            String.valueOf(smin),
            String.valueOf(smax),
            String.valueOf(total),
            total > 0 ? String.format("%.6f", (double) weight / total) : "0",
            cls,
            nbt);
    }

    private static String rowRaw(String source, String phase, String table, String cat, String level, String rmin,
        String rmax, String each, String reg, int meta, String disp, String weight, String smin, String smax,
        String total, String chance, String cls, String nbt) {
        return rowRaw(
            source,
            phase,
            table,
            cat,
            level,
            rmin,
            rmax,
            each,
            reg,
            meta,
            disp,
            weight,
            smin,
            smax,
            total,
            chance,
            cls,
            nbt,
            "");
    }

    private static String rowRaw(String source, String phase, String table, String cat, String level, String rmin,
        String rmax, String each, String reg, int meta, String disp, String weight, String smin, String smax,
        String total, String chance, String cls, String nbt, String enchLevel) {
        // Recorded here rather than only in row(): the Roguelike and TF captures parse names out of JSON and
        // never build an ItemStack, so hooking the ItemStack overload alone misses every weapon they list.
        if (reg != null && !reg.isEmpty()) ATTR_KEYS.add(reg + "\t" + meta);
        return String.join(
            ",",
            q(source),
            q(phase),
            q(table),
            q(cat),
            q(level),
            q(rmin),
            q(rmax),
            q(each),
            q(disp),
            q(weight),
            q(smin),
            q(smax),
            q(total),
            q(chance),
            q(reg),
            String.valueOf(meta),
            q(cls),
            q(nbt),
            q(enchLevel));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ChestGenHooks> chestInfo() throws Exception {
        final java.lang.reflect.Field f = ChestGenHooks.class.getDeclaredField("chestInfo");
        f.setAccessible(true);
        return (Map<String, ChestGenHooks>) f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<WeightedRandomChestContent> contents(ChestGenHooks hooks) throws Exception {
        final java.lang.reflect.Field f = ChestGenHooks.class.getDeclaredField("contents");
        f.setAccessible(true);
        return (List<WeightedRandomChestContent>) f.get(hooks);
    }

    /** Every field is quoted, so a display name containing a comma survives a spreadsheet import. */
    private static String q(String s) {
        if (s == null) s = "";
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
