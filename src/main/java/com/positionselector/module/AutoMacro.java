package com.positionselector.module;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayDeque;
import java.util.Deque;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoMacro extends Module {

    private static final int MAX_MACROS   = 10;
    private static final int LINES_PER    = 10;

    // ── General ────────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Integer> macroCount = sgGeneral.add(new IntSetting.Builder()
        .name("macro-count")
        .description("How many macros to use.")
        .defaultValue(1)
        .min(1)
        .sliderMax(MAX_MACROS)
        .build()
    );

    // ── Per-macro settings (built in constructor loop) ──────────────────────
    @SuppressWarnings("unchecked")
    private final Setting<String>[]   triggers = new Setting[MAX_MACROS];

    @SuppressWarnings("unchecked")
    private final Setting<String>[][] lines    = new Setting[MAX_MACROS][LINES_PER];

    @SuppressWarnings("unchecked")
    private final Setting<Integer>[][] delays   = new Setting[MAX_MACROS][LINES_PER - 1];

    // ── Queue ───────────────────────────────────────────────────────────────
    private record MacroStep(String command, int delayAfterMs) {}
    private final Deque<MacroStep> queue = new ArrayDeque<>();
    private long nextActionTime = 0;

    public AutoMacro() {
        super(AutoChestSteal.STORAGE, "auto-macro",
            "Sends command sequences when triggered by chat messages.");

        for (int m = 0; m < MAX_MACROS; m++) {
            final int mi = m;
            SettingGroup group = settings.createGroup("Macro " + (m + 1));

            // Trigger keyword
            triggers[m] = group.add(new StringSetting.Builder()
                .name("trigger")
                .description("Chat keyword that fires this macro.")
                .defaultValue("")
                .visible(() -> macroCount.get() > mi)
                .build()
            );

            // Lines + interleaved delays
            for (int l = 0; l < LINES_PER; l++) {
                final int li = l;

                lines[m][l] = group.add(new StringSetting.Builder()
                    .name("line-" + (l + 1))
                    .description("Command or message to send.")
                    .defaultValue("")
                    .visible(() -> macroCount.get() > mi)
                    .build()
                );

                // Delay after every line except the last
                if (l < LINES_PER - 1) {
                    delays[m][l] = group.add(new IntSetting.Builder()
                        .name("delay-" + (l + 1) + "->" + (l + 2) + "-ms")
                        .description("Delay in ms between line " + (l + 1) + " and line " + (l + 2) + ".")
                        .defaultValue(500)
                        .min(0)
                        .sliderMax(10000)
                         .visible(() -> macroCount.get() > mi) // ← this line was missing
                        .build()
                    );
                }
            }
        }
    }

    // ── Chat listener ───────────────────────────────────────────────────────
    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (!isActive()) return;
        if (!queue.isEmpty()) return; // block new triggers while one is running

        String content = event.getMessage().getString();

        for (int m = 0; m < macroCount.get(); m++) {
            String trigger = triggers[m].get().trim();
            if (trigger.isEmpty()) continue;
            if (!content.toLowerCase().contains(trigger.toLowerCase())) continue;

            // Build queue from non-empty lines only
            for (int l = 0; l < LINES_PER; l++) {
                String line = lines[m][l].get().trim();
                if (line.isEmpty()) continue;

                int delayAfter = (l < LINES_PER - 1) ? delays[m][l].get() : 0;
                queue.add(new MacroStep(line, delayAfter));
            }

            nextActionTime = 0; // fire first command immediately
            break; // first matching macro wins
        }
    }

    // ── Tick processor ──────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || queue.isEmpty()) return;
        if (System.currentTimeMillis() < nextActionTime) return;

        MacroStep step = queue.poll();
        if (step == null) return;

        sendChat(step.command());
        nextActionTime = System.currentTimeMillis() + step.delayAfterMs();
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────
    @Override
    public void onDeactivate() {
        queue.clear();
        nextActionTime = 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private void sendChat(String msg) {
        if (mc.player == null || msg == null || msg.isEmpty()) return;
        if (msg.startsWith("/")) {
            mc.player.networkHandler.sendChatCommand(msg.substring(1));
        } else {
            mc.player.networkHandler.sendChatMessage(msg);
        }
    }
}