package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleSwitchPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleSwitchPokemonPacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.cobblemonextendedbattleui.DamageTracker;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Intercepts Pokemon switch-in events to pre-populate HP baselines in DamageTracker.
 *
 * This ensures that when HP change packets arrive later, we have an accurate baseline
 * for calculating damage/healing percentages. Without this, the first HP change after
 * a switch-in would have no reliable baseline, leading to incorrect damage numbers.
 *
 * The switch packet contains the authoritative HP values from the server, so we capture
 * these at switch-in time before any client-side updates or HP change packets.
 */
@Mixin(value = BattleSwitchPokemonHandler.class, remap = false)
public class BattleSwitchHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleSwitchPokemonPacket packet, MinecraftClient client, CallbackInfo ci) {
        try {
            BattleInitializePacket.ActiveBattlePokemonDTO newPokemon = packet.getNewPokemon();
            if (newPokemon == null) return;

            UUID uuid = newPokemon.getUuid();
            float hpValue = newPokemon.getHpValue();
            float maxHp = newPokemon.getMaxHp();
            boolean isFlat = newPokemon.isFlatHp();

            // Pre-populate the HP tracker with the switch-in HP value
            // This provides an accurate baseline for subsequent HP change calculations
            DamageTracker.INSTANCE.initializeHpFromSwitch(uuid, hpValue, maxHp, isFlat);
        } catch (Exception e) {
            // Silent fail to avoid breaking gameplay
        }
    }
}
