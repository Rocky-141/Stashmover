package com.positionselector;

import com.positionselector.module.AutoChestSteal;
import com.positionselector.module.AutoStashSteal;
import com.positionselector.module.AutoChestDeposit;
import com.positionselector.module.PearlLoaderModule;
import com.positionselector.module.AutoStashDeposit;
import com.positionselector.module.AutoStashCycle;
import com.positionselector.module.AutoMacro;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.commands.Commands;

import com.positionselector.commands.PositionCenterCommand;
import com.positionselector.commands.PositionLookCommand;
import com.positionselector.commands.ItemNameCommand;

public class PositionSelectorAddon extends MeteorAddon {

    @Override
    public void onInitialize() {

        // Register modules
        Modules.get().add(new AutoChestSteal());
        Modules.get().add(new AutoChestDeposit());
        Modules.get().add(new PearlLoaderModule());
        Modules.get().add(new AutoStashSteal());
        Modules.get().add(new AutoStashDeposit());
        Modules.get().add(new AutoStashCycle());
        Modules.get().add(new AutoMacro());

        // Register commands
        Commands.add(new PositionCenterCommand());
        Commands.add(new PositionLookCommand());
        Commands.add(new ItemNameCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(AutoChestSteal.STORAGE);
    }

    @Override
    public String getPackage() {
        return "com.positionselector";
    }
}
