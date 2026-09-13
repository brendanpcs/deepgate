package com.brendan.deepgate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player combat timers (spec section 7).
 *
 * <p>A timer starts or refreshes when a damage event actually lands between two combatants, where a
 * combatant is a {@link Player} or a {@link Mob}. That single rule reproduces the spec's table for
 * free: boats, minecarts, item frames and armor stands are not {@code Mob}, so hitting them never
 * tags; and fall, fire, drowning and starvation carry no causing entity, so they never tag either.
 *
 * <p>Timers are memory-only and do not survive a restart (section 48).
 */
public final class CombatTracker {
	private static final int TICKS_PER_SECOND = 20;

	private final Map<UUID, Long> taggedUntilTick = new HashMap<>();

	/**
	 * Record a damage event that already happened.
	 *
	 * @param damageTaken the damage that actually landed; zero means the hit was absorbed entirely
	 */
	public void onDamage(MinecraftServer server, LivingEntity victim, DamageSource source, float damageTaken,
			int combatSeconds) {
		// Swinging at something is not combat; the damage has to succeed.
		if (damageTaken <= 0.0F) {
			return;
		}

		// getEntity() is the causing entity, so a projectile is credited to whoever fired it.
		Entity attacker = source.getEntity();

		if (!isCombatant(victim) || !isCombatant(attacker)) {
			return;
		}

		tagIfPlayer(server, victim, combatSeconds);
		tagIfPlayer(server, attacker, combatSeconds);
	}

	private static boolean isCombatant(Entity entity) {
		return entity instanceof Player || entity instanceof Mob;
	}

	private void tagIfPlayer(MinecraftServer server, Entity entity, int combatSeconds) {
		if (entity instanceof ServerPlayer player) {
			tag(server, player, combatSeconds);
		}
	}

	/** Start or refresh a player's combat timer. */
	public void tag(MinecraftServer server, ServerPlayer player, int combatSeconds) {
		if (combatSeconds <= 0) {
			taggedUntilTick.remove(player.getUUID());
			return;
		}

		long until = server.getTickCount() + (long) combatSeconds * TICKS_PER_SECOND;
		taggedUntilTick.merge(player.getUUID(), until, Math::max);
	}

	public boolean isTagged(MinecraftServer server, UUID playerId) {
		return remainingTicks(server, playerId) > 0;
	}

	public long remainingTicks(MinecraftServer server, UUID playerId) {
		Long until = taggedUntilTick.get(playerId);

		if (until == null) {
			return 0L;
		}

		long remaining = until - server.getTickCount();

		if (remaining <= 0L) {
			taggedUntilTick.remove(playerId);
			return 0L;
		}

		return remaining;
	}

	/** Remaining wait rounded up, so a message never says "0s remaining" while still blocking. */
	public int remainingSeconds(MinecraftServer server, UUID playerId) {
		long ticks = remainingTicks(server, playerId);
		return (int) ((ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
	}

	/**
	 * The combat failure for a player, if combat currently blocks them.
	 *
	 * @return empty when {@code deepgate:allow_in_combat} is on, or the player is not tagged
	 */
	public java.util.Optional<Failure> check(MinecraftServer server, UUID playerId, RuleSnapshot rules) {
		if (rules.allowInCombat()) {
			return java.util.Optional.empty();
		}

		int seconds = remainingSeconds(server, playerId);
		return seconds > 0 ? java.util.Optional.of(Failure.combat(seconds)) : java.util.Optional.empty();
	}

	public void clear(UUID playerId) {
		taggedUntilTick.remove(playerId);
	}

	public void clearAll() {
		taggedUntilTick.clear();
	}
}
