package com.cobblemonextendedbattleui

import com.cobblemonextendedbattleui.network.FeatureSyncS2CPayload
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

object CobblemonExtendedBattleUI : ModInitializer {
    const val MOD_ID = "cobblemonextendedbattleui"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(FeatureSyncS2CPayload.ID, FeatureSyncS2CPayload.CODEC)

        ServerLifecycleEvents.SERVER_STARTING.register { _ ->
            ServerPanelConfig.load()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            ServerPlayNetworking.send(player, ServerPanelConfig.buildSyncPayload())
        }

        LOGGER.info("Cobblemon Extended Battle UI initialized")
    }
}
