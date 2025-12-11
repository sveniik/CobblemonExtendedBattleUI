package com.cobblemonextendedbattleui

import com.cobblemon.mod.common.client.render.drawScaledText
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Shared UI utilities to avoid code duplication between BattleInfoPanel and BattleLogWidget.
 */
object UIUtils {

    // ═══════════════════════════════════════════════════════════════════════════
    // Panel interaction tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Identifies which panel is being interacted with.
     */
    enum class ActivePanel {
        NONE, INFO_PANEL, BATTLE_LOG
    }

    /**
     * Currently active panel for drag/resize operations.
     * Only one panel can be interacted with at a time.
     */
    var activePanel: ActivePanel = ActivePanel.NONE
        private set

    /**
     * Attempts to claim interaction for a panel.
     * Returns true if successful (no other panel is active).
     */
    fun claimInteraction(panel: ActivePanel): Boolean {
        if (activePanel == ActivePanel.NONE || activePanel == panel) {
            activePanel = panel
            return true
        }
        return false
    }

    /**
     * Releases interaction claim for a panel.
     */
    fun releaseInteraction(panel: ActivePanel) {
        if (activePanel == panel) {
            activePanel = ActivePanel.NONE
        }
    }

    /**
     * Checks if interaction is available for a panel.
     */
    fun canInteract(panel: ActivePanel): Boolean {
        return activePanel == ActivePanel.NONE || activePanel == panel
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Color utilities
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates an ARGB color from component values.
     */
    fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ═══════════════════════════════════════════════════════════════════════════
    // Resize zones (shared enum)
    // ═══════════════════════════════════════════════════════════════════════════

    enum class ResizeZone {
        NONE, LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scissor management
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tracks current scissor bounds for manual clipping checks.
     */
    data class ScissorBounds(var minY: Int = 0, var maxY: Int = 0)

    /**
     * Enables scissor test with the given bounds.
     * Returns ScissorBounds for use with drawTextClipped.
     */
    fun enableScissor(x: Int, y: Int, width: Int, height: Int): ScissorBounds {
        val mc = MinecraftClient.getInstance()
        val scale = mc.window.scaleFactor

        val scaledX = (x * scale).toInt()
        val scaledY = ((mc.window.scaledHeight - y - height) * scale).toInt()
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        RenderSystem.enableScissor(scaledX, scaledY, scaledWidth, scaledHeight)
        return ScissorBounds(y, y + height)
    }

    /**
     * Disables scissor test.
     */
    fun disableScissor() {
        RenderSystem.disableScissor()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Text rendering
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Draws scaled text with shadow using Cobblemon's renderer.
     */
    fun drawText(context: DrawContext, text: String, x: Float, y: Float, color: Int, scale: Float) {
        drawScaledText(
            context = context,
            text = Text.literal(text),
            x = x,
            y = y,
            scale = scale,
            colour = color,
            shadow = true
        )
    }

    /**
     * Draws scaled text only if within scissor bounds (Y axis).
     */
    fun drawTextClipped(
        context: DrawContext,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        scale: Float,
        bounds: ScissorBounds
    ) {
        val textHeight = (8 * scale).toInt()
        if (y + textHeight < bounds.minY || y > bounds.maxY) return
        drawText(context, text, x, y, color, scale)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input utilities
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks if a key or mouse button is currently pressed.
     * Works with both keyboard keys and mouse buttons.
     */
    fun isKeyOrButtonPressed(handle: Long, key: InputUtil.Key): Boolean {
        return when (key.category) {
            InputUtil.Type.MOUSE -> GLFW.glfwGetMouseButton(handle, key.code) == GLFW.GLFW_PRESS
            else -> GLFW.glfwGetKey(handle, key.code) == GLFW.GLFW_PRESS
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Resize zone detection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determines which resize zone the mouse is hovering over.
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     * @param x Widget X position
     * @param y Widget Y position
     * @param w Widget width
     * @param h Widget height
     * @param handleSize Size of the resize handle detection area
     * @param enabled Whether resizing is currently allowed
     */
    fun getResizeZone(
        mouseX: Int,
        mouseY: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        handleSize: Int,
        enabled: Boolean = true
    ): ResizeZone {
        if (!enabled) return ResizeZone.NONE

        val onLeft = mouseX >= x - handleSize && mouseX <= x + handleSize
        val onRight = mouseX >= x + w - handleSize && mouseX <= x + w + handleSize
        val onTop = mouseY >= y - handleSize && mouseY <= y + handleSize
        val onBottom = mouseY >= y + h - handleSize && mouseY <= y + h + handleSize
        val withinX = mouseX >= x - handleSize && mouseX <= x + w + handleSize
        val withinY = mouseY >= y - handleSize && mouseY <= y + h + handleSize

        return when {
            onTop && onLeft && withinX && withinY -> ResizeZone.TOP_LEFT
            onTop && onRight && withinX && withinY -> ResizeZone.TOP_RIGHT
            onBottom && onLeft && withinX && withinY -> ResizeZone.BOTTOM_LEFT
            onBottom && onRight && withinX && withinY -> ResizeZone.BOTTOM_RIGHT
            onLeft && withinY -> ResizeZone.LEFT
            onRight && withinY -> ResizeZone.RIGHT
            onTop && withinX -> ResizeZone.TOP
            onBottom && withinX -> ResizeZone.BOTTOM
            else -> ResizeZone.NONE
        }
    }

    /**
     * Calculates new dimensions and position based on resize zone and mouse delta.
     */
    data class ResizeResult(
        val newX: Int,
        val newY: Int,
        val newWidth: Int,
        val newHeight: Int
    )

    fun calculateResize(
        zone: ResizeZone,
        deltaX: Int,
        deltaY: Int,
        startX: Int,
        startY: Int,
        startWidth: Int,
        startHeight: Int,
        minWidth: Int,
        maxWidth: Int,
        minHeight: Int,
        maxHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ResizeResult {
        var newWidth = startWidth
        var newHeight = startHeight
        var newX = startX
        var newY = startY

        when (zone) {
            ResizeZone.RIGHT -> newWidth = startWidth + deltaX
            ResizeZone.BOTTOM -> newHeight = startHeight + deltaY
            ResizeZone.BOTTOM_RIGHT -> {
                newWidth = startWidth + deltaX
                newHeight = startHeight + deltaY
            }
            ResizeZone.LEFT -> {
                newWidth = startWidth - deltaX
                newX = startX + deltaX
            }
            ResizeZone.TOP -> {
                newHeight = startHeight - deltaY
                newY = startY + deltaY
            }
            ResizeZone.TOP_LEFT -> {
                newWidth = startWidth - deltaX
                newHeight = startHeight - deltaY
                newX = startX + deltaX
                newY = startY + deltaY
            }
            ResizeZone.TOP_RIGHT -> {
                newWidth = startWidth + deltaX
                newHeight = startHeight - deltaY
                newY = startY + deltaY
            }
            ResizeZone.BOTTOM_LEFT -> {
                newWidth = startWidth - deltaX
                newHeight = startHeight + deltaY
                newX = startX + deltaX
            }
            ResizeZone.NONE -> {}
        }

        // When resizing from left/top edges, we need to fix position when hitting limits
        // to prevent the panel from moving when it can't resize further
        if (zone in listOf(ResizeZone.LEFT, ResizeZone.TOP_LEFT, ResizeZone.BOTTOM_LEFT)) {
            if (newWidth < minWidth) {
                newX = startX + startWidth - minWidth
                newWidth = minWidth
            }
            if (newWidth > maxWidth) {
                newX = startX + startWidth - maxWidth
                newWidth = maxWidth
            }
        }
        if (zone in listOf(ResizeZone.TOP, ResizeZone.TOP_LEFT, ResizeZone.TOP_RIGHT)) {
            if (newHeight < minHeight) {
                newY = startY + startHeight - minHeight
                newHeight = minHeight
            }
            if (newHeight > maxHeight) {
                newY = startY + startHeight - maxHeight
                newHeight = maxHeight
            }
        }

        // Apply final constraints
        newWidth = newWidth.coerceIn(minWidth, maxWidth)
        newHeight = newHeight.coerceIn(minHeight, maxHeight)
        newX = newX.coerceIn(0, screenWidth - newWidth)
        newY = newY.coerceIn(0, screenHeight - newHeight)

        return ResizeResult(newX, newY, newWidth, newHeight)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Corner handle rendering
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Draws an L-shaped corner handle for resize indicators.
     */
    fun drawCornerHandle(
        context: DrawContext,
        cornerX: Int,
        cornerY: Int,
        length: Int,
        thickness: Int,
        color: Int,
        topLeft: Boolean = false,
        topRight: Boolean = false,
        bottomLeft: Boolean = false,
        bottomRight: Boolean = false
    ) {
        when {
            topLeft -> {
                context.fill(cornerX, cornerY, cornerX + length, cornerY + thickness, color)
                context.fill(cornerX, cornerY, cornerX + thickness, cornerY + length, color)
            }
            topRight -> {
                context.fill(cornerX - length, cornerY, cornerX, cornerY + thickness, color)
                context.fill(cornerX - thickness, cornerY, cornerX, cornerY + length, color)
            }
            bottomLeft -> {
                context.fill(cornerX, cornerY - thickness, cornerX + length, cornerY, color)
                context.fill(cornerX, cornerY - length, cornerX + thickness, cornerY, color)
            }
            bottomRight -> {
                context.fill(cornerX - length, cornerY - thickness, cornerX, cornerY, color)
                context.fill(cornerX - thickness, cornerY - length, cornerX, cornerY, color)
            }
        }
    }
}
