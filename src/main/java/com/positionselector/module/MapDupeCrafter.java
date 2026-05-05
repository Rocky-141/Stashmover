package test.StashMover.module;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import static test.StashMover.StashMoverAddon.CATEGORY;

public class MapDupeCrafter extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelay = settings.createGroup("Delay");

    private final Setting<Integer> targetAmount = sgGeneral.add(new IntSetting.Builder()
        .name("amount")
        .description("How many maps to craft.")
        .defaultValue(16)
        .min(2)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> tickDelaySetting = sgDelay.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between each action.")
        .defaultValue(3)
        .min(2)
        .sliderMax(20)
        .build()
    );

    private enum State {
        WAITING_FOR_TABLE,
        SHIFT_FILLED,
        SHIFT_EMPTY,
        SHIFT_RESULT,
        SHIFT_RESULT_BACK
    }

    private State state = State.WAITING_FOR_TABLE;
    private int crafted = 0;
    private int tickDelay = 0;

    public MapDupeCrafter() {
        super(AutoChestSteal.STORAGE, "auto-macro",
            "Automatically performs a series of actions in response to chat messages or other triggers.");
    }
            
    @Override
    public void onActivate() {
        crafted = 0;
        tickDelay = 0;
        state = State.WAITING_FOR_TABLE;
        info("Open a crafting table to start crafting.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (state == State.WAITING_FOR_TABLE) {
            if (mc.currentScreen instanceof CraftingScreen) {
                state = State.SHIFT_FILLED;
                info("Crafting (highlight)%d(default) maps...", targetAmount.get());
            }
            return;
        }

        if (!(mc.currentScreen instanceof CraftingScreen)) {
            info("Crafting table closed, pausing.");
            state = State.WAITING_FOR_TABLE;
            return;
        }

        if (crafted >= targetAmount.get()) {
            info("Done! Crafted (highlight)%d(default) maps.", crafted);
            toggle();
            return;
        }

        tickDelay++;
        if (tickDelay < tickDelaySetting.get()) return;
        tickDelay = 0;

        CraftingScreenHandler handler = (CraftingScreenHandler) mc.player.currentScreenHandler;

        switch (state) {
            case SHIFT_FILLED -> {
                // Find filled map in inventory and shift click it into crafting grid
                int slot = findItem(handler, Items.FILLED_MAP);
                if (slot == -1) { error("No filled map found."); toggle(); return; }
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                state = State.SHIFT_EMPTY;
            }

            case SHIFT_EMPTY -> {
                // Find empty map in inventory and shift click it into crafting grid
                int slot = findItem(handler, Items.MAP);
                if (slot == -1) { error("No empty maps left."); toggle(); return; }
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                state = State.SHIFT_RESULT;
            }

            case SHIFT_RESULT -> {
                // Wait for result to appear then shift click it
                if (handler.getSlot(0).getStack().isEmpty()) return;
                mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                crafted++;
                state = State.SHIFT_RESULT_BACK;
            }

            case SHIFT_RESULT_BACK -> {
                // Shift remaining items in crafting grid back to inventory
                boolean anyLeft = false;
                for (int i = 1; i <= 9; i++) {
                    if (!handler.getSlot(i).getStack().isEmpty()) {
                        mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                        anyLeft = true;
                        break;
                    }
                }
                // Once grid is clear, start next craft
                if (!anyLeft) state = State.SHIFT_FILLED;
            }
        }
    }

    // Search player inventory slots only (10 onwards in crafting table)
    private int findItem(CraftingScreenHandler handler, net.minecraft.item.Item item) {
        for (int i = 10; i < handler.slots.size(); i++) {
            var stack = handler.getSlot(i).getStack();
            if (stack.getItem() == item && !stack.isEmpty()) return i;
        }
        return -1;
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        if (!(mc.currentScreen instanceof CraftingScreen)) return;
        CraftingScreenHandler handler = (CraftingScreenHandler) mc.player.currentScreenHandler;
        for (int i = 1; i <= 9; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }
    }
}