package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleMessageHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import com.cobblemonextendedbattleui.BattleLog;
import com.cobblemonextendedbattleui.BattleMessageInterceptor;
import com.cobblemonextendedbattleui.PanelConfig;
import net.minecraft.client.MinecraftClient;

/**
 * Mixin to intercept battle messages for state tracking and custom battle log.
 *
 * When replaceBattleLog is enabled, this mixin will:
 * 1. Process messages for state tracking (weather, terrain, stats, etc.)
 * 2. Store messages in our custom BattleLog
 * 3. Cancel the default handler to prevent Cobblemon from showing messages in chat
 */
@Mixin(value = BattleMessageHandler.class, remap = false)
public class BattleMessageHandlerMixin {

    /**
     * Inject at the start of handle() to process messages before they're displayed.
     * When replaceBattleLog is enabled, we cancel the default handling entirely.
     */
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(BattleMessagePacket packet, MinecraftClient client, CallbackInfo ci) {
        // Always process messages for state tracking (weather, terrain, stats, etc.)
        BattleMessageInterceptor.INSTANCE.processMessages(packet.getMessages());

        // Always store messages in our battle log
        BattleLog.INSTANCE.processMessages(packet.getMessages());

        // If replaceBattleLog is enabled, prevent Cobblemon from showing messages in chat
        if (PanelConfig.INSTANCE.getReplaceBattleLog()) {
            ci.cancel();
        }
    }
}
