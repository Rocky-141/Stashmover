package com.positionselector.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoStashCycle extends Module {
    private static class SlotData {
        Vec3d playerPos;
        BlockPos targetBlock;
        boolean isSet() { return playerPos != null && targetBlock != null; }
    }

    private final SlotData[] stealSlots   = new SlotData[4];
    private final SlotData[] depositSlots = new SlotData[4];

    // configuringType: 0 = none, 1 = steal, 2 = deposit
    private int configuringType = 0;
    private int configuringIndex = -1;

    private enum ExecState { IDLE, MOVING, ROTATING, INTERACTING, WAITING_FOR_GUI, INVENTORY, CLOSING }
    private ExecState execState = ExecState.IDLE;

    private enum MainPhase { IDLE, STEAL_SEQUENCE, TPA_WAIT, DEPOSIT_SEQUENCE, KILL_WAIT, DONE }
    private MainPhase mainPhase = MainPhase.IDLE;

    private boolean running = false;

    // Steal-phase
    private int stealSequenceStep = -1;
    private int stealSourceSlot = 1;
    private boolean stealCheckedSlot1Empty = false;
    private boolean stealSlot1WasEmpty = false;
    private int stealMatchingStackCount = 0;
    private static final int STEAL_TOTAL_STEPS = 5;

    // Deposit-phase
    private int depositSequenceStep = 0;
    private int depositDestSlot = 1;
    private int depositItemsMoved = 0;

    private static final int DEPOSIT_FALLBACK_THRESHOLD_PERCENT = 90;
    private static final int STEAL_SKIP_THRESHOLD_PERCENT = 20;

    // Tickers
    private int rotateTicker = 0;
    private int inventorySlotIndex = 0;
    private int inventoryTicker = 0;
    private int inventoryOpenTicker = 0;
    private int closeTicker = 0;
    private int movingTicker = 0;

    private int tpaWaitTicker = 0;
    private int killWaitTicker = 0;

    private int loopCounter = 0;

    private boolean suppressStartCallback = false;
    private boolean suppressResetCallback = false;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private File stateFile = null;
    private boolean stateLoadedThisSession = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Filters");
    private final SettingGroup sgStealSlots = settings.createGroup("Steal Slots");
    private final SettingGroup sgDepositSlots = settings.createGroup("Deposit Slots");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blockFilters;
    private final Setting<List<String>> nameFilters;

    private final Setting<Integer> guiOpenDelay;
    private final Setting<Integer> itemDelay;
    private final Setting<Integer> tpaDelaySeconds;
    private final Setting<Integer> killDelaySeconds;
    private final Setting<Integer> cycles;

    private final Setting<String> tpaCommand;
    private final Setting<String> killCommand;

    private final Setting<SettingColor> colorSteal;
    private final Setting<SettingColor> colorDeposit;

    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] setStealButtons = new Setting[4];
    @SuppressWarnings("unchecked")
    private final Setting<String>[]  stealStatusSettings = new Setting[4];

    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] setDepositButtons = new Setting[4];
    @SuppressWarnings("unchecked")
    private final Setting<String>[]  depositStatusSettings = new Setting[4];

    private final Setting<Boolean> startSetting;
    private Setting<Boolean> resetSetting;

    public AutoStashCycle() {
        super(AutoChestSteal.STORAGE, "auto-stash-cycle",
            "Full cycle: steal -> tpa -> deposit-sequence -> kill -> repeat.");

        for (int i = 0; i < stealSlots.length; i++) {
            stealSlots[i] = new SlotData();
            depositSlots[i] = new SlotData();
        }

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

        guiOpenDelay = sgTiming.add(new IntSetting.Builder()
            .name("gui-open-delay")
            .description("Ticks to wait after container opens before moving items (20 = 1s).")
            .defaultValue(10).min(0).sliderMax(100)
            .build()
        );

        itemDelay = sgTiming.add(new IntSetting.Builder()
            .name("item-delay")
            .description("Ticks to wait between each item move (20 = 1s).")
            .defaultValue(2).min(0).sliderMax(40)
            .build()
        );

        tpaDelaySeconds = sgTiming.add(new IntSetting.Builder()
            .name("tpa-delay-seconds")
            .description("Seconds to wait after sending TPA command before continuing.")
            .defaultValue(5).min(0).sliderMax(300)
            .build()
        );

        killDelaySeconds = sgTiming.add(new IntSetting.Builder()
            .name("kill-delay-seconds")
            .description("Seconds to wait after KILL command before continuing.")
            .defaultValue(5).min(0).sliderMax(300)
            .build()
        );

        cycles = sgGeneral.add(new IntSetting.Builder()
            .name("cycles")
            .description("Number of full cycles to run (max 20).")
            .defaultValue(1).min(1).sliderMax(20)
            .build()
        );

        tpaCommand = sgGeneral.add(new StringSetting.Builder()
            .name("tpa-command")
            .description("Command to run after stealing. Use {player} placeholder if needed.")
            .defaultValue("/tpa {player}")
            .build()
        );

        killCommand = sgGeneral.add(new StringSetting.Builder()
            .name("kill-command")
            .description("Command to run after depositing (e.g., /kill).")
            .defaultValue("/kill")
            .build()
        );

        colorSteal = sgRender.add(new ColorSetting.Builder()
            .name("steal-color")
            .description("Color for steal slots.")
            .defaultValue(new SettingColor(0, 255, 0, 80))
            .build()
        );

        colorDeposit = sgRender.add(new ColorSetting.Builder()
            .name("deposit-color")
            .description("Color for deposit slots.")
            .defaultValue(new SettingColor(255, 0, 0, 80))
            .build()
        );

        for (int slot : new int[]{1, 2, 3}) {
            final int s = slot;
            stealStatusSettings[slot] = sgStealSlots.add(new StringSetting.Builder()
                .name("steal-slot-" + slot + "-status")
                .description("Steal slot " + slot + " status.")
                .defaultValue("Not set")
                .build()
            );

            setStealButtons[slot] = sgStealSlots.add(new BoolSetting.Builder()
                .name("set-steal-slot-" + slot)
                .description("Left-click a block to set steal slot " + slot + ".")
                .defaultValue(false)
                .onChanged(v -> {
                    if (!v) return;
                    if (stealSlots[s].isSet()) {
                        sendChat("Steal slot " + s + " already set. Reset by disabling the module.");
                        setStealButtons[s].set(false);
                        return;
                    }
                    configuringType = 1;
                    configuringIndex = s;
                    sendChat("Left-click a block to set steal slot " + s + ".");
                })
                .build()
            );
        }

        for (int slot : new int[]{1, 2, 3}) {
            final int s = slot;
            depositStatusSettings[slot] = sgDepositSlots.add(new StringSetting.Builder()
                .name("deposit-slot-" + slot + "-status")
                .description("Deposit slot " + slot + " status.")
                .defaultValue("Not set")
                .build()
            );

            setDepositButtons[slot] = sgDepositSlots.add(new BoolSetting.Builder()
                .name("set-deposit-slot-" + slot)
                .description("Left-click a block to set deposit slot " + slot + ".")
                .defaultValue(false)
                .onChanged(v -> {
                    if (!v) return;
                    if (depositSlots[s].isSet()) {
                        sendChat("Deposit slot " + s + " already set. Reset by disabling the module.");
                        setDepositButtons[s].set(false);
                        return;
                    }
                    configuringType = 2;
                    configuringIndex = s;
                    sendChat("Left-click a block to set deposit slot " + s + ".");
                })
                .build()
            );
        }

        startSetting = sgGeneral.add(new BoolSetting.Builder()
            .name("start")
            .description("Start the full steal/tpa/deposit/kill cycle.")
            .defaultValue(false)
            .onChanged(v -> {
                if (suppressStartCallback) return;
                if (v) beginSequence();
                else stopAll();
            })
            .build()
        );

        final Setting<Boolean>[] resetRef = new Setting[1];
        resetRef[0] = sgGeneral.add(new BoolSetting.Builder()
            .name("reset")
            .description("Reset everything: clear assigned slots and reset the current sequence/step.")
            .defaultValue(false)
            .onChanged(v -> {
                if (!v) return;
                if (suppressResetCallback) return;
                suppressResetCallback = true;
                resetAllState();
                if (resetRef[0] != null) resetRef[0].set(false);
                suppressResetCallback = false;
            })
            .build()
        );
        resetSetting = resetRef[0];
    }

    @Override
    public void onActivate() {
        resetRuntimeState();
        loadStateIfExists();
        stateLoadedThisSession = true;
    }

    @Override
    public void onDeactivate() {
        // No detection-based phase changes here.
        // If we are running, just save the current state as-is.
        if (running) {
            saveState();
        }

        // Clean up in-memory only.
        try {
            if (mc.player != null) {
                mc.player.networkHandler.sendChatMessage("#stop");
            }
        } catch (Exception ignored) {}

        running = false;
        mainPhase = MainPhase.IDLE;
        execState = ExecState.IDLE;
        configuringType = 0;
        configuringIndex = -1;

        if (startSetting != null && startSetting.get()) {
            suppressStartCallback = true;
            startSetting.set(false);
            suppressStartCallback = false;
        }
    }

    private void resetRuntimeState() {
        running = false;
        mainPhase = MainPhase.IDLE;
        execState = ExecState.IDLE;

        stealSequenceStep = -1;
        stealSourceSlot = 1;
        stealCheckedSlot1Empty = false;
        stealSlot1WasEmpty = false;
        stealMatchingStackCount = 0;

        depositSequenceStep = 0;
        depositDestSlot = 1;
        depositItemsMoved = 0;

        rotateTicker = 0;
        inventorySlotIndex = 0;
        inventoryTicker = 0;
        inventoryOpenTicker = 0;
        closeTicker = 0;
        movingTicker = 0;

        tpaWaitTicker = 0;
        killWaitTicker = 0;

        loopCounter = 0;

        configuringType = 0;
        configuringIndex = -1;
    }

    private void resetAllState() {
        resetRuntimeState();

        for (int i = 0; i < stealSlots.length; i++) {
            stealSlots[i] = new SlotData();
            depositSlots[i] = new SlotData();
        }

        for (int slot : new int[]{1,2,3}) {
            if (stealStatusSettings[slot] != null) stealStatusSettings[slot].set("Not set");
            if (setStealButtons[slot] != null) setStealButtons[slot].set(false);
            if (depositStatusSettings[slot] != null) depositStatusSettings[slot].set("Not set");
            if (setDepositButtons[slot] != null) setDepositButtons[slot].set(false);
        }

        saveState();

        if (startSetting != null && startSetting.get()) {
            suppressStartCallback = true;
            startSetting.set(false);
            suppressStartCallback = false;
        }
    }

    private void stopAll() {
        sendChatCommand("#stop");
        running = false;
        mainPhase = MainPhase.IDLE;
        execState = ExecState.IDLE;
        configuringType = 0;
        configuringIndex = -1;

        // Explicit user stop writes running=false.
        saveState();

        if (startSetting != null && startSetting.get()) {
            suppressStartCallback = true;
            startSetting.set(false);
            suppressStartCallback = false;
        }
    }

    private void beginSequence() {
        if (running) return;

        if (!stealSlots[1].isSet() || !stealSlots[2].isSet()) {
            sendChat("Steal slots 1 and 2 must be set before starting.");
            suppressStartCallback = true;
            startSetting.set(false);
            suppressStartCallback = false;
            return;
        }
        if (!depositSlots[1].isSet() || !depositSlots[2].isSet()) {
            sendChat("Deposit slots 1 and 2 must be set before starting.");
            suppressStartCallback = true;
            startSetting.set(false);
            suppressStartCallback = false;
            return;
        }

        running = true;
        loopCounter = 0;
        mainPhase = MainPhase.STEAL_SEQUENCE;

        stealSequenceStep = 0;
        stealSourceSlot = 1;
        stealCheckedSlot1Empty = false;
        stealSlot1WasEmpty = false;
        stealMatchingStackCount = 0;

        depositSequenceStep = 0;
        depositDestSlot = 1;
        depositItemsMoved = 0;

        saveState();
        moveToStealSource();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            stateLoadedThisSession = false;
            return;
        }

        if (!stateLoadedThisSession) {
            loadStateIfExists();
            stateLoadedThisSession = true;
        }

        switch (execState) {
            case MOVING -> handleMoving();
            case ROTATING -> handleRotating();
            case INTERACTING -> handleInteracting();
            case WAITING_FOR_GUI -> handleWaitingForGui();
            case INVENTORY -> handleInventory();
            case CLOSING -> handleClosing();
            default -> {}
        }

        // TPA_WAIT: only the delay drives the transition, no detection.
        if (mainPhase == MainPhase.TPA_WAIT && execState == ExecState.IDLE) {
            tpaWaitTicker++;
            if (tpaWaitTicker >= tpaDelaySeconds.get() * 20) {
                tpaWaitTicker = 0;
                mainPhase = MainPhase.DEPOSIT_SEQUENCE;
                depositSequenceStep = 0;
                depositDestSlot = 1;
                depositItemsMoved = 0;
                saveState();
                sendChat("TPA delay finished — starting deposit sequence (steps 0..4).");
                moveToDepositDest(depositDestSlot);
            }
        }

        if (mainPhase == MainPhase.KILL_WAIT && execState == ExecState.IDLE) {
            killWaitTicker++;
            if (killWaitTicker >= killDelaySeconds.get() * 20) {
                killWaitTicker = 0;
                loopCounter++;
                if (loopCounter < cycles.get()) {
                    mainPhase = MainPhase.STEAL_SEQUENCE;
                    stealSequenceStep = 0;
                    stealSourceSlot = 1;
                    stealCheckedSlot1Empty = false;
                    stealSlot1WasEmpty = false;
                    stealMatchingStackCount = 0;
                    saveState();
                    sendChat("Kill wait finished — starting next steal sequence.");
                    moveToStealSource();
                } else {
                    sendChat("Completed all cycles.");
                    stopAll();
                }
            }
        }
    }

    private void moveToStealSource() {
        SlotData data = stealSlots[stealSourceSlot];
        if (!data.isSet()) {
            sendChat("Steal slot " + stealSourceSlot + " is not set! Stopping.");
            stopAll();
            return;
        }
        int x = (int) data.playerPos.x;
        int y = (int) data.playerPos.y;
        int z = (int) data.playerPos.z;
        sendChat("Moving to steal slot " + stealSourceSlot + "...");
        sendChatCommand("#goto " + x + " " + y + " " + z);
        movingTicker = 0;
        execState = ExecState.MOVING;
        saveState();
    }

    private void moveToStealDeposit() {
        SlotData data = stealSlots[2];
        if (!data.isSet()) {
            sendChat("Steal deposit slot 2 is not set! Stopping.");
            stopAll();
            return;
        }
        int x = (int) data.playerPos.x;
        int y = (int) data.playerPos.y;
        int z = (int) data.playerPos.z;
        sendChat("Moving to steal deposit slot 2...");
        sendChatCommand("#goto " + x + " " + y + " " + z);
        movingTicker = 0;
        execState = ExecState.MOVING;
        saveState();
    }

    private void moveToDepositDest(int slot) {
        SlotData data = depositSlots[slot];
        if (!data.isSet()) {
            sendChat("Deposit slot " + slot + " is not set! Stopping.");
            stopAll();
            return;
        }
        int x = (int) data.playerPos.x;
        int y = (int) data.playerPos.y;
        int z = (int) data.playerPos.z;
        sendChat("Moving to deposit slot " + slot + "...");
        sendChatCommand("#goto " + x + " " + y + " " + z);
        movingTicker = 0;
        execState = ExecState.MOVING;
        saveState();
    }

    private Vec3d currentTargetPos() {
        if (!running) return null;
        if (mainPhase == MainPhase.STEAL_SEQUENCE) {
            if (stealSequenceStep >= 0 && stealSequenceStep < STEAL_TOTAL_STEPS) {
                int slot = (stealSequenceStep % 2 == 0) ? stealSourceSlot : 2;
                return stealSlots[slot] != null ? stealSlots[slot].playerPos : null;
            }
            return null;
        } else if (mainPhase == MainPhase.DEPOSIT_SEQUENCE) {
            if (isDepositStep(depositSequenceStep)) {
                return depositSlots[depositDestSlot] != null ? depositSlots[depositDestSlot].playerPos : null;
            } else {
                return depositSlots[2] != null ? depositSlots[2].playerPos : null;
            }
        }
        return null;
    }

    private BlockPos currentTargetBlock() {
        if (!running) return null;
        if (mainPhase == MainPhase.STEAL_SEQUENCE) {
            if (stealSequenceStep >= 0 && stealSequenceStep < STEAL_TOTAL_STEPS) {
                int slot = (stealSequenceStep % 2 == 0) ? stealSourceSlot : 2;
                return stealSlots[slot] != null ? stealSlots[slot].targetBlock : null;
            }
            return null;
        } else if (mainPhase == MainPhase.DEPOSIT_SEQUENCE) {
            if (isDepositStep(depositSequenceStep)) {
                return depositSlots[depositDestSlot] != null ? depositSlots[depositDestSlot].targetBlock : null;
            } else {
                return depositSlots[2] != null ? depositSlots[2].targetBlock : null;
            }
        }
        return null;
    }

    private boolean isDepositStep(int step) {
        return step % 2 == 0;
    }

    private void handleMoving() {
        Vec3d dest = currentTargetPos();
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

    private void handleRotating() {
        BlockPos target = currentTargetBlock();
        if (target == null) return;
        rotateToFace(target);
        rotateTicker++;
        if (rotateTicker >= 5) execState = ExecState.INTERACTING;
    }

    private void handleInteracting() {
        BlockPos target = currentTargetBlock();
        if (target == null) {
            execState = ExecState.IDLE;
            return;
        }
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
                sendChat("Container did not open — skipping.");
                if (mainPhase == MainPhase.STEAL_SEQUENCE) advanceStealStep();
                else execState = ExecState.IDLE;
            }
            return;
        }

        if (inventoryOpenTicker < guiOpenDelay.get() + 1) return;

        if (mainPhase == MainPhase.STEAL_SEQUENCE) {
            boolean isTaking = (stealSequenceStep % 2 == 0);
            if (isTaking && stealSourceSlot == 1 && !stealCheckedSlot1Empty) {
                int fullness = containerFillPercent((HandledScreen<?>) mc.currentScreen);
                if (fullness <= STEAL_SKIP_THRESHOLD_PERCENT) {
                    sendChat("Steal source (slot 1) is only " + fullness + "% full — skipping to backup steal slot 3 if set.");
                    mc.player.closeHandledScreen();
                    stealCheckedSlot1Empty = true;
                    if (stealSlots[3].isSet()) {
                        stealSourceSlot = 3;
                        saveState();
                        moveToStealSource();
                    } else {
                        sendChat("Steal slot 3 not set. Continuing with slot 1 (may be empty).");
                        inventorySlotIndex = 0;
                        inventoryTicker = 0;
                        stealSlot1WasEmpty = true;
                        execState = ExecState.INVENTORY;
                    }
                    return;
                }
                stealCheckedSlot1Empty = true;
            }
        }

        if (mainPhase == MainPhase.DEPOSIT_SEQUENCE && isDepositStep(depositSequenceStep)) {
            if (depositDestSlot == 1) {
                int fullness = containerFillPercent((HandledScreen<?>) mc.currentScreen);
                if (fullness >= DEPOSIT_FALLBACK_THRESHOLD_PERCENT) {
                    sendChat("Deposit slot 1 is " + fullness + "% full (hard threshold " + DEPOSIT_FALLBACK_THRESHOLD_PERCENT + "%) — switching to slot 3 if set.");
                    mc.player.closeHandledScreen();
                    if (depositSlots[3].isSet()) {
                        depositDestSlot = 3;
                        saveState();
                        moveToDepositDest(3);
                    } else {
                        sendChat("Deposit slot 3 not set and slot 1 is above hard threshold. Stopping.");
                        stopAll();
                    }
                    return;
                }
            }
        }

        inventorySlotIndex = 0;
        inventoryTicker = 0;
        execState = ExecState.INVENTORY;
    }

    private void handleInventory() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            sendChat("Container closed unexpectedly.");
            if (mainPhase == MainPhase.STEAL_SEQUENCE) advanceStealStep();
            else execState = ExecState.CLOSING;
            return;
        }

        if (inventoryTicker > 0) { inventoryTicker--; return; }

        int totalSlots = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;

        if (mainPhase == MainPhase.STEAL_SEQUENCE) {
            boolean isTaking = (stealSequenceStep % 2 == 0);
            if (isTaking) {
                while (inventorySlotIndex < playerInvStart) {
                    int current = inventorySlotIndex++;
                    var stack = screen.getScreenHandler().slots.get(current).getStack();
                    if (stack.isEmpty()) continue;
                    if (!matchesFilters(stack)) continue;
                    stealSlot1WasEmpty = false;
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
            return;
        }

        if (mainPhase == MainPhase.DEPOSIT_SEQUENCE) {
            if (isDepositStep(depositSequenceStep)) {
                boolean movedAny = false;
                while (inventorySlotIndex < 36) {
                    int current = playerInvStart + inventorySlotIndex++;
                    var stack = screen.getScreenHandler().slots.get(current).getStack();
                    if (stack.isEmpty()) continue;
                    if (!matchesFilters(stack)) continue;
                    movedAny = true;
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, current, 0, SlotActionType.QUICK_MOVE, mc.player);
                    depositItemsMoved++;
                    inventoryTicker = itemDelay.get();
                    return;
                }

                if (!movedAny) {
                    mc.player.closeHandledScreen();
                    sendChat("No matching items in player inventory to deposit at step " + depositSequenceStep + ". Advancing.");
                    depositItemsMoved = 0;
                    depositSequenceStep++;
                    execState = ExecState.IDLE;
                    saveState();
                    if (depositSequenceStep <= 4) {
                        if (isDepositStep(depositSequenceStep)) {
                            depositDestSlot = 1;
                            moveToDepositDest(depositDestSlot);
                        } else {
                            moveToDepositDest(2);
                        }
                    } else {
                        String killCmd = killCommand.get().trim();
                        if (!killCmd.isEmpty()) {
                            sendChatCommand(killCmd);
                            sendChat("Sent: " + killCmd + ". Waiting " + killDelaySeconds.get() + "s.");
                        } else {
                            sendChat("Kill command empty — skipping kill wait.");
                        }
                        mainPhase = MainPhase.KILL_WAIT;
                        killWaitTicker = 0;
                        saveState();
                    }
                    return;
                }

                closeTicker = 0;
                execState = ExecState.CLOSING;
                return;
            } else {
                boolean movedAny = false;
                while (inventorySlotIndex < playerInvStart) {
                    int current = inventorySlotIndex++;
                    var stack = screen.getScreenHandler().slots.get(current).getStack();
                    if (stack.isEmpty()) continue;
                    if (!matchesFilters(stack)) continue;
                    movedAny = true;
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, current, 0, SlotActionType.QUICK_MOVE, mc.player);
                    inventoryTicker = itemDelay.get();
                    return;
                }

                if (!movedAny) {
                    mc.player.closeHandledScreen();
                    sendChat("Deposit slot 2 empty at deposit step " + depositSequenceStep + ". Advancing.");
                    depositSequenceStep++;
                    execState = ExecState.IDLE;
                    saveState();
                    if (depositSequenceStep <= 4) {
                        if (isDepositStep(depositSequenceStep)) {
                            depositDestSlot = 1;
                            moveToDepositDest(depositDestSlot);
                        } else {
                            moveToDepositDest(2);
                        }
                    } else {
                        String killCmd = killCommand.get().trim();
                        if (!killCmd.isEmpty()) {
                            sendChatCommand(killCmd);
                            sendChat("Sent: " + killCmd + ". Waiting " + killDelaySeconds.get() + "s.");
                        } else {
                            sendChat("Kill command empty — skipping kill wait.");
                        }
                        mainPhase = MainPhase.KILL_WAIT;
                        killWaitTicker = 0;
                        saveState();
                    }
                    return;
                }

                closeTicker = 0;
                execState = ExecState.CLOSING;
                return;
            }
        }
    }

    private void handleClosing() {
        closeTicker++;
        if (closeTicker >= 3) {
            mc.player.closeHandledScreen();

            if (mainPhase == MainPhase.STEAL_SEQUENCE) {
                if (stealSequenceStep % 2 == 0 && stealSourceSlot == 1 && stealSlot1WasEmpty && !stealCheckedSlot1Empty) {
                    stealCheckedSlot1Empty = true;
                    if (stealSlots[3].isSet()) {
                        sendChat("Steal slot 1 empty — trying backup steal slot 3.");
                        stealSourceSlot = 3;
                        saveState();
                        moveToStealSource();
                        return;
                    } else {
                        sendChat("Steal slot 1 empty and steal slot 3 not set. Stopping.");
                        stopAll();
                        return;
                    }
                }
                advanceStealStep();
                return;
            }

            if (mainPhase == MainPhase.DEPOSIT_SEQUENCE) {
                depositSequenceStep++;
                saveState();
                if (depositSequenceStep <= 4) {
                    if (isDepositStep(depositSequenceStep)) {
                        depositDestSlot = 1;
                        moveToDepositDest(depositDestSlot);
                    } else {
                        moveToDepositDest(2);
                    }
                } else {
                    String killCmd = killCommand.get().trim();
                    if (!killCmd.isEmpty()) {
                        sendChatCommand(killCmd);
                        sendChat("Sent: " + killCmd + ". Waiting " + killDelaySeconds.get() + "s.");
                    } else {
                        sendChat("Kill command empty — skipping kill wait.");
                    }
                    mainPhase = MainPhase.KILL_WAIT;
                    execState = ExecState.IDLE;
                    killWaitTicker = 0;
                    depositItemsMoved = 0;
                    saveState();
                }
                return;
            }

            execState = ExecState.IDLE;
        }
    }

    private void advanceStealStep() {
        stealSequenceStep++;
        saveState();
        if (stealSequenceStep >= STEAL_TOTAL_STEPS) {
            sendChat("Steal sequence complete.");
            mainPhase = MainPhase.TPA_WAIT;
            execState = ExecState.IDLE;
            String tpaCmd = tpaCommand.get().trim();
            if (!tpaCmd.isEmpty()) {
                sendChatCommand(tpaCmd);
                sendChat("Sent: " + tpaCmd + ". Waiting " + tpaDelaySeconds.get() + "s.");
            } else {
                sendChat("TPA command empty — skipping TPA wait.");
            }
            tpaWaitTicker = 0;
            saveState();
            return;
        }

        if (stealSequenceStep % 2 == 0) {
            stealSourceSlot = 1;
            stealCheckedSlot1Empty = false;
            stealSlot1WasEmpty = false;
            moveToStealSource();
        } else {
            moveToStealDeposit();
        }
    }

    private static class PersistSlot {
        @SerializedName("player_x") double px;
        @SerializedName("player_y") double py;
        @SerializedName("player_z") double pz;
        @SerializedName("block_x") int bx;
        @SerializedName("block_y") int by;
        @SerializedName("block_z") int bz;
    }

    private static class PersistState {
        @SerializedName("running") boolean running;
        @SerializedName("main_phase") String mainPhase;
        @SerializedName("steal_sequence_step") int stealSequenceStep;
        @SerializedName("steal_source_slot") int stealSourceSlot;
        @SerializedName("deposit_sequence_step") int depositSequenceStep;
        @SerializedName("deposit_dest_slot") int depositDestSlot;
        @SerializedName("loop_counter") int loopCounter;
        @SerializedName("steal_slots") PersistSlot[] stealSlots;
        @SerializedName("deposit_slots") PersistSlot[] depositSlots;
    }

    private File getStateFile() {
        if (stateFile != null) return stateFile;
        try {
            if (mc != null && mc.runDirectory != null) {
                File dir = mc.runDirectory;
                if (!dir.exists()) dir.mkdirs();
                stateFile = new File(dir, "autostashcycle_state.json");
            } else {
                stateFile = new File("autostashcycle_state.json");
            }
        } catch (Exception e) {
            stateFile = new File("autostashcycle_state.json");
        }
        return stateFile;
    }

    private PersistSlot slotToPersist(SlotData sd) {
        PersistSlot ps = new PersistSlot();
        if (sd != null && sd.isSet()) {
            ps.px = sd.playerPos.x;
            ps.py = sd.playerPos.y;
            ps.pz = sd.playerPos.z;
            ps.bx = sd.targetBlock.getX();
            ps.by = sd.targetBlock.getY();
            ps.bz = sd.targetBlock.getZ();
        } else {
            ps.px = ps.py = ps.pz = 0;
            ps.bx = ps.by = ps.bz = 0;
        }
        return ps;
    }

    private void saveState() {
        File out = getStateFile();
        PersistState s = new PersistState();
        s.running = running;
        s.mainPhase = mainPhase.name();
        s.stealSequenceStep = stealSequenceStep;
        s.stealSourceSlot = stealSourceSlot;
        s.depositSequenceStep = depositSequenceStep;
        s.depositDestSlot = depositDestSlot;
        s.loopCounter = loopCounter;

        s.stealSlots = new PersistSlot[4];
        s.depositSlots = new PersistSlot[4];
        for (int i = 0; i < 4; i++) {
            s.stealSlots[i] = slotToPersist(stealSlots[i]);
            s.depositSlots[i] = slotToPersist(depositSlots[i]);
        }

        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (FileWriter fw = new FileWriter(tmp)) {
                gson.toJson(s, fw);
            }

            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                try (FileWriter fw = new FileWriter(out)) {
                    gson.toJson(s, fw);
                }
            } catch (IOException ignored) {}
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private void loadStateIfExists() {
        File f = getStateFile();
        if (!f.exists()) return;
        try (FileReader fr = new FileReader(f)) {
            PersistState s = gson.fromJson(fr, PersistState.class);
            if (s == null) return;

            for (int i = 0; i < 4; i++) {
                if (s.stealSlots != null && s.stealSlots.length > i && s.stealSlots[i] != null && !(s.stealSlots[i].bx == 0 && s.stealSlots[i].by == 0 && s.stealSlots[i].bz == 0)) {
                    stealSlots[i].playerPos = new Vec3d(s.stealSlots[i].px, s.stealSlots[i].py, s.stealSlots[i].pz);
                    stealSlots[i].targetBlock = new BlockPos(s.stealSlots[i].bx, s.stealSlots[i].by, s.stealSlots[i].bz);
                    if (stealStatusSettings[i] != null) stealStatusSettings[i].set((int)s.stealSlots[i].px + " " + (int)s.stealSlots[i].py + " " + (int)s.stealSlots[i].pz + " | " + s.stealSlots[i].bx + " " + s.stealSlots[i].by + " " + s.stealSlots[i].bz);
                }
                if (s.depositSlots != null && s.depositSlots.length > i && s.depositSlots[i] != null && !(s.depositSlots[i].bx == 0 && s.depositSlots[i].by == 0 && s.depositSlots[i].bz == 0)) {
                    depositSlots[i].playerPos = new Vec3d(s.depositSlots[i].px, s.depositSlots[i].py, s.depositSlots[i].pz);
                    depositSlots[i].targetBlock = new BlockPos(s.depositSlots[i].bx, s.depositSlots[i].by, s.depositSlots[i].bz);
                    if (depositStatusSettings[i] != null) depositStatusSettings[i].set((int)s.depositSlots[i].px + " " + (int)s.depositSlots[i].py + " " + (int)s.depositSlots[i].pz + " | " + s.depositSlots[i].bx + " " + s.depositSlots[i].by + " " + s.depositSlots[i].bz);
                }
            }

            running = s.running;
            try { mainPhase = MainPhase.valueOf(s.mainPhase); } catch (Exception ignored) { mainPhase = MainPhase.IDLE; }
            stealSequenceStep = s.stealSequenceStep;
            stealSourceSlot = s.stealSourceSlot;
            depositSequenceStep = s.depositSequenceStep;
            depositDestSlot = s.depositDestSlot;
            loopCounter = s.loopCounter;

            if (running) {
                execState = ExecState.IDLE;
                switch (mainPhase) {
                    case STEAL_SEQUENCE -> {
                        if (stealSequenceStep < 0) stealSequenceStep = 0;
                        if (stealSequenceStep % 2 == 0) moveToStealSource();
                        else moveToStealDeposit();
                    }
                    case TPA_WAIT -> {
                        // No detection: just resume the TPA delay from 0.
                        tpaWaitTicker = 0;
                        sendChat("Resuming TPA wait.");
                    }
                    case DEPOSIT_SEQUENCE -> {
                        if (isDepositStep(depositSequenceStep)) moveToDepositDest(depositDestSlot);
                        else moveToDepositDest(2);
                    }
                    case KILL_WAIT -> {
                        killWaitTicker = 0;
                        sendChat("Resuming kill wait.");
                    }
                    default -> {}
                }
            }
        } catch (IOException ignored) {
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (configuringType == 0) return;
        if (!(event.packet instanceof PlayerActionC2SPacket packet)) return;
        if (packet.getAction() != PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) return;

        event.cancel();

        BlockPos block = packet.getPos();
        Vec3d player = mc.player.getPos();

        if (configuringType == 1) {
            stealSlots[configuringIndex].playerPos = player;
            stealSlots[configuringIndex].targetBlock = block;
            if (stealStatusSettings[configuringIndex] != null) {
                stealStatusSettings[configuringIndex].set(
                    (int)player.x + " " + (int)player.y + " " + (int)player.z
                    + " | " + block.getX() + " " + block.getY() + " " + block.getZ()
                );
            }
            sendChat("Steal slot " + configuringIndex + " set — player: " +
                (int)player.x + " " + (int)player.y + " " + (int)player.z +
                " | block: " + block.getX() + " " + block.getY() + " " + block.getZ());
            if (setStealButtons[configuringIndex] != null) setStealButtons[configuringIndex].set(false);

            saveState();
        } else if (configuringType == 2) {
            depositSlots[configuringIndex].playerPos = player;
            depositSlots[configuringIndex].targetBlock = block;
            if (depositStatusSettings[configuringIndex] != null) {
                depositStatusSettings[configuringIndex].set(
                    (int)player.x + " " + (int)player.y + " " + (int)player.z
                    + " | " + block.getX() + " " + block.getY() + " " + block.getZ()
                );
            }
            sendChat("Deposit slot " + configuringIndex + " set — player: " +
                (int)player.x + " " + (int)player.y + " " + (int)player.z +
                " | block: " + block.getX() + " " + block.getY() + " " + block.getZ());
            if (setDepositButtons[configuringIndex] != null) setDepositButtons[configuringIndex].set(false);

            saveState();
        }

        configuringType = 0;
        configuringIndex = -1;
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();
        if (msg.startsWith("[Baritone]") || msg.startsWith("Searching for path") || msg.startsWith("Pathing")) {
            event.cancel();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (int slot : new int[]{1,2,3}) {
            if (!stealSlots[slot].isSet()) continue;
            SettingColor col = colorSteal.get();
            SettingColor line = new SettingColor(col.r, col.g, col.b, 255);

            BlockPos b = stealSlots[slot].targetBlock;
            event.renderer.box(
                b.getX(), b.getY(), b.getZ(),
                b.getX() + 1, b.getY() + 1, b.getZ() + 1,
                col, line, ShapeMode.Both, 0
            );

            Vec3d p = stealSlots[slot].playerPos;
            double s = 0.3;
            event.renderer.box(
                p.x - s, p.y, p.z - s,
                p.x + s, p.y + 1.8, p.z + s,
                col, line, ShapeMode.Both, 0
            );
        }

        for (int slot : new int[]{1,2,3}) {
            if (!depositSlots[slot].isSet()) continue;
            SettingColor col = colorDeposit.get();
            SettingColor line = new SettingColor(col.r, col.g, col.b, 255);

            BlockPos b = depositSlots[slot].targetBlock;
            event.renderer.box(
                b.getX(), b.getY(), b.getZ(),
                b.getX() + 1, b.getY() + 1, b.getZ() + 1,
                col, line, ShapeMode.Both, 0
            );

            Vec3d p = depositSlots[slot].playerPos;
            double s = 0.3;
            event.renderer.box(
                p.x - s, p.y, p.z - s,
                p.x + s, p.y + 1.8, p.z + s,
                col, line, ShapeMode.Both, 0
            );
        }
    }

    private int containerFillPercent(HandledScreen<?> screen) {
        int totalSlots = screen.getScreenHandler().slots.size();
        int playerInvStart = totalSlots - 36;
        if (playerInvStart <= 0) return 0;
        int occupied = 0;
        int possible = playerInvStart;
        for (int i = 0; i < playerInvStart; i++) {
            var stack = screen.getScreenHandler().slots.get(i).getStack();
            if (!stack.isEmpty()) occupied++;
        }
        return (int) Math.round((occupied / (double) possible) * 100.0);
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
        Vec3d eyes = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        double dx = center.x - eyes.x;
        double dy = center.y - eyes.y;
        double dz = center.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90));
        mc.player.setPitch((float)(-Math.toDegrees(Math.atan2(dy, dist))));
    }

    private void sendChatCommand(String cmd) {
        if (mc.player != null && cmd != null && !cmd.isEmpty()) {
            String out = cmd.replace("{player}", "");
            mc.player.networkHandler.sendChatMessage(out);
        }
    }

    private void sendChat(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(
                Text.literal("[StashCycle] ").formatted(Formatting.AQUA)
                    .append(Text.literal(msg).formatted(Formatting.WHITE)),
                false
            );
    }
}
