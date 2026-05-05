package test.StashMover.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.Queue;

import static test.StashMover.StashMoverAddon.CATEGORY;

public class SystemStatsHud extends HudElement {
    public static final HudElementInfo<SystemStatsHud> INFO = new HudElementInfo<>(
        meteordevelopment.meteorclient.systems.hud.Hud.GROUP,
        "system-stats",
        "Displays CPU, GPU, and RAM usage.",
        SystemStatsHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showCpu = sgGeneral.add(new BoolSetting.Builder()
        .name("show-cpu")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showGpu = sgGeneral.add(new BoolSetting.Builder()
        .name("show-gpu")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showRam = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ram")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> averageSamples = sgGeneral.add(new IntSetting.Builder()
        .name("cpu-average-samples")
        .description("How many samples to average CPU over.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> smoothing = sgGeneral.add(new IntSetting.Builder()
        .name("smoothing")
        .description("Ticks between each update. Higher = slower but smoother.")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    // OSHI for RAM only
    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();
    private final GlobalMemory memory = hal.getMemory();

    // CPU
    private final Queue<Double> cpuSamples = new LinkedList<>();
    private int updateTimer = 0;
    private double cpuAverage = 0;
    private boolean cpuQueryRunning = false;

    // GPU
    private double gpuUsage = 0;
    private int gpuUpdateTimer = 0;
    private boolean gpuQueryRunning = false;

    public SystemStatsHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        updateTimer++;
        gpuUpdateTimer++;

        // CPU via PowerShell
        if (updateTimer >= smoothing.get() && !cpuQueryRunning) {
            updateTimer = 0;
            cpuQueryRunning = true;
            Thread cpuThread = new Thread(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-command",
                        "(Get-Counter '\\Processor(_Total)\\% Processor Time').CounterSamples.CookedValue"
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        double load = Double.parseDouble(line.trim());
                        cpuSamples.add(load);
                        if (cpuSamples.size() > averageSamples.get()) cpuSamples.poll();
                        cpuAverage = cpuSamples.stream().mapToDouble(d -> d).average().orElse(0);
                    }
                    p.waitFor();
                } catch (Exception e) {
                    cpuAverage = -1;
                } finally {
                    cpuQueryRunning = false;
                }
            });
            cpuThread.setDaemon(true);
            cpuThread.start();
        }

        // GPU via PowerShell
        if (gpuUpdateTimer >= smoothing.get() * 3 && !gpuQueryRunning) {
            gpuUpdateTimer = 0;
            gpuQueryRunning = true;
            Thread gpuThread = new Thread(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-command",
                        "(Get-Counter '\\GPU Engine(*engtype_3D)\\Utilization Percentage').CounterSamples | " +
                        "Measure-Object -Property CookedValue -Sum | Select-Object -ExpandProperty Sum"
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        gpuUsage = Math.min(Double.parseDouble(line.trim()), 100.0);
                    }
                    p.waitFor();
                } catch (Exception e) {
                    gpuUsage = -1;
                } finally {
                    gpuQueryRunning = false;
                }
            });
            gpuThread.setDaemon(true);
            gpuThread.start();
        }

        double ramUsed = (memory.getTotal() - memory.getAvailable()) / (double) memory.getTotal() * 100;

        double x = this.x;
        double y = this.y;

        // 1.21.4: textHeight() requires the shadow boolean parameter
        double lineHeight = renderer.textHeight(false) + 2;

        if (showCpu.get()) {
            String cpuText = cpuAverage < 0 ? "CPU: Unsupported" : String.format("CPU: %.1f%%", cpuAverage);
            renderer.text(cpuText, x, y, color.get(), true);
            y += lineHeight;
        }

        if (showGpu.get()) {
            String gpuText = gpuUsage < 0 ? "GPU: Unsupported" : String.format("GPU: %.1f%%", gpuUsage);
            renderer.text(gpuText, x, y, color.get(), true);
            y += lineHeight;
        }

        if (showRam.get()) {
            renderer.text(String.format("RAM: %.1f%%", ramUsed), x, y, color.get(), true);
            y += lineHeight;
        }

        // 1.21.4: setSize takes two ints, not floats
        setSize(200, (int)(y - this.y));
    }
}