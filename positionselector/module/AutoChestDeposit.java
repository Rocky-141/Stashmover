package com.positionselector.module;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class AutoChestDeposit extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Block>> blockFilters = sgGeneral.add(new BlockListSetting.Builder()
        .name("block-filters")
        .description("Blocks to deposit into containers.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<List<String>> nameFilters = sgGeneral.add(new StringListSetting.Builder()
        .name("custom-name-filters")
        .description("Items with these custom names will be deposited regardless of block type.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Integer> depositDelay = sgGeneral.add(new IntSetting.Builder()
        .name("deposit-delay-ms")
        .description("Delay between depositing each item.")
        .defaultValue(120)
        .min(0).max(2000)
        .sliderMin(0).sliderMax(2000)
        .build()
    );

    private boolean depositing = false;
    private long lastDepositTime = 0;

    public AutoChestDeposit() {
        super(AutoChestSteal.STORAGE, "chest-deposit",
            "deposits items into containers");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            depositing = false;
            return;
        }

        if (screen.getTitle().getString().equals("Inventory")) {
            depositing = false;
            return;
        }

        if (depositing) return;
        depositing = true;

        depositIntoContainer(screen);
    }

    private void depositIntoContainer(HandledScreen<?> screen) {
        int totalSlots = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;

        new Thread(() -> {
            try {
                while (isActive() && mc.currentScreen == screen) {
                    boolean depositedSomething = false;

                    for (int slot = playerInvStart; slot < totalSlots; slot++) {
                        if (!isActive() || mc.currentScreen != screen) return;

                        long now = System.currentTimeMillis();
                        if (now - lastDepositTime < depositDelay.get()) {
                            Thread.sleep(depositDelay.get());
                        }

                        var stack = screen.getScreenHandler().slots.get(slot).getStack();
                        if (stack.isEmpty()) continue;

                        String itemName = stack.getName().getString();

                        Block block = null;
                        if (stack.getItem() instanceof BlockItem blockItem) {
                            block = blockItem.getBlock();
                        }

                        if (!matchesFilters(block, itemName)) continue;

                        mc.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId,
                            slot,
                            0,
                            SlotActionType.QUICK_MOVE,
                            mc.player
                        );

                        lastDepositTime = System.currentTimeMillis();
                        depositedSomething = true;
                    }

                    if (!depositedSomething) {
                        Thread.sleep(depositDelay.get());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                depositing = false;
            }
        }).start();
    }

    private boolean matchesFilters(Block block, String itemName) {
        for (String name : nameFilters.get()) {
            if (itemName.equals(name)) return true;
        }

        if (block != null && blockFilters.get().contains(block)) return true;

        return false;
    }
}
