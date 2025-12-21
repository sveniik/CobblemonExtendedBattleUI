package com.cobblemonextendedbattleui

import net.minecraft.client.gui.DrawContext

/**
 * Renders battle information overlays.
 */
object BattleInfoRenderer {

    fun render(context: DrawContext) {
        TeamIndicatorUI.render(context)
        BattleInfoPanel.render(context)
        BattleLogWidget.render(context)

        // Tooltip renders LAST to appear on top of everything
        TeamIndicatorUI.renderHoverTooltip(context)
    }
}
