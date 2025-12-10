package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleMessagePane;
import com.cobblemonextendedbattleui.PanelConfig;
import net.minecraft.client.gui.DrawContext;

/**
 * Mixin to hide Cobblemon's BattleMessagePane when we're using our custom battle log.
 * This prevents the empty box from rendering while we display our own enhanced log.
 */
@Mixin(value = BattleMessagePane.class, remap = false)
public class BattleMessagePaneMixin {

    /**
     * Intercept the renderWidget method and skip it entirely when replaceBattleLog is enabled.
     * BattleMessagePane extends AlwaysSelectedEntryListWidget which uses renderWidget for rendering.
     */
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void onRenderWidget(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (PanelConfig.INSTANCE.getReplaceBattleLog()) {
            ci.cancel();
        }
    }
}
