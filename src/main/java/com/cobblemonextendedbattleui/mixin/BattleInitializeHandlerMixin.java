package com.cobblemonextendedbattleui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleInitializeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.cobblemonextendedbattleui.BattleStateTracker;
import com.cobblemonextendedbattleui.DamageTracker;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Intercepts battle initialization to pre-populate HP baselines and register Pokemon.
 *
 * When a battle starts, all Pokemon on the field have their HP captured here.
 * This ensures we have accurate HP baselines before any HP change packets arrive,
 * which is critical for correctly calculating damage during the first turn.
 *
 * Also registers Pokemon with BattleStateTracker early so that historical battle
 * messages (when spectating) can correctly apply stat changes and other effects.
 */
@Mixin(value = BattleInitializeHandler.class, remap = false)
public class BattleInitializeHandlerMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandlePre(BattleInitializePacket packet, MinecraftClient client, CallbackInfo ci) {
        try {
            // Determine if player is spectating
            UUID playerUUID = client.getSession().getUuidOrNull();
            boolean isPlayerInSide1 = isPlayerInSide(packet.getSide1(), playerUUID);
            boolean isPlayerInSide2 = isPlayerInSide(packet.getSide2(), playerUUID);
            boolean isSpectating = !isPlayerInSide1 && !isPlayerInSide2;

            // Set spectator mode in tracker
            BattleStateTracker.INSTANCE.setSpectating(isSpectating);

            // Get player names for disambiguation in mirror matches
            String side1PlayerName = getFirstActorName(packet.getSide1());
            String side2PlayerName = getFirstActorName(packet.getSide2());

            // For participants: side1 is ally if player is in side1
            // For spectators: side1 is treated as "ally" (left side)
            boolean side1IsAlly = isPlayerInSide1 || isSpectating;

            if (side1PlayerName != null && side2PlayerName != null) {
                if (side1IsAlly) {
                    BattleStateTracker.INSTANCE.setPlayerNames(side1PlayerName, side2PlayerName);
                } else {
                    BattleStateTracker.INSTANCE.setPlayerNames(side2PlayerName, side1PlayerName);
                }
            }

            // Initialize and register Pokemon from both sides
            initializePokemonFromSide(packet.getSide1(), side1IsAlly);
            initializePokemonFromSide(packet.getSide2(), !side1IsAlly);
        } catch (Exception e) {
            // Silent fail to avoid breaking gameplay
        }
    }

    private boolean isPlayerInSide(BattleInitializePacket.BattleSideDTO side, UUID playerUUID) {
        if (side == null || playerUUID == null) return false;
        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor != null && playerUUID.equals(actor.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private String getFirstActorName(BattleInitializePacket.BattleSideDTO side) {
        if (side == null) return null;
        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor != null && actor.getDisplayName() != null) {
                return actor.getDisplayName().getString();
            }
        }
        return null;
    }

    private void initializePokemonFromSide(BattleInitializePacket.BattleSideDTO side, boolean isAlly) {
        if (side == null) return;

        for (BattleInitializePacket.BattleActorDTO actor : side.getActors()) {
            if (actor == null) continue;

            for (BattleInitializePacket.ActiveBattlePokemonDTO pokemon : actor.getActivePokemon()) {
                if (pokemon == null) continue;

                UUID uuid = pokemon.getUuid();
                float hpValue = pokemon.getHpValue();
                float maxHp = pokemon.getMaxHp();
                boolean isFlat = pokemon.isFlatHp();
                String name = pokemon.getDisplayName() != null ? pokemon.getDisplayName().getString() : "Unknown";

                // Pre-populate the HP tracker with the initial HP value
                DamageTracker.INSTANCE.initializeHpFromSwitch(uuid, hpValue, maxHp, isFlat);

                // Register Pokemon with BattleStateTracker so historical messages
                // can apply stat changes correctly (critical for spectator mode)
                BattleStateTracker.INSTANCE.registerPokemon(uuid, name, isAlly);
            }
        }
    }
}
