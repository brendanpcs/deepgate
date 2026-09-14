package com.brendan.deepgate.ui;

import java.util.ArrayList;
import java.util.List;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Failure;
import com.brendan.deepgate.core.Fare;
import com.brendan.deepgate.core.Quotes;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.core.TxnResource;
import com.brendan.deepgate.dialog.DialogService;
import com.brendan.deepgate.dialog.Dialogs;
import com.brendan.deepgate.spawn.AnchorChargeResource;
import com.brendan.deepgate.spawn.SpawnService;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /spawn} screen and its commit (spec sections 10 to 13).
 *
 * <p>The screen names the destination and, for a respawn anchor, its remaining charge. A bed or
 * anchor that cannot be used falls back to world spawn, exactly as dying does, and the player is
 * told why - the fallback is never silent.
 */
public final class SpawnUi {
	private static final Identifier TRAVEL = Deepgate.id("spawn/travel");

	private SpawnUi() {
	}

	public static void registerHandlers(DialogService dialogs) {
		dialogs.register(TRAVEL, (player, payload) -> travel(player));
	}

	/** Open the spawn destination screen. */
	public static void open(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		SpawnService.Resolution resolution = SpawnService.resolve(player, rules);

		if (resolution instanceof SpawnService.Resolution.Blocked blocked) {
			refuse(player, blocked.failure());
			return;
		}

		if (resolution instanceof SpawnService.Resolution.Unavailable unavailable) {
			refuse(player, unavailable.failure());
			return;
		}

		Destination destination = SpawnService.destinationOf(resolution).orElseThrow();
		Fare fare = Quotes.quote(player, destination.level(), destination.position(), rules);

		List<DialogBody> body = new ArrayList<>();
		body.add(Dialogs.text("Destination: " + SpawnService.describe(resolution)));

		if (resolution instanceof SpawnService.Resolution.World world && world.afterFallback()) {
			body.add(Dialogs.text("Your bed or anchor cannot be used right now."));
		}

		if (resolution instanceof SpawnService.Resolution.Personal personal) {
			if (personal.kind() == SpawnService.Kind.BED && personal.bedColour() != null) {
				body.add(Dialogs.text("Bed colour: " + prettyColour(personal.bedColour().getName())));
			}

			if (personal.kind() == SpawnService.Kind.ANCHOR) {
				// Shown even at zero, so an unusable anchor explains itself rather than just failing.
				body.add(Dialogs.text("Charges: " + personal.anchorCharges()));
			}
		}

		body.add(Dialogs.text("Distance: " + Math.round(fare.distance()) + " blocks"));
		body.add(Dialogs.text(TpaUi.describeFare(fare)));

		if (fare.crossDimension()) {
			body.add(Dialogs.text("Crosses dimensions."));
		}

		Deepgate.dialogs().open(player, Dialogs.confirmation(
				"Spawn",
				body,
				Dialogs.commit(Deepgate.dialogs(), player.getUUID(), server.getTickCount(),
						Component.literal("Teleport"), TRAVEL, new CompoundTag()),
				Dialogs.close()));
	}

	/**
	 * Commit the travel, re-resolving from scratch (spec section 46).
	 *
	 * <p>Everything is recomputed here: the bed may have been broken, the anchor drained, or the
	 * player moved since the screen was drawn.
	 */
	public static void travel(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		SpawnService.Resolution resolution = SpawnService.resolve(player, rules);

		if (resolution instanceof SpawnService.Resolution.Blocked blocked) {
			refuse(player, blocked.failure());
			return;
		}

		if (resolution instanceof SpawnService.Resolution.Unavailable unavailable) {
			refuse(player, unavailable.failure());
			return;
		}

		Destination destination = SpawnService.destinationOf(resolution).orElseThrow();
		Fare fare = Quotes.quote(player, destination.level(), destination.position(), rules);

		// An anchor is charged as part of the same transaction, so a failed move gives it back.
		List<TxnResource> extra = new ArrayList<>();

		if (resolution instanceof SpawnService.Resolution.Personal personal
				&& personal.kind() == SpawnService.Kind.ANCHOR) {
			ServerLevel anchorLevel = server.getLevel(player.getRespawnConfig().respawnData().dimension());

			if (anchorLevel == null) {
				refuse(player, Failure.of(Failure.Reason.DESTINATION_MISSING, "Your anchor is not loaded"));
				return;
			}

			extra.add(new AnchorChargeResource(anchorLevel, personal.block()));
		}

		TeleportService.Result result = Deepgate.teleports()
				.execute(player, destination, fare, List.of(), extra, rules);

		if (result instanceof TeleportService.Result.Failed failed) {
			refuse(player, failed.failure());
			return;
		}

		if (resolution instanceof SpawnService.Resolution.World world && world.afterFallback()) {
			// Vanilla's own wording for a respawn point that exists but cannot be honoured.
			player.sendSystemMessage(Component.translatable(SpawnService.SPAWN_NOT_VALID));
		}

		Feedback.teleported(player, SpawnService.describe(resolution),
				((TeleportService.Result.Success) result).pointsCharged());
	}

	private static void refuse(ServerPlayer player, Failure failure) {
		Feedback.refused(player, failure);
	}

	/** "light_blue" reads better as "Light Blue". */
	private static String prettyColour(String name) {
		String[] parts = name.split("_");
		StringBuilder out = new StringBuilder();

		for (String part : parts) {
			if (!out.isEmpty()) {
				out.append(' ');
			}

			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}

		return out.toString();
	}
}
