package test.StashMover.module;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;

import static test.StashMover.StashMoverAddon.CATEGORY;

public class SixBAutoRespawn extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ms")
        .description("Delay before clicking respawn.")
        .defaultValue(300)
        .min(0)
        .sliderMax(2000)
        .build()
    );

    private long nextRespawnTime = 0;

    public SixBAutoRespawn() {
        super(AutoChestSteal.STORAGE, "6b-auto-respawn",
            "performs Double respawn for 6b's dual respawn system.");
    }
    public void onActivate() {
        nextRespawnTime = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        // Only act when the death screen is open
        if (!(mc.currentScreen instanceof DeathScreen)) return;

        long now = System.currentTimeMillis();

        // First detection: schedule the click
        if (nextRespawnTime == 0) {
            nextRespawnTime = now + delay.get();
            return;
        }

        // Time to click respawn
        if (now >= nextRespawnTime) {
            try {
                mc.player.requestRespawn();
                mc.setScreen(null);
            } catch (Exception ignored) {}

            nextRespawnTime = 0; // reset for next respawn screen
        }
    }
}
