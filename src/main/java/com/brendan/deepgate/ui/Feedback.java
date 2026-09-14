package com.brendan.deepgate.ui;

import com.brendan.deepgate.core.Failure;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Short confirmations and refusals (spec sections 21 and 52).
 *
 * <p>A successful command teleport offers an immediate undo, because the fifteen second window is
 * easy to miss if the player has to remember the command. P2P portal travel deliberately says
 * nothing: it should feel like walking through a Nether portal.
 */
public final class Feedback {
	private Feedback() {
	}

	/**
	 * "Teleported to Workshop. [Back]", with the charge appended when there was one.
	 *
	 * <p>The price was already shown and approved on the confirmation screen; repeating what was
	 * actually taken makes the undo window meaningful, since the refund is exact.
	 */
	public static void teleported(ServerPlayer player, String destinationName, int pointsCharged) {
		String cost = pointsCharged > 0 ? " (-" + pointsCharged + " XP)" : "";
		MutableComponent message = Component.literal("Teleported to " + destinationName + "." + cost + " ");

		message.append(Component.literal("[Back]").withStyle(style -> style
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/back"))));

		player.sendSystemMessage(message);
	}

	/** Confirmation for a successful undo, which never offers another undo of its own. */
	public static void wentBack(ServerPlayer player, int pointsRefunded) {
		String suffix = pointsRefunded > 0 ? " Refunded " + pointsRefunded + " XP." : "";
		player.sendSystemMessage(Component.literal("Returned to where you were." + suffix));
	}

	/** A refusal, phrased as the corrective action wherever the reason allows one. */
	public static void refused(ServerPlayer player, Failure failure) {
		player.sendSystemMessage(failure.translationKey() == null
				? Component.literal(failure.message())
				: Component.translatable(failure.translationKey()));
	}
}
