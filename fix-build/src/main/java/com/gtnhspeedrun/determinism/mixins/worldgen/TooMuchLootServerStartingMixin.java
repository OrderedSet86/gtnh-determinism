package com.gtnhspeedrun.determinism.mixins.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.GtnhDeterminism;
import com.gtnhspeedrun.determinism.worldgen.EarlyLootTables;

import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * F9 second half: suppress TooMuchLoot's own application once {@link EarlyLootTables} has already run it.
 *
 * <p>
 * This is required for correctness, not tidiness. {@code ChestLootLoader.loadFiles} applies three loading modes.
 * {@code OVERRIDE} is idempotent — it {@code putAll}s freshly built {@code ChestGenHooks} objects into the static
 * registry, so a second pass produces the same table. {@code ADD} is not: it calls
 * {@code ChestGenHooks.getInfo(category).addItem(...)} against the live table, so a second pass duplicates every
 * added entry and silently doubles those items' weights. GTNH daily-707 ships 16 groups, all {@code OVERRIDE}, but
 * a pack or user XML using {@code ADD} would be corrupted by a double application.
 *
 * <p>
 * The {@code /chestloot} command is still registered here, because it needs the event object and is the only part
 * of {@code serverStarting} that {@link EarlyLootTables} cannot do. If the early application did not happen — no
 * TooMuchLoot, a first-run empty loot folder, or any reflective failure — {@code consumeApplied()} is false and
 * the stock handler runs untouched.
 */
@Mixin(targets = "dmillerw.tml.TooMuchLoot", remap = false)
public class TooMuchLootServerStartingMixin {

    @Inject(method = "serverStarting", at = @At("HEAD"), cancellable = true, require = 1)
    private void gtnhdet$skipDuplicateApply(FMLServerStartingEvent event, CallbackInfo ci) {
        if (!EarlyLootTables.consumeApplied()) return;
        try {
            event.registerServerCommand(
                (net.minecraft.command.ICommand) Class.forName("dmillerw.tml.command.CommandChestLoot")
                    .getConstructor()
                    .newInstance());
        } catch (Throwable t) {
            GtnhDeterminism.LOG.warn("Could not register TooMuchLoot's /chestloot command: {}", t.toString());
        }
        GtnhDeterminism.LOG.info("TooMuchLoot already applied before world load — skipping its duplicate run");
        ci.cancel();
    }
}
