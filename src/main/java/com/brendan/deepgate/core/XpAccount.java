package com.brendan.deepgate.core;

import net.minecraft.server.level.ServerPlayer;

/**
 * Exact experience read and write for a player.
 *
 * <p>Deepgate never nudges levels to charge a fare. {@code /back} has to refund precisely what was
 * removed (spec section 20), so every transaction goes through a total point count computed from
 * the vanilla curve.
 *
 * <p>The three experience fields are written directly rather than through
 * {@code giveExperiencePoints}, which also bumps the player's score statistic - a side effect that
 * has no business firing on a teleport, least of all in reverse on a refund.
 * {@link ServerPlayer#resetSentInfo()} then forces the bar to resync to the client.
 *
 * <p>This is the single place that touches those fields; if a future version needs an accessor
 * mixin to reach them, only this class changes.
 */
public final class XpAccount {
	private XpAccount() {
	}

	/** The player's experience as an exact point total. */
	public static int totalPoints(ServerPlayer player) {
		return XpCurve.totalPoints(player.experienceLevel, player.experienceProgress);
	}

	/** Overwrite the player's experience with an exact point total. */
	public static void setTotalPoints(ServerPlayer player, int total) {
		int clamped = Math.max(0, total);

		player.experienceLevel = XpCurve.levelForTotal(clamped);
		player.experienceProgress = XpCurve.progressForTotal(clamped);
		player.totalExperience = clamped;
		player.resetSentInfo();
	}

	/** Add (or with a negative amount, remove) an exact number of points. */
	public static void addPoints(ServerPlayer player, int points) {
		setTotalPoints(player, totalPoints(player) + points);
	}
}
