package com.brendan.deepgate.core;

/**
 * Distance measured in the destination dimension's coordinate frame (spec section 5).
 *
 * <p>Only X and Z are scaled; Y is carried across unchanged. The scale comes from the dimension's
 * own {@code coordinateScale} at the call site, so a custom dimension is priced by its real ratio
 * rather than a hardcoded Nether 8:1 assumption.
 *
 * <p>Deliberately free of Minecraft imports so it can be unit tested without the game.
 */
public final class GeoMath {
	private GeoMath() {
	}

	/** Source X or Z converted into the destination frame. */
	public static double convertHorizontal(double sourceValue, double sourceScale, double destinationScale) {
		return sourceValue * sourceScale / destinationScale;
	}

	/**
	 * Three-dimensional distance between a source point and a destination point, with the source
	 * first converted into the destination's coordinate frame.
	 *
	 * <p>Pass equal scales for same-dimension travel; the conversion then collapses to identity.
	 */
	public static double distance(
			double sourceX, double sourceY, double sourceZ, double sourceScale,
			double destX, double destY, double destZ, double destScale) {
		double convertedX = convertHorizontal(sourceX, sourceScale, destScale);
		double convertedZ = convertHorizontal(sourceZ, sourceScale, destScale);

		double dx = convertedX - destX;
		double dy = sourceY - destY;
		double dz = convertedZ - destZ;

		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
