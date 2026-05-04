package com.positionselector.module;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class PositionSelectorModule extends Module {

    private static final int CLICK_SLOTS = 5;
    private static final int TOTAL_SLOTS = 6;

    private boolean handlingCallback = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSlots   = settings.createGroup("Slots");
    private final SettingGroup sgColors  = settings.createGroup("Colors");

    private final Setting<Integer> opacity = sgGeneral.add(new IntSetting.Builder()
        .name("opacity")
        .description("Alpha of all rendered boxes (0-255).")
        .defaultValue(160)
        .min(0).max(255)
        .sliderMin(0).sliderMax(255)
        .build()
    );

    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] setButtons   = new Setting[TOTAL_SLOTS];
    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] clearButtons = new Setting[TOTAL_SLOTS];

    @SuppressWarnings("unchecked")
    private final Setting<SettingColor>[] slotColors = new Setting[TOTAL_SLOTS];

    private final BlockPos[] blockPos  = new BlockPos[CLICK_SLOTS];
    private final BlockPos[] playerPos = new BlockPos[CLICK_SLOTS];
    private BlockPos slot6Pos = null;
    private int capturingSlot = -1;

    public PositionSelectorModule() {
        // CHANGED: now uses the Storage category from AutoChestLoot
        super(AutoChestSteal.STORAGE, "position-selector",
              "GUI-driven 6-slot world position capture with in-world rendering.");
        buildSlotSettings();
        buildColorSettings();
    }

    private void buildSlotSettings() {
        for (int i = 0; i < CLICK_SLOTS; i++) {
            final int slot = i;

            setButtons[i] = sgSlots.add(new BoolSetting.Builder()
                .name("slot-" + (i + 1) + "-click")
                .description("Slot " + (i + 1) + ": close GUI then left-click a block to set. Press Esc to cancel.")
                .defaultValue(false)
                .onChanged(v -> {
                    if (handlingCallback) return;
                    if (v) {
                        handlingCallback = true;
                        setButtons[slot].set(false);
                        handlingCallback = false;
                        startCapture(slot);
                    }
                })
                .build()
            );

            clearButtons[i] = sgSlots.add(new BoolSetting.Builder()
                .name("slot-" + (i + 1) + "-clear")
                .description("Clear slot " + (i + 1) + ".")
                .defaultValue(false)
                .onChanged(v -> {
                    if (handlingCallback) return;
                    if (v) {
                        handlingCallback = true;
                        clearButtons[slot].set(false);
                        handlingCallback = false;
                        clearSlot(slot);
                    }
                })
                .build()
            );
        }

        setButtons[5] = sgSlots.add(new BoolSetting.Builder()
            .name("slot-6-set-here")
            .description("Slot 6: instantly store your current position.")
            .defaultValue(false)
            .onChanged(v -> {
                if (handlingCallback) return;
                if (v) {
                    handlingCallback = true;
                    setButtons[5].set(false);
                    handlingCallback = false;
                    setHere();
                }
            })
            .build()
        );

        clearButtons[5] = sgSlots.add(new BoolSetting.Builder()
            .name("slot-6-clear")
            .description("Clear slot 6.")
            .defaultValue(false)
            .onChanged(v -> {
                if (handlingCallback) return;
                if (v) {
                    handlingCallback = true;
                    clearButtons[5].set(false);
                    handlingCallback = false;
                    clearSlot(5);
                }
            })
            .build()
        );
    }

    private void buildColorSettings() {
        int[][] defaults = {
            {255,  80,  80, 200},
            {255, 165,   0, 200},
            {230, 220,  50, 200},
            { 60, 210,  60, 200},
            { 60, 140, 255, 200},
            {200,  60, 255, 200},
        };
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            int[] d = defaults[i];
            slotColors[i] = sgColors.add(new ColorSetting.Builder()
                .name("slot-" + (i + 1) + "-color")
                .description("Color for slot " + (i + 1) + ".")
                .defaultValue(new SettingColor(d[0], d[1], d[2], d[3]))
                .build()
            );
        }
    }

    private void startCapture(int slot) {
        if (mc.player == null) return;
        if (capturingSlot >= 0) sendMsg("§cCancelled previous slot " + (capturingSlot + 1) + " capture.");
        if (mc.currentScreen != null) mc.currentScreen.close();
        capturingSlot = slot;
        sendMsg("§eLeft-click a block to set Slot " + (slot + 1) + ". Press §cEsc §eto cancel.");
    }

    public boolean onBlockAttack(BlockPos pos) {
        if (capturingSlot < 0 || capturingSlot >= CLICK_SLOTS || mc.player == null) return false;
        blockPos[capturingSlot]  = pos;
        playerPos[capturingSlot] = mc.player.getBlockPos();
        sendMsg("§aSlot " + (capturingSlot + 1) + " set → block " + fmt(pos) + "  feet " + fmt(playerPos[capturingSlot]));
        capturingSlot = -1;
        return true;
    }

    public void onEscapePressed() {
        if (capturingSlot < 0) return;
        sendMsg("§cSlot " + (capturingSlot + 1) + " capture cancelled.");
        capturingSlot = -1;
    }

    private void setHere() {
        if (mc.player == null) return;
        slot6Pos = mc.player.getBlockPos();
        sendMsg("§aSlot 6 set → " + fmt(slot6Pos));
    }

    private void clearSlot(int slot) {
        if (slot == 5) {
            slot6Pos = null;
        } else {
            blockPos[slot]  = null;
            playerPos[slot] = null;
        }
        if (capturingSlot == slot) capturingSlot = -1;
        sendMsg("§7Slot " + (slot + 1) + " cleared.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        int a = opacity.get();
        for (int i = 0; i < CLICK_SLOTS; i++) {
            SettingColor col = slotColors[i].get();
            if (blockPos[i]  != null) renderBlockBox(event, blockPos[i], col, a);
            if (playerPos[i] != null) renderFeetMarker(event, playerPos[i], col, a);
        }
        if (slot6Pos != null) renderBlockBox(event, slot6Pos, slotColors[5].get(), a);
    }

    private void renderBlockBox(Render3DEvent ev, BlockPos pos, SettingColor c, int a) {
        Color sides = new Color(c.r, c.g, c.b, Math.max(0, a / 5));
        Color lines = new Color(c.r, c.g, c.b, a);
        ev.renderer.box(pos, sides, lines, ShapeMode.Both, 0);
    }

    private void renderFeetMarker(Render3DEvent ev, BlockPos pos, SettingColor c, int a) {
        double cx = pos.getX() + 0.5, cy = pos.getY(), cz = pos.getZ() + 0.5, h = 0.18;
        Color sides = new Color(c.r, c.g, c.b, Math.max(0, a / 3));
        Color lines = new Color(c.r, c.g, c.b, a);
        ev.renderer.box(cx - h, cy, cz - h, cx + h, cy + 0.32, cz + h, sides, lines, ShapeMode.Both, 0);
    }

    public boolean isCapturing() { return capturingSlot >= 0; }

    private void sendMsg(String msg) {
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("§8[§bPositionSelector§8] §r" + msg), true);
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
