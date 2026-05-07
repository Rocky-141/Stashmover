package com.positionselector.module;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PearlLoaderModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private int pendingClicks = 0;
    private int cooldownTimer = 0;

    private static final int COOLDOWN_TICKS = 4;

    private final Setting<String> triggerWord;

    public PearlLoaderModule() {
        super(AutoChestSteal.STORAGE, "pearl-loader", "Clicks when a PM contains the trigger word.");

        triggerWord = sgGeneral.add(new StringSetting.Builder()
            .name("trigger-word")
            .description("Word to detect in private messages.")
            .defaultValue("")
            .build()
        );
    }

    @Override
    public void onActivate() {
        pendingClicks = 0;
        cooldownTimer = 0;
    }

    @Override
    public void onDeactivate() {
        pendingClicks = 0;
        cooldownTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (cooldownTimer > 0) {
            cooldownTimer--;
            return;
        }

        if (pendingClicks > 0) {
            pendingClicks--;
            cooldownTimer = COOLDOWN_TICKS;

            HitResult hit = mc.crosshairTarget;
            if (hit != null) {
                switch (hit.getType()) {
                    case ENTITY -> {
                        if (hit instanceof EntityHitResult ehr)
                            mc.interactionManager.interactEntity(mc.player, ehr.getEntity(), Hand.MAIN_HAND);
                    }
                    case BLOCK -> {
                        if (hit instanceof BlockHitResult bhr)
                            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                    }
                    default -> {}
                }
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive()) return;

        String word = triggerWord.get();
        if (word == null || word.isBlank()) return;

        String message = event.getMessage().getString();
        if (!message.contains(":")) return;
        if (!message.contains(word)) return;

        pendingClicks++;
    }
}