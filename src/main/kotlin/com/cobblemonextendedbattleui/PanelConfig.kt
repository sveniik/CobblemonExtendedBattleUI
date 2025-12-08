package com.cobblemonextendedbattleui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.io.File

/**
 * Persistent configuration for the battle info panel.
 */
object PanelConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("cobblemonextendedbattleui.json").toFile()
    }

    // Base dimensions (default size)
    const val DEFAULT_WIDTH = 200
    const val DEFAULT_HEIGHT = 250

    // Panel position (null = default right-center position)
    var panelX: Int? = null
        private set
    var panelY: Int? = null
        private set

    // Panel dimensions (null = use content-based sizing)
    var panelWidth: Int? = null
        private set
    var panelHeight: Int? = null
        private set

    // Font scale multiplier (user-adjustable via Ctrl+Scroll)
    var fontScale: Float = 1.0f
        private set

    // Content scroll offset
    var scrollOffset: Int = 0

    // Whether panel starts expanded
    var startExpanded: Boolean = false
        private set

    // Font scale limits
    const val MIN_FONT_SCALE = 0.5f
    const val MAX_FONT_SCALE = 2.0f
    const val FONT_SCALE_STEP = 0.05f

    // Maximum screen percentage for panel size
    const val MAX_SCREEN_PERCENTAGE = 0.85f

    data class ConfigData(
        val panelX: Int? = null,
        val panelY: Int? = null,
        val panelWidth: Int? = null,
        val panelHeight: Int? = null,
        val fontScale: Float = 1.0f,
        val startExpanded: Boolean = false
    )

    fun load() {
        try {
            if (configFile.exists()) {
                val data = gson.fromJson(configFile.readText(), ConfigData::class.java)
                panelX = data.panelX
                panelY = data.panelY
                panelWidth = data.panelWidth
                panelHeight = data.panelHeight
                fontScale = data.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                startExpanded = data.startExpanded
                CobblemonExtendedBattleUI.LOGGER.info("PanelConfig: Loaded config - pos=(${panelX}, ${panelY}), size=(${panelWidth}, ${panelHeight}), fontScale=$fontScale")
            }
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("PanelConfig: Failed to load config, using defaults: ${e.message}")
        }
    }

    fun save() {
        try {
            val data = ConfigData(panelX, panelY, panelWidth, panelHeight, fontScale, startExpanded)
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(data))
            CobblemonExtendedBattleUI.LOGGER.debug("PanelConfig: Saved config")
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("PanelConfig: Failed to save config: ${e.message}")
        }
    }

    fun setPosition(x: Int?, y: Int?) {
        panelX = x
        panelY = y
    }

    fun setDimensions(width: Int?, height: Int?) {
        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        panelWidth = width?.coerceIn(getMinWidth(), getMaxWidth(screenWidth))
        panelHeight = height?.coerceIn(getMinHeight(), getMaxHeight(screenHeight))
    }

    fun setStartExpanded(expanded: Boolean) {
        startExpanded = expanded
    }

    fun adjustFontScale(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    fun getMinWidth(): Int = DEFAULT_WIDTH / 2  // ~100px minimum

    fun getMinHeight(): Int = 60  // Just enough for header

    fun getMaxWidth(screenWidth: Int): Int = (screenWidth * MAX_SCREEN_PERCENTAGE).toInt()

    fun getMaxHeight(screenHeight: Int): Int = (screenHeight * MAX_SCREEN_PERCENTAGE).toInt()

    fun resetToDefaults() {
        panelX = null
        panelY = null
        panelWidth = null
        panelHeight = null
        fontScale = 1.0f
        startExpanded = false
        scrollOffset = 0
        save()
    }
}
