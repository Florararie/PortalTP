package com.floramene.portaltp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PortalTp implements ModInitializer {
    public static final Logger LOG = LogManager.getLogger("PortalTP");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                PortalTpCommand.register(dispatcher));
        LOG.info("PortalTP loaded: /portaltp <target> <x> <y> <z>");
    }
}