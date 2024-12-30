package ichttt.mods.sendmoarchunks.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SendMoarChunksClient implements ClientModInitializer {
    public static final boolean DEBUG = false;
    public static final Logger LOGGER = LoggerFactory.getLogger("SendMoarChunks");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing SendMoarChunks!");
    }
}
