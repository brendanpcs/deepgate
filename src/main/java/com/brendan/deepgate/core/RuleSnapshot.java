package com.brendan.deepgate.core;

/**
 * The gamerule values a single teleport decision depends on, captured at one instant.
 *
 * <p>Taking a snapshot keeps the pricing and validation logic free of Minecraft imports, and means
 * a quote and its commit can be compared against identical inputs rather than re-reading rules that
 * an operator may have changed mid-dialog.
 */
public record RuleSnapshot(
		boolean allowCrossDimension,
		boolean allowInCombat,
		int combatSeconds,
		boolean allowHomes,
		int maxHomes,
		int homeBeaconLayers,
		int xpCostPer1k,
		boolean xpCostInLevels,
		int xpCrossDimension,
		int xpFreeDistance,
		int backWindowSeconds,
		int portalMaxFrameBlocks) {
}
