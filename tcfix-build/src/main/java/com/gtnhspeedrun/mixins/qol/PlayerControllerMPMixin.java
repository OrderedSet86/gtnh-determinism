package com.gtnhspeedrun.mixins.qol;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.item.ItemStack;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.qol.QolConfig;

/**
 * Stop block-breaking progress resetting when the ItemStack in the held hotbar slot is replaced or mutated
 * underneath the player.
 *
 * <p>
 * {@code clickBlock} stores {@code currentItemHittingBlock = getHeldItem()}, an alias rather than a copy, and
 * {@code sameToolAndBlock} compares the live held stack against it. Two cases fail:
 *
 * <ul>
 * <li><b>Empty hand.</b> The comparison seeds {@code flag} as "both null" and only runs the value comparison when
 * both sides are non-null, so a null to non-null transition returns false. Picking anything up while mining
 * bare-handed therefore resets. This is Mojira MC-2588, closed Works As Intended, still present in 1.21.</li>
 * <li><b>Stale alias.</b> While the alias holds, in-place NBT mutation is invisible because both sides are the
 * same object. An item pickup makes the server run {@code Container.detectAndSendChanges}, which compares with
 * {@code areItemStacksEqual} (that one does compare stackSize) and sends S2FPacketSetSlot; the client's
 * {@code handleSetSlot} calls {@code putStackInSlot}, which installs a fresh ItemStack in the held slot. The
 * alias is now a stale snapshot, so later NBT drift — GregTech GT.ToolStats damage, GT.ItemCharge — fails
 * {@code areItemStackTagsEqual} and resets.</li>
 * </ul>
 *
 * <p>
 * Note that stackSize itself is not compared and has not been since 13w03a (MC-3449), so a plain 1 to 2 merge on
 * an item without NBT never reset in the first place.
 *
 * <p>
 * The comparison is only relaxed while the hotbar slot is unchanged, so a deliberate switch still defers to
 * vanilla and the MC-2519 anti-exploit survives: swapping to a cheap pickaxe just before the block breaks still
 * resets, and so the cheap tool cannot absorb the durability cost. Keying on the slot rather than on the stack
 * matters for GregTech, because {@code MetaGeneratedTool.getToolWithStats} encodes the tool TYPE in metadata and
 * the MATERIAL in NBT — a bronze and a tungstensteel pickaxe are the same Item with the same metadata, and only
 * the slot check can tell them apart.
 *
 * <p>
 * Disable with {@code -Dgtnhdet.nokeepmining=true}.
 */
@Mixin(PlayerControllerMP.class)
public abstract class PlayerControllerMPMixin {

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private int currentBlockX;

    @Shadow
    private int currentBlockY;

    /** Lowercase 'b' is the real MCP name, not a typo. */
    @Shadow
    private int currentblockZ;

    @Shadow
    private ItemStack currentItemHittingBlock;

    /** Hotbar index selected when the current dig started. -1 is never a valid index, so it fails closed. */
    @Unique
    private int tcfix$hitSlot = -1;

    /**
     * currentBlockX/Y/Z are written three instructions above this PUTFIELD and nowhere else, and
     * onPlayerDestroyBlock invalidates by setting currentBlockY = -1. Recording here therefore guarantees that
     * tcfix$hitSlot is in sync with the recorded position whenever the position check below passes. TAIL would
     * not have that property: it also fires when clickBlock short-circuits on sameToolAndBlock, which would
     * re-arm the slot to whatever is held now and defeat the check.
     */
    @Inject(
        method = "clickBlock(IIII)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;currentItemHittingBlock:Lnet/minecraft/item/ItemStack;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER),
        require = 1)
    private void tcfix$recordHitSlot(int x, int y, int z, int side, CallbackInfo ci) {
        this.tcfix$hitSlot = this.mc.thePlayer.inventory.currentItem;
        QolConfig.traceMining("dig START at {},{},{} slot={}", x, y, z, this.tcfix$hitSlot);
    }

    @Inject(method = "sameToolAndBlock(III)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void tcfix$keepProgressAcrossStackSwap(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!QolConfig.KEEP_MINING_PROGRESS) return;

        final EntityClientPlayerMP player = this.mc.thePlayer;
        if (player == null) return;

        // Different block: vanilla already answers false.
        if (x != this.currentBlockX || y != this.currentBlockY || z != this.currentblockZ) return;

        // Genuine hotbar switch: defer to vanilla, which is what preserves the anti-exploit.
        if (player.inventory.currentItem != this.tcfix$hitSlot) return;

        final ItemStack held = player.getHeldItem();
        final ItemStack snapshot = this.currentItemHittingBlock;

        if (snapshot != null) {
            // Tool broke or was consumed mid-dig. Vanilla resets and there is no bug to fix here.
            if (held == null) {
                QolConfig.traceMining("defer: held became null, snapshot={}", snapshot.getUnlocalizedName());
                return;
            }
            // A mod swapped one item for another in place. Be conservative.
            if (held.getItem() != snapshot.getItem()) {
                QolConfig.traceMining(
                    "defer: item changed in slot {} -> {}",
                    snapshot.getUnlocalizedName(),
                    held.getUnlocalizedName());
                return;
            }
            // Non-damageable items are distinguished by metadata, and every GregTech tool is maxDamage 0
            // (MetaBaseItem calls setMaxDamage(0)), so this is the GT tool-type guard.
            if (!held.isItemStackDamageable() && held.getItemDamage() != snapshot.getItemDamage()) {
                QolConfig
                    .traceMining("defer: metadata changed {} -> {}", snapshot.getItemDamage(), held.getItemDamage());
                return;
            }
        }

        // Same slot, same logical item, or the dig started bare-handed. The stack object was swapped or its NBT
        // ticked underneath us. Refresh the alias so the snapshot is current for the next tick, then keep going.
        if (snapshot != held) {
            QolConfig.traceMining(
                "KEEP progress: slot {} stack swapped {} -> {}",
                this.tcfix$hitSlot,
                snapshot == null ? "empty" : snapshot.getUnlocalizedName() + " x" + snapshot.stackSize,
                held == null ? "empty" : held.getUnlocalizedName() + " x" + held.stackSize);
        }
        this.currentItemHittingBlock = held;
        cir.setReturnValue(true);
    }
}
