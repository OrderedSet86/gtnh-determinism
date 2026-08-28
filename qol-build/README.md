# GTNH Speedrun QoL

Client-side quality-of-life fixes for GTNH speedrunning. Minecraft 1.7.10, mod id `gtnhspeedrunqol`.

## Scope rule

A fix belongs in this jar only if it removes interface friction. Nothing here may change what the game
simulates: no world generation, no recipes, no drops, no player capabilities. A run that uses this jar must stay
comparable to a run that does not.

Worldgen determinism work lives in the separate `gtnhdeterminism` jar, built from `../fix-build`. The two jars
are independent. Install either one alone, or both.

## Fixes

### Block-breaking progress survives a held-stack swap

Vanilla restarts the block you are mining when the ItemStack in your held hotbar slot is replaced or mutated,
even though you did not switch slots. Picking up an item mid-dig is the common trigger, and GTNH makes it more
common than vanilla: Backhand overrides `InventoryPlayer.storeItemStack` to prefer the held slot, so pickups
merge into the held stack far more often.

Two distinct causes, both fixed:

* Mining bare-handed, the vanilla comparison returns false on any null to non-null transition, so picking
  anything up resets the block. This is Mojira MC-2588, closed Works As Intended, still present in 1.21.
* `clickBlock` stores an alias of the held stack rather than a copy. An item pickup makes the server send
  `S2FPacketSetSlot`, and the client installs a fresh ItemStack in the slot. The alias is then a stale snapshot,
  so later NBT drift — GregTech `GT.ToolStats` damage, `GT.ItemCharge` — fails the tag comparison and resets.

Progress is kept only while the hotbar slot is unchanged. A deliberate slot switch still defers to vanilla,
which preserves the MC-2519 anti-exploit: swapping to a cheap pickaxe just before the block breaks still resets,
so the cheap tool cannot absorb the durability cost.

Read `src/main/java/com/gtnhspeedrun/qol/mixins/PlayerControllerMPMixin.java` for the full mechanism.

## Switches

The mod has no config file. Each switch is a system property read once at class load, and each defaults to ON,
so an A/B run only needs the flag on the control side.

| Property | Effect |
| --- | --- |
| `-Dgtnhqol.nokeepmining=true` | Disable the block-breaking fix; restore vanilla behaviour. |
| `-Dgtnhqol.tracemining=true` | Log every keep-or-defer decision, prefixed `[miningtrace]`. |

## Building

Build through the repo script, which builds clean, verifies that every source class reached the jar, and prints
the md5:

```bash
../scripts/build-jar.sh qol
```
