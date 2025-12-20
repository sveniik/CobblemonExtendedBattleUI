package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.cobblemonextendedbattleui.DamageTracker;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Intercepts battle initialization to pre-populate HP baselines for initial Pokemon.
 *
 * When a battle starts, all Pokemon on the field have their HP captured here.
 * This ensures we have accurate HP baselines before any HP change packets arrive,
 * which is critical for correctly calculating damage during the first turn.
 */
@Mixin(value = BattleInitializeHandler.class, remap = false)
public class BattleInitializeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleInitializePacket packet, MinecraftClient client, CallbackInfo ci) {
        try {
            // Process both sides
            initializePokemonFromSide(packet.getSide1());
            initializePokemonFromSide(packet.getSide2());
        } catch (Exception e) {
            // Silent fail to avoid breaking gameplay
        }
    }

    private void initializePokemonFromSide(BattleInitializePacket.BattleSideDTO side) {
        if (side == null) return;

        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor == null) continue;

            for (BattleInitializePacket.ActiveBattlePokemonDTO pokemon : actor.getActivePokemon()) {
                if (pokemon == null) continue;

                UUID uuid = pokemon.getUuid();
                float hpValue = pokemon.getHpValue();
                float maxHp = pokemon.getMaxHp();
                boolean isFlat = pokemon.isFlatHp();

                // Pre-populate the HP tracker with the initial HP value
                DamageTracker.INSTANCE.initializeHpFromSwitch(uuid, hpValue, maxHp, isFlat);
            }
        }
    }
}
