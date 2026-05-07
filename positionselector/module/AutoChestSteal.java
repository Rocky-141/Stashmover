package com.positionselector.module;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class AutoChestSteal extends Module {

    public static final Category STORAGE = new Category("Storage");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Block filters (smooth, unlimited)
    private final Setting<List<Block>> blockFilters = sgGeneral.add(new BlockListSetting.Builder()
        .name("block-filters")
        .description("Blocks to steal from containers.")
        .defaultValue(List.of())
        .build()
    );

    // Custom name filters (smooth, unlimited)
    private final Setting<List<String>> nameFilters = sgGeneral.add(new StringListSetting.Builder()
        .name("custom-name-filters")
        .description("items with custom names to steal")
        .defaultValue(List.of())
        .build()
    );

    // Delay slider
    private final Setting<Integer> takeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("take-delay-ms")
        .description("Delay between taking each item.")
        .defaultValue(120)
        .min(0).max(2000)
        .sliderMin(0).sliderMax(2000)
        .build()
    );

    private boolean looting = false;
    private long lastTakeTime = 0;

    public AutoChestSteal() {
        super(STORAGE, "chest-steal", "Automatically takes specific items from any container ");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            looting = false;
            return;
        }

        // Ignore player inventory
        if (screen.getTitle().getString().equals("Inventory")) {
            looting = false;
            return;
        }

        if (looting) return;
        looting = true;

        lootContainer(screen);
    }

    private void lootContainer(HandledScreen<?> screen) {
        int totalSlots = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;

        new Thread(() -> {
            try {
                while (isActive() && mc.currentScreen == screen) {
                    boolean tookSomething = false;

                    for (int slot = 0; slot < playerInvStart; slot++) {
                        if (!isActive() || mc.currentScreen != screen) return;

                        long now = System.currentTimeMillis();
                        if (now - lastTakeTime < takeDelay.get()) {
                            Thread.sleep(takeDelay.get());
                        }

                        var stack = screen.getScreenHandler().slots.get(slot).getStack();
                        if (stack.isEmpty()) continue;

                        // Extract item name
                        String itemName = stack.getName().getString();

                        // Extract block if applicable
                        Block block = null;
                        if (stack.getItem() instanceof BlockItem blockItem) {
                            block = blockItem.getBlock();
                        }

                        // Check filters
                        if (!matchesFilters(block, itemName)) continue;

                        // Take item
                        mc.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId,
                            slot,
                            0,
                            SlotActionType.QUICK_MOVE,
                            mc.player
                        );

                        lastTakeTime = System.currentTimeMillis();
                        tookSomething = true;
                    }

                    if (!tookSomething) {
                        Thread.sleep(takeDelay.get());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                looting = false;
            }
        }).start();
    }

    private boolean matchesFilters(Block block, String itemName) {
        // Custom name filters (highest priority)
        for (String name : nameFilters.get()) {
            if (itemName.equals(name)) return true;
        }

        // Block filters
        if (block != null && blockFilters.get().contains(block)) return true;

        return false;
    }
}
