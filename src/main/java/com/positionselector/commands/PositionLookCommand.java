package com.positionselector.commands;

import meteordevelopment.meteorclient.commands.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class PositionLookCommand extends Command {
    public PositionLookCommand() {
        super("look", "looks at place (yaw and pitch)");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("yaw", FloatArgumentType.floatArg())
            .then(argument("pitch", FloatArgumentType.floatArg())
                .executes(ctx -> {
                    if (mc.player == null) {
                        error("Player not found.");
                        return SINGLE_SUCCESS;
                    }

                    float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                    float pitch = FloatArgumentType.getFloat(ctx, "pitch");

                    mc.player.setYaw(yaw);
                    mc.player.setPitch(pitch);

                    info("Set rotation to yaw " + yaw + " pitch " + pitch);
                    return SINGLE_SUCCESS;
                })
            )
        );
    }
}
