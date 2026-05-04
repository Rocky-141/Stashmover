package com.positionselector.mixin;

// This mixin is intentionally empty - Esc no longer cancels capture.
// Kept as a placeholder so the mixin config reference remains valid.

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Keyboard.class)
public class PositionSelectorKeyboardMixin {
    // No Esc interception - capture only ends when player left-clicks a block.
}
