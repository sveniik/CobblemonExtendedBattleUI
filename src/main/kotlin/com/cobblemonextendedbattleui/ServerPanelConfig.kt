package com.cobblemonextendedbattleui

import com.cobblemonextendedbattleui.network.FeatureSyncS2CPayload
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Dedicated / integrated server: [SERVER_CONFIG_FILE] in the config directory.
 * Only feature toggles; each value can be pushed to clients when `sync_*` is true.
 */
object ServerPanelConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve(SERVER_CONFIG_FILE_NAME).toFile()
    }

    const val SERVER_CONFIG_FILE_NAME = "cobblemonextendedbattleui-server.json"

    data class Data(
        val schemaVersion: Int = 1,
        val enableTeamIndicators: Boolean = true,
        val sync_enableTeamIndicators: Boolean = false,
        val teamIndicatorRepositioningEnabled: Boolean = true,
        val sync_teamIndicatorRepositioningEnabled: Boolean = false,
        val enableBattleInfoPanel: Boolean = true,
        val sync_enableBattleInfoPanel: Boolean = false,
        val enableBattleLog: Boolean = true,
        val sync_enableBattleLog: Boolean = false,
        val enableBattleLogDamagePercentages: Boolean = true,
        val sync_enableBattleLogDamagePercentages: Boolean = false,
        val enableMoveTooltips: Boolean = true,
        val sync_enableMoveTooltips: Boolean = false,
        val showTeraType: Boolean = false,
        val sync_showTeraType: Boolean = false,
        val showStatRanges: Boolean = true,
        val sync_showStatRanges: Boolean = false,
        val showOpponentSpeedRange: Boolean = true,
        val sync_showOpponentSpeedRange: Boolean = false,
        val showBaseCritRate: Boolean = false,
        val sync_showBaseCritRate: Boolean = false
    )

    private var data: Data = Data()

    fun load() {
        try {
            if (configFile.exists()) {
                data = gson.fromJson(configFile.readText(), Data::class.java) ?: Data()
                CobblemonExtendedBattleUI.LOGGER.info("ServerPanelConfig: Loaded $SERVER_CONFIG_FILE_NAME")
            } else {
                data = Data()
                save()
                CobblemonExtendedBattleUI.LOGGER.info("ServerPanelConfig: Created default $SERVER_CONFIG_FILE_NAME")
            }
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("ServerPanelConfig: Failed to load, using defaults: ${e.message}")
            data = Data()
        }
    }

    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("ServerPanelConfig: Failed to save: ${e.message}")
        }
    }

    /**
     * Build payload for a joining client: only entries whose sync_* flag is true.
     */
    fun buildSyncPayload(): FeatureSyncS2CPayload {
        val d = data
        val entries = mutableListOf<Pair<String, Boolean>>()
        fun addIfSynced(sync: Boolean, key: String, value: Boolean) {
            if (sync) entries.add(key to value)
        }
        addIfSynced(d.sync_enableTeamIndicators, "enableTeamIndicators", d.enableTeamIndicators)
        addIfSynced(d.sync_teamIndicatorRepositioningEnabled, "teamIndicatorRepositioningEnabled", d.teamIndicatorRepositioningEnabled)
        addIfSynced(d.sync_enableBattleInfoPanel, "enableBattleInfoPanel", d.enableBattleInfoPanel)
        addIfSynced(d.sync_enableBattleLog, "enableBattleLog", d.enableBattleLog)
        addIfSynced(d.sync_enableBattleLogDamagePercentages, "enableBattleLogDamagePercentages", d.enableBattleLogDamagePercentages)
        addIfSynced(d.sync_enableMoveTooltips, "enableMoveTooltips", d.enableMoveTooltips)
        addIfSynced(d.sync_showTeraType, "showTeraType", d.showTeraType)
        addIfSynced(d.sync_showStatRanges, "showStatRanges", d.showStatRanges)
        addIfSynced(d.sync_showOpponentSpeedRange, "showOpponentSpeedRange", d.showOpponentSpeedRange)
        addIfSynced(d.sync_showBaseCritRate, "showBaseCritRate", d.showBaseCritRate)
        return FeatureSyncS2CPayload(FeatureSyncS2CPayload.SCHEMA_VERSION, entries)
    }
}
