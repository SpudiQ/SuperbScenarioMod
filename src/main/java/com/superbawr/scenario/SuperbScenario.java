package com.superbawr.scenario;

import com.mojang.logging.LogUtils;
import com.superbawr.scenario.commands.ScenarioCommands;
import com.superbawr.scenario.events.ScenarioEvents;
import com.superbawr.scenario.manager.ScenarioManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SuperbScenario.MOD_ID)
public class SuperbScenario {

    public static final String MOD_ID = "superbscenario";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ScenarioManager manager = new ScenarioManager();

    public SuperbScenario() {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new ScenarioEvents());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        manager.setServer(event.getServer());
        manager.loadClasses();
        manager.loadPositions();
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        ScenarioCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}
