package test.StashMover.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class NetworkStatsHud extends HudElement {

    public enum GraphType { Bars, Line }

    public static final HudElementInfo<NetworkStatsHud> INFO =
        new HudElementInfo<>(meteordevelopment.meteorclient.systems.hud.Hud.GROUP, "network-stats", "Real-time network jitter and ping.", NetworkStatsHud::new);

    private final SettingGroup sgGraph = settings.createGroup("Graph");
    private final SettingGroup sgAppearance = settings.createGroup("Appearance");

    private final Setting<GraphType> graphType = sgGraph.add(new EnumSetting.Builder<GraphType>().name("graph-type").defaultValue(GraphType.Line).build());
    
    // ⭐ Minimum is now 0 for frame-perfect updates
    private final Setting<Integer> updateRate = sgGraph.add(new IntSetting.Builder()
        .name("update-rate")
        .description("Delay between samples (ms). Set to 0 for every frame.")
        .defaultValue(50)
        .min(0) 
        .sliderMax(1000)
        .build());

    private final Setting<Integer> graphWidth = sgGraph.add(new IntSetting.Builder().name("graph-width").defaultValue(120).min(50).sliderMax(400).build());
    private final Setting<Integer> graphHeight = sgGraph.add(new IntSetting.Builder().name("graph-height").defaultValue(40).min(10).sliderMax(200).build());
    private final Setting<Integer> maxPing = sgGraph.add(new IntSetting.Builder().name("max-ping").defaultValue(500).min(50).sliderMax(2000).build());
    private final Setting<SettingColor> graphColor = sgGraph.add(new ColorSetting.Builder().name("graph-color").defaultValue(new SettingColor(0, 255, 150, 255)).build());
    
    private final Setting<Double> scale = sgAppearance.add(new DoubleSetting.Builder().name("scale").defaultValue(1.0).build());
    private final Setting<Boolean> background = sgAppearance.add(new BoolSetting.Builder().name("background").defaultValue(true).build());
    private final Setting<SettingColor> bgColor = sgAppearance.add(new ColorSetting.Builder().name("background-color").defaultValue(new SettingColor(0, 0, 0, 120)).build());
    private final Setting<Integer> padding = sgAppearance.add(new IntSetting.Builder().name("padding").defaultValue(5).build());

    private final LinkedList<Double> pingHistory = new LinkedList<>();
    private long lastUpdate = 0;

    public NetworkStatsHud() { super(INFO); }

    @Override
    public void render(HudRenderer renderer) {
        MinecraftClient mc = MinecraftClient.getInstance();
        
        if (mc.world == null || mc.getNetworkHandler() == null || mc.player == null) {
            pingHistory.clear();
            setSize(120, 20);
            return;
        }

        double currentPing = 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getGameProfile().getId());
        if (entry != null) {
            currentPing = entry.getLatency();
        }
        
        // Logic to update: Always update if rate is 0, otherwise check timer
        long now = System.currentTimeMillis();
        if (updateRate.get() == 0 || (now - lastUpdate > updateRate.get())) {
            pingHistory.addLast(currentPing);
            while (pingHistory.size() > graphWidth.get() / 2) pingHistory.removeFirst();
            lastUpdate = now;
        }

        double s = scale.get();
        int pad = padding.get();
        
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Ping: %.0fms", currentPing));

        double lineH = renderer.textHeight(false) * s;
        double textW = 0;
        for (String line : lines) textW = Math.max(textW, renderer.textWidth(line, false) * s);

        double totalW = Math.max(textW, (double) graphWidth.get()) + (pad * 2);
        double totalH = (lineH * lines.size()) + graphHeight.get() + (pad * 2) + 2;

        setSize((int) Math.ceil(totalW), (int) Math.ceil(totalH));

        if (background.get()) renderer.quad(x, y, totalW, totalH, bgColor.get());

        double ty = y + pad;
        for (String line : lines) {
            renderer.text(line, x + pad, ty, new SettingColor(255, 255, 255), false);
            ty += lineH;
        }

        if (!pingHistory.isEmpty()) {
            double gx = x + pad;
            double gy = ty + 2;
            double step = 2.0;
            double mP = maxPing.get();
            SettingColor c = graphColor.get();

            if (graphType.get() == GraphType.Bars) {
                for (Double p : pingHistory) {
                    double h = (Math.min(p, mP) / mP) * graphHeight.get();
                    renderer.quad(gx, gy + graphHeight.get() - h, step - 0.5, h, c);
                    gx += step;
                }
            } else {
                double lastX = -1, lastY = -1;
                for (Double p : pingHistory) {
                    double h = (Math.min(p, mP) / mP) * graphHeight.get();
                    double cy = gy + graphHeight.get() - h;

                    if (lastX != -1) {
                        renderer.line(lastX, lastY, gx, cy, c);
                    }
                    renderer.quad(gx - 0.5, cy - 0.5, 1, 1, c);
                    
                    lastX = gx;
                    lastY = cy;
                    gx += step;
                }
            }
        }
    }
}