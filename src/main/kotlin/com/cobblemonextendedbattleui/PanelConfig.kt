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

    // ═══════════════════════════════════════════════════════════════════════════
    // Configuration values
    // ═══════════════════════════════════════════════════════════════════════════

    // Base dimensions (default size)
    const val DEFAULT_WIDTH = 200

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Battle log widget settings
    // ═══════════════════════════════════════════════════════════════════════════

    // Whether to replace Cobblemon's battle log with our custom log
    var replaceBattleLog: Boolean = true
        private set

    // Log widget position (null = default bottom-center position)
    var logX: Int? = null
        private set
    var logY: Int? = null
        private set

    // Log widget dimensions
    var logWidth: Int? = null
        private set
    var logHeight: Int? = null
        private set

    // Log widget font scale (separate from main panel)
    var logFontScale: Float = 1.0f
        private set

    // Log widget expanded state
    var logExpanded: Boolean = true
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // Team indicator tooltip settings
    // ═══════════════════════════════════════════════════════════════════════════

    // Tooltip font scale (separate from other panels)
    var tooltipFontScale: Float = 1.0f
        private set

    // Default log dimensions
    const val DEFAULT_LOG_WIDTH = 200
    const val DEFAULT_LOG_HEIGHT = 120
    const val MIN_LOG_WIDTH = 120
    const val MIN_LOG_HEIGHT = 60
    const val MAX_LOG_WIDTH = 400
    const val MAX_LOG_HEIGHT = 300

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    // Font scale limits
    const val MIN_FONT_SCALE = 0.5f
    const val MAX_FONT_SCALE = 2.0f
    const val FONT_SCALE_STEP = 0.05f

    // Maximum screen percentage for panel size
    const val MAX_SCREEN_PERCENTAGE = 0.85f

    // ═══════════════════════════════════════════════════════════════════════════
    // Data class for serialization
    // ═══════════════════════════════════════════════════════════════════════════

    data class ConfigData(
        val panelX: Int? = null,
        val panelY: Int? = null,
        val panelWidth: Int? = null,
        val panelHeight: Int? = null,
        val fontScale: Float = 1.0f,
        val startExpanded: Boolean = false,
        // Battle log widget settings
        val replaceBattleLog: Boolean = true,
        val logX: Int? = null,
        val logY: Int? = null,
        val logWidth: Int? = null,
        val logHeight: Int? = null,
        val logFontScale: Float = 1.0f,
        val logExpanded: Boolean = true,
        // Tooltip settings
        val tooltipFontScale: Float = 1.0f
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
                // Battle log widget settings
                replaceBattleLog = data.replaceBattleLog
                logX = data.logX
                logY = data.logY
                logWidth = data.logWidth
                logHeight = data.logHeight
                logFontScale = data.logFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                logExpanded = data.logExpanded
                tooltipFontScale = data.tooltipFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                CobblemonExtendedBattleUI.LOGGER.info("PanelConfig: Loaded config - panel pos=(${panelX}, ${panelY}), log pos=(${logX}, ${logY})")
            }
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("PanelConfig: Failed to load config, using defaults: ${e.message}")
        }
    }

    fun save() {
        try {
            val data = ConfigData(
                panelX = panelX,
                panelY = panelY,
                panelWidth = panelWidth,
                panelHeight = panelHeight,
                fontScale = fontScale,
                startExpanded = startExpanded,
                replaceBattleLog = replaceBattleLog,
                logX = logX,
                logY = logY,
                logWidth = logWidth,
                logHeight = logHeight,
                logFontScale = logFontScale,
                logExpanded = logExpanded,
                tooltipFontScale = tooltipFontScale
            )
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Log widget management
    // ═══════════════════════════════════════════════════════════════════════════

    fun setLogPosition(x: Int?, y: Int?) {
        logX = x
        logY = y
    }

    fun setLogDimensions(width: Int?, height: Int?) {
        logWidth = width?.coerceIn(MIN_LOG_WIDTH, MAX_LOG_WIDTH)
        logHeight = height?.coerceIn(MIN_LOG_HEIGHT, MAX_LOG_HEIGHT)
    }

    fun adjustLogFontScale(delta: Float) {
        logFontScale = (logFontScale + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    fun adjustTooltipFontScale(delta: Float) {
        tooltipFontScale = (tooltipFontScale + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
    }

    /**
     * Toggle the replaceBattleLog setting.
     */
    fun toggleReplaceBattleLog() {
        replaceBattleLog = !replaceBattleLog
        save()
    }
}
