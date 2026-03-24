package com.cobblemonextendedbattleui

import com.cobblemonextendedbattleui.network.FeatureSyncS2CPayload

/**
 * Client-only: server-forced feature toggles received on join. Cleared on disconnect.
 */
object ClientServerFeatureSync {
    private val overrides: MutableMap<String, Boolean> = linkedMapOf()

    fun clear() {
        overrides.clear()
    }

    fun applyPayload(payload: FeatureSyncS2CPayload) {
        overrides.clear()
        if (payload.schemaVersion != FeatureSyncS2CPayload.SCHEMA_VERSION) {
            CobblemonExtendedBattleUI.LOGGER.warn(
                "ClientServerFeatureSync: Ignoring feature sync packet (schema ${payload.schemaVersion}, expected ${FeatureSyncS2CPayload.SCHEMA_VERSION})"
            )
            return
        }
        for ((key, value) in payload.entries) {
            overrides[key] = value
        }
        CobblemonExtendedBattleUI.LOGGER.debug("ClientServerFeatureSync: Applied ${overrides.size} server overrides")
    }

    fun getOverride(key: String): Boolean? = overrides[key]
}
