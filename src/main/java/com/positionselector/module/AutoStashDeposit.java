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

public class AutoStashDeposit extends Module {

    // -------------------------------------------------------------------------
    // Slot data
    // -------------------------------------------------------------------------

    private static class SlotData {
        Vec3d    playerPos;
        BlockPos targetBlock;
        boolean isSet() { return playerPos != null && targetBlock != null; }
    }

    private final SlotData[] slotData      = new SlotData[4];
    private       int        configuringSlot = -1;

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    private enum ExecState { IDLE, MOVING, ROTATING, INTERACTING, WAITING_FOR_GUI, INVENTORY, CLOSING }
    private ExecState execState = ExecState.IDLE;

    private boolean running     = false;
    private boolean takingPhase = true;   // true = steal from slot 1, false = deposit into 2/3
    private int     destSlot    = 2;
    private int     itemsMoved  = 0;

    // Timers
    private int rotateTicker        = 0;
    private int inventorySlotIndex  = 0;
    private int inventoryTicker     = 0;
    private int inventoryOpenTicker = 0;
    private int closeTicker         = 0;
    private int movingTicker        = 0;

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public AutoStashDeposit() {
        super(AutoChestSteal.STORAGE, "auto-stash-deposit",
            "Steals from slot 1, deposits into slot 2, overflows to slot 3. Loops until slot 1 is empty.");

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
            .description("Start the steal-deposit loop.")
            .defaultValue(false)
            .onChanged(v -> { if (v) beginSequence(); })
            .build()
        );

        for (int slot : new int[]{1, 2, 3}) {
            final int s = slot;

            statusSettings[slot] = sgSlots.add(new StringSetting.Builder()
                .name("slot-" + slot + "-status")
                .description("Slot " + slot + (slot == 3 ? " (overflow)" : "") + " status.")
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
                        sendChat("Slot " + s + " already set. Reset by disabling the module.");
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
            .description("Color for slot 1 (source).")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .build()
        );

        color2 = sgRender.add(new ColorSetting.Builder()
            .name("color-2")
            .description("Color for slot 2 and 3 (deposit).")
            .defaultValue(new SettingColor(255, 0, 0, 80))
            .build()
        );
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onActivate() {
        running         = false;
        takingPhase     = true;
        destSlot        = 2;
        itemsMoved      = 0;
        execState       = ExecState.IDLE;
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
        running         = false;
        execState       = ExecState.IDLE;
        configuringSlot = -1;
    }

    // -------------------------------------------------------------------------
    // Sequence control
    // -------------------------------------------------------------------------

    private void beginSequence() {
        if (running) return;

        if (!slotData[1].isSet() || !slotData[2].isSet()) {
            sendChat("Slots 1 and 2 must be set before starting.");
            startSetting.set(false);
            return;
        }

        running     = true;
        takingPhase = true;
        destSlot    = 2;
        itemsMoved  = 0;
        moveTo(1);
    }

    /**
     * Called after each CLOSING phase.
     * Decides whether to loop, switch phase, or stop.
     */
    private void advanceStep() {
        if (takingPhase) {
            // Finished stealing from slot 1
            if (itemsMoved == 0) {
                sendChat("Slot 1 has no matching items left. Stopping.");
                stopSequence();
                return;
            }
            // Items were stolen — go deposit
            takingPhase = false;
            itemsMoved  = 0;
            moveTo(destSlot);

        } else {
            // Finished depositing — loop back to slot 1
            takingPhase = true;
            destSlot    = 2;   // reset overflow slot for next cycle
            itemsMoved  = 0;
            moveTo(1);
        }
    }

    private void stopSequence() {
        running     = false;
        takingPhase = true;
        destSlot    = 2;
        execState   = ExecState.IDLE;
        startSetting.set(false);
        sendChatCommand("#stop");
    }

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
        sendChat((takingPhase ? "Stealing from" : "Depositing into") + " slot " + slot + "...");
        sendChatCommand("#goto " + x + " " + y + " " + z);
        movingTicker = 0;
        execState    = ExecState.MOVING;
    }

    // -------------------------------------------------------------------------
    // Tick handler
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void handleMoving() {
        Vec3d dest = currentTargetPos();
        if (dest == null) return;

        double dx   = mc.player.getX() - dest.x;
        double dy   = mc.player.getY() - dest.y;
        double dz   = mc.player.getZ() - dest.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        movingTicker++;

        if (dist < 1.5 || movingTicker > 600) {
            sendChatCommand("#stop");
            rotateTicker = 0;
            execState    = ExecState.ROTATING;
        }
    }

    private void handleRotating() {
        rotateToFace(currentTargetBlock());
        rotateTicker++;
        if (rotateTicker >= 5) execState = ExecState.INTERACTING;
    }

    private void handleInteracting() {
        BlockPos target = currentTargetBlock();
        mc.interactionManager.interactBlock(
            mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false)
        );
        inventoryOpenTicker = 0;
        execState           = ExecState.WAITING_FOR_GUI;
    }

    private void handleWaitingForGui() {
        inventoryOpenTicker++;

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            if (inventoryOpenTicker > 40) {
                sendChat("Container did not open — skipping.");
                advanceStep();
            }
            return;
        }

        if (inventoryOpenTicker < guiOpenDelay.get() + 1) return;

        // Check fullness only during a deposit phase into slot 2
        if (!takingPhase && destSlot == 2) {
            if (isContainerFull(screen)) {
                sendChat("Slot 2 is full — switching to slot 3.");
                mc.player.closeHandledScreen();

                if (slotData[3].isSet()) {
                    destSlot = 3;
                    moveTo(3);
                } else {
                    sendChat("Slot 3 not set and slot 2 is full. Stopping.");
                    stopSequence();
                }
                return;
            }
        }

        inventorySlotIndex = 0;
        inventoryTicker    = 0;
        execState          = ExecState.INVENTORY;
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

        if (takingPhase) {
            // Steal matching items from chest into player inventory
            while (inventorySlotIndex < playerInvStart) {
                int current = inventorySlotIndex++;
                var stack = screen.getScreenHandler().slots.get(current).getStack();
                if (stack.isEmpty() || !matchesFilters(stack)) continue;

                mc.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId, current, 0,
                    SlotActionType.QUICK_MOVE, mc.player
                );
                itemsMoved++;
                inventoryTicker = itemDelay.get();
                return;
            }
        } else {
            // Deposit matching items from player inventory into chest
            while (inventorySlotIndex < 36) {
                int current = playerInvStart + inventorySlotIndex++;
                var stack = screen.getScreenHandler().slots.get(current).getStack();
                if (stack.isEmpty() || !matchesFilters(stack)) continue;

                mc.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId, current, 0,
                    SlotActionType.QUICK_MOVE, mc.player
                );
                itemsMoved++;
                inventoryTicker = itemDelay.get();
                return;
            }
        }

        // Exhausted all slots for this phase
        closeTicker = 0;
        execState   = ExecState.CLOSING;
    }

    private void handleClosing() {
        closeTicker++;
        if (closeTicker >= 3) {
            mc.player.closeHandledScreen();
            advanceStep();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Player position we are currently navigating toward. */
    private Vec3d currentTargetPos() {
        if (!running) return null;
        int slot = takingPhase ? 1 : destSlot;
        return slotData[slot] != null ? slotData[slot].playerPos : null;
    }

    /** Chest block we are currently targeting. */
    private BlockPos currentTargetBlock() {
        int slot = takingPhase ? 1 : destSlot;
        return slotData[slot].targetBlock;
    }

    private boolean isContainerFull(HandledScreen<?> screen) {
        int totalSlots     = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;
        for (int i = 0; i < playerInvStart; i++) {
            var stack = screen.getScreenHandler().slots.get(i).getStack();
            if (stack.isEmpty()) return false;
            if (matchesFilters(stack) && stack.getCount() < stack.getMaxCount()) return false;
        }
        return true;
    }

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

    private void rotateToFace(BlockPos target) {
        Vec3d  eyes   = mc.player.getEyePos();
        Vec3d  center = Vec3d.ofCenter(target);
        double dx     = center.x - eyes.x;
        double dy     = center.y - eyes.y;
        double dz     = center.z - eyes.z;
        double dist   = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90));
        mc.player.setPitch((float)(-Math.toDegrees(Math.atan2(dy, dist))));
    }

    // -------------------------------------------------------------------------
    // Packet: set slot on left-click
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Hide Baritone chat noise
    // -------------------------------------------------------------------------

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();
        if (msg.startsWith("[Baritone]") || msg.startsWith("Searching for path") || msg.startsWith("Pathing")) {
            event.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (int slot : new int[]{1, 2, 3}) {
            if (!slotData[slot].isSet()) continue;
            SettingColor col  = (slot == 1) ? color1.get() : color2.get();
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

    // -------------------------------------------------------------------------
    // Chat helpers
    // -------------------------------------------------------------------------

    private void sendChatCommand(String cmd) {
        if (mc.player != null) mc.player.networkHandler.sendChatMessage(cmd);
    }

    private void sendChat(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(
                Text.literal("[StashDeposit] ").formatted(Formatting.AQUA)
                    .append(Text.literal(msg).formatted(Formatting.WHITE)),
                false
            );
    }
}