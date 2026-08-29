package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Collection;
import java.util.Random;

import net.minecraft.util.WeightedRandom;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The initial animals of a world are a different set on every launch.
 * <p>
 * {@code SpawnerAnimals.performWorldGenSpawning} receives the populate-seeded {@code Random} as its last parameter
 * and uses it for the group count, the spawn column, the scatter walk and the rotation — but picks the
 * <em>species</em> off {@code world.rand}:
 *
 * <pre>
 * while (populateRand.nextFloat() &lt; biome.getSpawningChance()) {
 *     SpawnListEntry e = WeightedRandom.getRandomItem(world.rand, list);   // &lt;-- wrong Random
 *     int count = e.minGroupCount + populateRand.nextInt(...);
 * </pre>
 *
 * {@code World.rand} is a bare {@code new Random()} (BugTorch leaves it alone: {@code replaceRandomInWorld=false} in
 * both pack lines), so it is clock-seeded. Which animals a seed starts with — and therefore the first leather, wool
 * and food on the route — is not a function of the seed at all. This is the same shadowing shape as the TiC slime
 * island bug fixed in {@link SlimeIslandGenMixin}, and it has been invisible to every probe run so far because
 * entities are not blocks.
 * <p>
 * Redirecting the species pick onto the populate Random makes it seed-pure. Note this also shifts both RNG streams
 * relative to stock, so animal populations are re-rolled rather than merely stabilised; that is a balance change and
 * is covered by the stock-vs-fixed equivalence harness.
 * <p>
 * Priority is above default because Hodgepodge's {@code MixinSpawnerAnimals_optimizeSpawning} {@code @Overwrite}s
 * this method. Its replacement body reproduces the bug verbatim (bytecode: {@code getfield World.field_73012_v}
 * feeding {@code WeightedRandom.func_76271_a}), so the redirect is correct against either body — but it must apply
 * after the overwrite, not before, or it would be discarded. ArchaicFix already redirects into the same overwritten
 * method, so the arrangement is known to work in this pack.
 */
@Mixin(value = SpawnerAnimals.class, priority = 1500)
public class SpawnerAnimalsMixin {

    @Redirect(
        method = "performWorldGenSpawning",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/WeightedRandom;getRandomItem(Ljava/util/Random;Ljava/util/Collection;)Lnet/minecraft/util/WeightedRandom$Item;"))
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static WeightedRandom.Item gtnhdet$speciesFromPopulateRand(Random worldRand, Collection list, World world,
        BiomeGenBase biome, int x, int z, int width, int depth, Random populateRand) {
        return WeightedRandom.getRandomItem(populateRand, list);
    }
}
