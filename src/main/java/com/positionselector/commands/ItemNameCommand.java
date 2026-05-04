package com.positionselector.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;

public class ItemNameCommand extends Command {
    public ItemNameCommand() {
        super("itemname", "Copies the name of the item you're holding to your clipboard.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.player == null) {
                ChatUtils.error("Player not found.");
                return 0;
            }

            ItemStack stack = mc.player.getMainHandStack();

            if (stack.isEmpty()) {
                ChatUtils.error("You are not holding an item.");
                return 0;
            }

            String name = stack.getName().getString();

            mc.keyboard.setClipboard(name);

            ChatUtils.info("Copied item name: " + Formatting.AQUA + name);

            return 1;
        });
    }
}
