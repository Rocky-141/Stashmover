package com.positionselector.module;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoStashSteal extends Module {

    private static class SlotData {
        Vec3d playerPos;
        BlockPos targetBlock;
        boolean isSet() { return playerPos != null && targetBlock != null; }
    }

    private final SlotData[] slotData = new SlotData[4];
    private int configuringSlot = -1;

    private enum ExecState { IDLE, MOVING, ROTATING, INTERACTING, WAITING_FOR_GUI, INVENTORY, CLOSING }
    private ExecState execState = ExecState.IDLE;

    private int sequenceStep      = -1;
    private int sourceSlot        = 1;
    private boolean checkedSlot1Empty = false;
    private boolean slot1WasEmpty     = false;
    private int matchingStackCount    = 0;

    private static final int TOTAL_STEPS = 5;

    private int rotateTicker        = 0;
    private int inventorySlotIndex  = 0;
    private int inventoryTicker     = 0;
    private int inventoryOpenTicker = 0;
    private int closeTicker         = 0;
    private int movingTicker        = 0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Filters");
    private final SettingGroup sgSlots   = settings.createGroup("Slots");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<List<Block>>  blockFilters;
    private final Setting<List<String>> nameFilters;
    private final Setting<Integer>      guiOpenDelay;
    private final Setting<Integer>      itemDelay;
    private       Setting<Boolean>      startSetting;
    private final Setting<SettingColor> color1;
    private final Setting<SettingColor> color2;

    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] setButtons     = new Setting[4];
    @SuppressWarnings("unchecked")
    private final Setting<String>[]  statusSettings = new Setting[4];

    public AutoStashSteal() {
        super(AutoChestSteal.STORAGE, "auto-stash-steal", "Moves items between stash positions.");


        for (int i = 0; i < slotData.length; i++) slotData[i] = new SlotData();

        blockFilters = sgFilters.add(new BlockListSetting.Builder()
            .name("block-filters")
            .description("Block types to take/deposit.")
            .defaultValue(List.of())
            .build()
        );

        nameFilters = sgFilters.add(new StringListSetting.Builder()
            .name("name-filters")
            .description("Item custom names to take/deposit.")
            .defaultValue(List.of())
            .build()
        );

        guiOpenDelay = sgGeneral.add(new IntSetting.Builder()
            .name("gui-open-delay")
            .description("Ticks to wait after container opens before moving items (20 = 1s).")
            .defaultValue(10).min(0).sliderMax(100)
            .build()
        );

        itemDelay = sgGeneral.add(new IntSetting.Builder()
            .name("item-delay")
            .description("Ticks to wait between each item move (20 = 1s).")
            .defaultValue(2).min(0).sliderMax(40)
            .build()
        );

        startSetting = sgGeneral.add(new BoolSetting.Builder()
            .name("start")
            .description("Start the stash mover sequence.")
            .defaultValue(false)
            .onChanged(v -> { if (v) beginSequence(); })
            .build()
        );

        for (int slot : new int[]{1, 2, 3}) {
            final int s = slot;

            statusSettings[slot] = sgSlots.add(new StringSetting.Builder()
                .name("slot-" + slot + "-status")
                .description("Slot " + slot + (slot == 3 ? " (backup chest)" : "") + " status.")
                .defaultValue("Not set")
                .build()
            );

            setButtons[slot] = sgSlots.add(new BoolSetting.Builder()
                .name("set-slot-" + slot)
                .description("Left-click a block to set slot " + slot + ".")
                .defaultValue(false)
                .onChanged(v -> {
                    if (!v) return;
                    if (slotData[s].isSet()) {
                        sendChat("Slot " + s + " is already set. Disable and re-enable to reset.");
                        setButtons[s].set(false);
                        return;
                    }
                    configuringSlot = s;
                    sendChat("Left-click a block to set slot " + s + ".");
                })
                .build()
            );
        }

        color1 = sgRender.add(new ColorSetting.Builder()
            .name("color-1")
            .description("Color for slot 1 and 3 boxes.")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .build()
        );

        color2 = sgRender.add(new ColorSetting.Builder()
            .name("color-2")
            .description("Color for slot 2 boxes.")
            .defaultValue(new SettingColor(255, 0, 0, 80))
            .build()
        );
    }

    @Override
    public void onActivate() {
        execState = ExecState.IDLE;
        sequenceStep = -1;
        configuringSlot = -1;
        for (int i = 0; i < slotData.length; i++) slotData[i] = new SlotData();
        for (int slot : new int[]{1, 2, 3}) {
            statusSettings[slot].set("Not set");
            setButtons[slot].set(false);
        }
    }

    @Override
    public void onDeactivate() {
        sendChatCommand("#stop");
        execState = ExecState.IDLE;
        sequenceStep = -1;
        configuringSlot = -1;
    }

    // --- Sequence ---

    private void beginSequence() {
        if (sequenceStep != -1) return;
        sequenceStep = 0;
        sourceSlot = 1;
        checkedSlot1Empty = false;
        slot1WasEmpty = false;
        goToSource();
    }

    private void goToSource() { moveTo(sourceSlot); }

    private void moveTo(int slot) {
        SlotData data = slotData[slot];
        if (!data.isSet()) {
            sendChat("Slot " + slot + " is not set! Stopping.");
            stopSequence();
            return;
        }
        int x = (int) data.playerPos.x;
        int y = (int) data.playerPos.y;
        int z = (int) data.playerPos.z;
        sendChat("Moving to slot " + slot + "...");
        sendChatCommand("#goto " + x + " " + y + " " + z);
        movingTicker = 0;
        execState = ExecState.MOVING;
    }

    private void stopSequence() {
        sequenceStep = -1;
        execState = ExecState.IDLE;
        startSetting.set(false);
        sendChatCommand("#stop");
    }

    private void advanceStep() {
        sequenceStep++;
        if (sequenceStep >= TOTAL_STEPS) {
            sendChat("Complete.");
            stopSequence();
            return;
        }
        if (sequenceStep % 2 == 0) {
            sourceSlot = 1;
            checkedSlot1Empty = false;
            slot1WasEmpty = false;
            goToSource();
        } else {
            moveTo(2);
        }
    }

    // --- Tick ---

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        switch (execState) {
            case MOVING          -> handleMoving();
            case ROTATING        -> handleRotating();
            case INTERACTING     -> handleInteracting();
            case WAITING_FOR_GUI -> handleWaitingForGui();
            case INVENTORY       -> handleInventory();
            case CLOSING         -> handleClosing();
            default              -> {}
        }
    }

    private void handleMoving() {
        Vec3d dest = lastCommandedPos();
        if (dest == null) return;
        double dx = mc.player.getX() - dest.x;
        double dy = mc.player.getY() - dest.y;
        double dz = mc.player.getZ() - dest.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        movingTicker++;
        if (dist < 1.5 || movingTicker > 600) {
            sendChatCommand("#stop");
            rotateTicker = 0;
            execState = ExecState.ROTATING;
        }
    }

    private Vec3d lastCommandedPos() {
        if (sequenceStep >= 0 && sequenceStep < TOTAL_STEPS) {
            int slot = (sequenceStep % 2 == 0) ? sourceSlot : 2;
            return slotData[slot] != null ? slotData[slot].playerPos : null;
        }
        return null;
    }

    private void handleRotating() {
        int slot = (sequenceStep % 2 == 0) ? sourceSlot : 2;
        rotateToFace(slotData[slot].targetBlock);
        rotateTicker++;
        if (rotateTicker >= 5) execState = ExecState.INTERACTING;
    }

    private void handleInteracting() {
        int slot = (sequenceStep % 2 == 0) ? sourceSlot : 2;
        BlockPos target = slotData[slot].targetBlock;
        mc.interactionManager.interactBlock(
            mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false)
        );
        inventoryOpenTicker = 0;
        execState = ExecState.WAITING_FOR_GUI;
    }

    private void handleWaitingForGui() {
        inventoryOpenTicker++;
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            if (inventoryOpenTicker > 40) {
                sendChat("Container did not open, skipping step.");
                advanceStep();
            }
            return;
        }

        if (inventoryOpenTicker < guiOpenDelay.get() + 1) return;

        // Count matching stacks in slot 1 before taking
        if (sequenceStep % 2 == 0 && sourceSlot == 1 && !checkedSlot1Empty) {
            int totalSlots     = screen.getScreenHandler().slots.size();
            int playerInvStart = totalSlots - 36;
            matchingStackCount = 0;
            for (int i = 0; i < playerInvStart; i++) {
                var stack = screen.getScreenHandler().slots.get(i).getStack();
                if (!stack.isEmpty() && matchesFilters(stack)) matchingStackCount++;
            }
            if (matchingStackCount < 10) {
                sendChat("Slot 1 has less than 10 stacks (" + matchingStackCount + ") — going to slot 3.");
                mc.player.closeHandledScreen();
                if (slotData[3].isSet()) {
                    sourceSlot = 3;
                    checkedSlot1Empty = true;
                    moveTo(3);
                } else {
                    sendChat("Slot 3 not set. Stopping.");
                    stopSequence();
                }
                return;
            }
        }

        inventorySlotIndex = 0;
        inventoryTicker    = 0;
        slot1WasEmpty      = true;
        execState = ExecState.INVENTORY;
    }

    private void handleInventory() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            sendChat("Container closed unexpectedly.");
            advanceStep();
            return;
        }

        if (inventoryTicker > 0) { inventoryTicker--; return; }

        int totalSlots     = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;
        boolean isTaking   = (sequenceStep % 2 == 0);

        if (isTaking) {
            while (inventorySlotIndex < playerInvStart) {
                int current = inventorySlotIndex++;
                var stack = screen.getScreenHandler().slots.get(current).getStack();
                if (stack.isEmpty()) continue;
                if (!matchesFilters(stack)) continue;
                slot1WasEmpty = false;
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, current, 0, SlotActionType.QUICK_MOVE, mc.player);
                inventoryTicker = itemDelay.get();
                return;
            }
        } else {
            while (inventorySlotIndex < 36) {
                int current = playerInvStart + inventorySlotIndex++;
                var stack = screen.getScreenHandler().slots.get(current).getStack();
                if (stack.isEmpty()) continue;
                if (!matchesFilters(stack)) continue;
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, current, 0, SlotActionType.QUICK_MOVE, mc.player);
                inventoryTicker = itemDelay.get();
                return;
            }
        }

        closeTicker = 0;
        execState = ExecState.CLOSING;
    }

    private void handleClosing() {
        closeTicker++;
        if (closeTicker >= 3) {
            mc.player.closeHandledScreen();

            // If slot 1 was empty and we haven't tried slot 3 yet
            if (sequenceStep % 2 == 0 && sourceSlot == 1 && slot1WasEmpty && !checkedSlot1Empty) {
                checkedSlot1Empty = true;
                if (slotData[3].isSet()) {
                    sendChat("Slot 1 empty — trying backup slot 3.");
                    sourceSlot = 3;
                    moveTo(3);
                } else {
                    sendChat("Slot 1 empty and slot 3 not set. Stopping.");
                    stopSequence();
                }
                return;
            }

            advanceStep();
        }
    }

    // --- Filters ---

    private boolean matchesFilters(net.minecraft.item.ItemStack stack) {
        if (blockFilters.get().isEmpty() && nameFilters.get().isEmpty()) return true;
        String itemName = stack.getName().getString();
        for (String name : nameFilters.get()) {
            if (itemName.equals(name)) return true;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            if (blockFilters.get().contains(blockItem.getBlock())) return true;
        }
        return false;
    }

    // --- Packet: set slot on left click ---

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (configuringSlot == -1) return;
        if (!(event.packet instanceof PlayerActionC2SPacket packet)) return;
        if (packet.getAction() != PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) return;

        event.cancel();

        BlockPos block  = packet.getPos();
        Vec3d    player = mc.player.getPos();

        slotData[configuringSlot].playerPos   = player;
        slotData[configuringSlot].targetBlock = block;

        statusSettings[configuringSlot].set(
            (int)player.x + " " + (int)player.y + " " + (int)player.z
            + " | " + block.getX() + " " + block.getY() + " " + block.getZ()
        );

        sendChat("Slot " + configuringSlot + " set — player: " +
            (int)player.x + " " + (int)player.y + " " + (int)player.z +
            " | block: " + block.getX() + " " + block.getY() + " " + block.getZ());

        setButtons[configuringSlot].set(false);
        configuringSlot = -1;
    }

    // --- Hide Baritone chat ---

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();
        if (msg.startsWith("[Baritone]") || msg.startsWith("Searching for path") || msg.startsWith("Pathing")) {
            event.cancel();
        }
    }

    // --- Render ---

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (int slot : new int[]{1, 2, 3}) {
            if (!slotData[slot].isSet()) continue;
            SettingColor col  = (slot == 2) ? color2.get() : color1.get();
            SettingColor line = new SettingColor(col.r, col.g, col.b, 255);

            BlockPos b = slotData[slot].targetBlock;
            event.renderer.box(
                b.getX(), b.getY(), b.getZ(),
                b.getX() + 1, b.getY() + 1, b.getZ() + 1,
                col, line, ShapeMode.Both, 0
            );

            Vec3d p = slotData[slot].playerPos;
            double s = 0.3;
            event.renderer.box(
                p.x - s, p.y, p.z - s,
                p.x + s, p.y + 1.8, p.z + s,
                col, line, ShapeMode.Both, 0
            );
        }
    }

    // --- Helpers ---

    private void rotateToFace(BlockPos target) {
        Vec3d eyes   = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        double dx    = center.x - eyes.x;
        double dy    = center.y - eyes.y;
        double dz    = center.z - eyes.z;
        double dist  = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90));
        mc.player.setPitch((float)(-Math.toDegrees(Math.atan2(dy, dist))));
    }

    private void sendChatCommand(String cmd) {
        if (mc.player != null) mc.player.networkHandler.sendChatMessage(cmd);
    }

    private void sendChat(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(
                Text.literal("[StashMover] ").formatted(Formatting.BLUE)
                    .append(Text.literal(msg).formatted(Formatting.BLUE)),
                false
            );
    }
}