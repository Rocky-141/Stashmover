package com.positionselector.mixin;

import com.positionselector.module.PositionSelectorModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts ClientPlayerInteractionManager#attackBlock.
 *
 * When PositionSelectorModule is enabled and waiting for a block click,
 * the first left-click is handed to the module and vanilla block-damage
 * is suppressed for that single call.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class PositionSelectorInteractionMixin {

    @Inject(
        method = "attackBlock(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onAttackBlock(BlockPos pos, Direction direction,
                               CallbackInfoReturnable<Boolean> cir) {

        PositionSelectorModule mod = Modules.get().get(PositionSelectorModule.class);
        if (mod == null || !mod.isActive() || !mod.isCapturing()) return;

        boolean consumed = mod.onBlockAttack(pos);
        if (consumed) {
            cir.setReturnValue(false);   // suppress vanilla block damage
            cir.cancel();
        }
    }
}
