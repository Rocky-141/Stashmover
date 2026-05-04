package com.positionselector.commands;

import meteordevelopment.meteorclient.commands.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class PositionCenterCommand extends Command {
    public PositionCenterCommand() {
        super("center", "centers you on block");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            if (mc.player == null) {
                error("Player not found.");
                return SINGLE_SUCCESS;
            }

            BlockPos pos = mc.player.getBlockPos();

            double centerX = pos.getX() + 0.5;
            double centerY = mc.player.getY();
            double centerZ = pos.getZ() + 0.5;

            mc.player.setPosition(centerX, centerY, centerZ);

            
            return SINGLE_SUCCESS;
        });
    }
}
