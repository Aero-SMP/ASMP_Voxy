package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
//? if 1.20.1 {
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.screen.ConfigCorruptedScreen;
import me.cortex.voxy.client.config.VoxyConfigScreenPages;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumOptionsGUI;
//? } else {
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
//? }


public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (VoxyCommon.isAvailable()) {
//? if 1.20.1 {
                var screen = AccessorSodiumOptionsGUI.newScreen(parent);
                //Sorry jelly and douira, please dont hurt me
                try {
                    //We cant use .setPage() as that invokes rebuildGui, however the screen hasnt been initalized yet
                    // causing things to crash
                    var field = SodiumOptionsGUI.class.getDeclaredField("currentPage");
                    field.setAccessible(true);
                    field.set(screen, VoxyConfigScreenPages.voxyOptionPage);
                    field.setAccessible(false);
                } catch (Exception e) {
                    Logger.error("Failed to set the current page to voxy", e);
                }
//? } else {
                var page = (OptionPage) ConfigManager.CONFIG.getModOptions().stream().filter(a->a.configId().equals("voxy")).findFirst().get().pages().get(0);
                var screen = (VideoSettingsScreen)VideoSettingsScreen.createScreen(parent, page);
//? }
                return screen;
            } else {
                return null;
            }
        };
    }
}