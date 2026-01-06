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
    // Feature toggles (disable entire features)
    // ═══════════════════════════════════════════════════════════════════════════

    // Enable/disable team indicator displays under health bars
    var enableTeamIndicators: Boolean = true
        private set

    // Enable/disable the battle info panel (weather, terrain, conditions, stats)
    var enableBattleInfoPanel: Boolean = true
        private set

    // Enable/disable the custom battle log (replaces Cobblemon's chat-based log)
    var enableBattleLog: Boolean = true
        private set

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

    // Expanded panel dimensions (null = use content-based sizing)
    var panelWidth: Int? = null
        private set
    var panelHeight: Int? = null
        private set

    // Collapsed panel dimensions (null = use content-based sizing)
    var collapsedWidth: Int? = null
        private set
    var collapsedHeight: Int? = null
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
        // Feature toggles
        val enableTeamIndicators: Boolean = true,
        val enableBattleInfoPanel: Boolean = true,
        val enableBattleLog: Boolean = true,
        // Panel settings
        val panelX: Int? = null,
        val panelY: Int? = null,
        val panelWidth: Int? = null,
        val panelHeight: Int? = null,
        val collapsedWidth: Int? = null,
        val collapsedHeight: Int? = null,
        val fontScale: Float = 1.0f,
        val startExpanded: Boolean = false,
        // Battle log widget settings
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
                // Feature toggles
                enableTeamIndicators = data.enableTeamIndicators
                enableBattleInfoPanel = data.enableBattleInfoPanel
                enableBattleLog = data.enableBattleLog
                // Panel settings
                panelX = data.panelX
                panelY = data.panelY
                panelWidth = data.panelWidth
                panelHeight = data.panelHeight
                collapsedWidth = data.collapsedWidth
                collapsedHeight = data.collapsedHeight
                fontScale = data.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                startExpanded = data.startExpanded
                // Battle log widget settings
                logX = data.logX
                logY = data.logY
                logWidth = data.logWidth
                logHeight = data.logHeight
                logFontScale = data.logFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                logExpanded = data.logExpanded
                tooltipFontScale = data.tooltipFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
                CobblemonExtendedBattleUI.LOGGER.info("PanelConfig: Loaded config - features: team=$enableTeamIndicators, panel=$enableBattleInfoPanel, log=$enableBattleLog")
            }
        } catch (e: Exception) {
            CobblemonExtendedBattleUI.LOGGER.warn("PanelConfig: Failed to load config, using defaults: ${e.message}")
        }
    }

    fun save() {
        try {
            val data = ConfigData(
                enableTeamIndicators = enableTeamIndicators,
                enableBattleInfoPanel = enableBattleInfoPanel,
                enableBattleLog = enableBattleLog,
                panelX = panelX,
                panelY = panelY,
                panelWidth = panelWidth,
                panelHeight = panelHeight,
                collapsedWidth = collapsedWidth,
                collapsedHeight = collapsedHeight,
                fontScale = fontScale,
                startExpanded = startExpanded,
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

    fun setCollapsedDimensions(width: Int?, height: Int?) {
        val mc = MinecraftClient.getInstance()
        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight

        collapsedWidth = width?.coerceIn(getMinWidth(), getMaxWidth(screenWidth))
        collapsedHeight = height?.coerceIn(getMinCollapsedHeight(), getMaxHeight(screenHeight))
    }

    fun getMinCollapsedHeight(): Int = 40  // Smaller minimum for collapsed

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Feature toggle helpers (for mixins to check tracking dependencies)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if BattleStateTracker should process messages.
     * Needed for both battle info panel (weather, terrain, conditions, stats)
     * and team indicator tooltips (stat changes, volatile statuses).
     */
    fun needsBattleStateTracking(): Boolean = enableBattleInfoPanel || enableTeamIndicators

    /**
     * Returns true if DamageTracker should track HP changes.
     * Only needed for battle log damage/healing percentages.
     */
    fun needsDamageTracking(): Boolean = enableBattleLog
}
