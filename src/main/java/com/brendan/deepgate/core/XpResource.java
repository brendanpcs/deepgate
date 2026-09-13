package com.brendan.deepgate.core;

import net.minecraft.server.level.ServerPlayer;

/**
 * The experience half of a teleport transaction (spec section 50).
 *
 * <p>Refusing to consume when the player is short is what keeps "any failure before the teleport
 * leaves the player's experience unchanged" true: the charge never happens at all.
 */
public final class XpResource implements TxnResource {
	private final ServerPlayer player;
	private final int points;

	public XpResource(ServerPlayer player, int points) {
		this.player = player;
		this.points = Math.max(0, points);
	}

	/** The exact points this resource removes, recorded on the {@code /back} record for refund. */
	public int points() {
		return points;
	}

	@Override
	public boolean consume() {
		if (points == 0) {
			return true;
		}

		int held = XpAccount.totalPoints(player);

		if (held < points) {
			return false;
		}

		XpAccount.setTotalPoints(player, held - points);
		return true;
	}

	@Override
	public void rollback() {
		if (points == 0) {
			return;
		}

		// Add back exactly what was taken rather than restoring the pre-charge total: the player may
		// legitimately have picked up experience in between, and that is theirs to keep.
		XpAccount.addPoints(player, points);
	}

	@Override
	public String describe() {
		return "experience(" + points + " points, " + player.getGameProfile().name() + ")";
	}
}
