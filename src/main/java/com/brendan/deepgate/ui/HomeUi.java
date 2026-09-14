package com.brendan.deepgate.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Failure;
import com.brendan.deepgate.core.Fare;
import com.brendan.deepgate.core.Quotes;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.dialog.DialogService;
import com.brendan.deepgate.dialog.Dialogs;
import com.brendan.deepgate.home.HomeName;
import com.brendan.deepgate.home.HomeRecord;
import com.brendan.deepgate.home.HomeService;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerPlayer;

/**
 * The home screens (spec sections 15, 16 and 19).
 *
 * <p>Creating a home is the one Deepgate action that requires a client: it happens by walking into a
 * beacon beam and naming the home in the dialog, and there is deliberately no command for it.
 * Everything afterwards - travelling, renaming, deleting - is reachable from {@code /home} too.
 */
public final class HomeUi {
	private static final Identifier ROOT = Deepgate.id("home/root");
	private static final Identifier DETAIL = Deepgate.id("home/detail");
	private static final Identifier TRAVEL = Deepgate.id("home/travel");
	private static final Identifier SAVE = Deepgate.id("home/save");
	private static final Identifier RENAME_FORM = Deepgate.id("home/rename_form");
	private static final Identifier RENAME = Deepgate.id("home/rename");
	private static final Identifier DELETE_CONFIRM = Deepgate.id("home/delete_confirm");
	private static final Identifier DELETE = Deepgate.id("home/delete");

	private static final String KEY_HOME = "home";
	private static final String KEY_BEACON = "beacon";
	private static final String KEY_NAME = "name";

	private HomeUi() {
	}

	public static void registerHandlers(DialogService dialogs) {
		dialogs.registerNavigation(ROOT, (player, payload) -> openList(player));
		dialogs.registerNavigation(DETAIL, (player, payload) ->
				withHome(player, payload).ifPresent(home -> openDetail(player, home)));
		dialogs.registerNavigation(RENAME_FORM, (player, payload) ->
				withHome(player, payload).ifPresent(home -> openRename(player, home)));
		dialogs.registerNavigation(DELETE_CONFIRM, (player, payload) ->
				withHome(player, payload).ifPresent(home -> openDeleteConfirm(player, home)));

		dialogs.register(TRAVEL, HomeUi::onTravel);
		dialogs.register(SAVE, HomeUi::onSave);
		dialogs.register(RENAME, HomeUi::onRename);
		dialogs.register(DELETE, HomeUi::onDelete);
	}

	// ------------------------------------------------------------ list

	/** The {@code /home} root, with the chat copy that keeps it usable without a client. */
	public static void openList(ServerPlayer player) {
		openList(player, true);
	}

	/**
	 * The {@code /home} root: "Homes - 4/10" and one entry per home (section 19).
	 *
	 * @param alsoChat write the list to chat as well. True when the player asked for it, false when
	 *                 the screen was opened by walking into a beam - nobody wants their chat filled
	 *                 every time they cross their own beacon.
	 */
	public static void openList(ServerPlayer player, boolean alsoChat) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		List<HomeRecord> homes = Deepgate.homes().homesOf(player);

		String title = "Homes - " + homes.size() + "/" + rules.maxHomes();

		if (homes.isEmpty()) {
			if (alsoChat) {
				player.sendSystemMessage(Component.literal(
						"You have no homes. Walk into the beam of a beacon to make one."));
			}

			Deepgate.dialogs().open(player, Dialogs.notice(title,
					"You have no homes yet. Walk into the beam of a beacon to make one."));
			return;
		}

		List<ActionButton> buttons = new ArrayList<>();

		for (HomeRecord home : homes) {
			HomeService.Availability availability = Deepgate.homes().availability(player, home, rules);

			CompoundTag payload = new CompoundTag();
			payload.putString(KEY_HOME, home.id().toString());

			String label = availability.worthReporting()
					? home.name() + " (" + availability.description() + ")"
					: home.name();

			int colour = HomeService.beamColourOf(server, home);

			buttons.add(Dialogs.navigate(beamColoured(label, colour), DETAIL, payload));

			if (alsoChat) {
				player.sendSystemMessage(beamColoured("  " + label, colour));
			}
		}

		Deepgate.dialogs().open(player, Dialogs.menu(
				title, List.of(Dialogs.text("Choose a home.")), buttons, Dialogs.close(), 1));
	}

	// ------------------------------------------------------------ detail

	private static void openDetail(ServerPlayer player, HomeRecord home) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		long tick = server.getTickCount();

		HomeService.Availability availability = Deepgate.homes().availability(player, home, rules);
		Optional<Destination> destination = HomeService.findArrival(player, home);

		List<DialogBody> body = new ArrayList<>();
		body.add(Dialogs.text(beamColoured("Name: " + home.name(), HomeService.beamColourOf(server, home))));
		body.add(Dialogs.text("Dimension: " + home.dimension().identifier().getPath()));

		if (destination.isPresent()) {
			Fare fare = Quotes.quote(player, destination.get().level(), destination.get().position(), rules);
			body.add(Dialogs.text("Distance: " + Math.round(fare.distance()) + " blocks"));
			body.add(Dialogs.text(TpaUi.describeFare(fare)));
		}

		if (availability.worthReporting()) {
			body.add(Dialogs.text("Unavailable: " + availability.description()));
		}

		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_HOME, home.id().toString());

		List<ActionButton> actions = new ArrayList<>();

		if (availability.usable()) {
			actions.add(Dialogs.commit(Deepgate.dialogs(), player.getUUID(), tick,
					Component.literal("Teleport"), TRAVEL, payload));
		}

		actions.add(Dialogs.navigate(Component.literal("Rename"), RENAME_FORM, payload));
		actions.add(Dialogs.navigate(Component.literal("Delete"), DELETE_CONFIRM, payload));

		Deepgate.dialogs().open(player, Dialogs.menu(
				home.name(), body, actions, Dialogs.navigate(Component.literal("Back"), ROOT, new CompoundTag()), 1));
	}

	// ------------------------------------------------------------ create

	/** The Set Home screen, opened by walking into a qualifying beam (sections 15 and 16). */
	public static void openSetHome(ServerPlayer player, BlockPos beacon) {
		MinecraftServer server = player.level().getServer();
		long tick = server.getTickCount();

		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_BEACON, beacon.toShortString());
		payload.putLong("beacon_packed", beacon.asLong());

		Deepgate.dialogs().open(player, Dialogs.textEntry(
				"Set Home",
				List.of(Dialogs.text("Name this beacon so you can return to it.")),
				KEY_NAME,
				"Home name",
				"",
				HomeName.MAX_LENGTH,
				Dialogs.commitWithInputs(Deepgate.dialogs(), player.getUUID(), tick,
						Component.literal("Save"), SAVE, payload),
				Dialogs.close()));
	}

	private static void onSave(ServerPlayer player, CompoundTag payload) {
		RuleSnapshot rules = DeepgateRules.snapshot(player.level().getServer());
		BlockPos beacon = BlockPos.of(payload.getLongOr("beacon_packed", 0L));

		// Re-check the beacon rather than trusting the payload: the screen may be stale.
		var found = com.brendan.deepgate.home.BeaconScan.beaconAt(player.level(), beacon);

		if (found.isEmpty() || !found.get().qualifies(rules.homeBeaconLayers())) {
			player.sendSystemMessage(Component.literal("That beacon is no longer suitable."));
			return;
		}

		HomeName.Result result = Deepgate.homes().create(player, beacon, payload.getStringOr(KEY_NAME, ""), rules);

		if (result instanceof HomeName.Result.Invalid invalid) {
			player.sendSystemMessage(Component.literal(invalid.reason()));
			return;
		}

		player.sendSystemMessage(Component.literal(
				"Home saved: " + ((HomeName.Result.Valid) result).name()));
	}

	// ------------------------------------------------------------ rename and delete

	private static void openRename(ServerPlayer player, HomeRecord home) {
		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_HOME, home.id().toString());

		Deepgate.dialogs().open(player, Dialogs.textEntry(
				"Rename " + home.name(),
				List.of(Dialogs.text("Choose a new name.")),
				KEY_NAME,
				"Home name",
				home.name(),
				HomeName.MAX_LENGTH,
				Dialogs.commitWithInputs(Deepgate.dialogs(), player.getUUID(),
						player.level().getServer().getTickCount(),
						Component.literal("Save"), RENAME, payload),
				Dialogs.navigate(Component.literal("Back"), DETAIL, payload)));
	}

	private static void onRename(ServerPlayer player, CompoundTag payload) {
		withHome(player, payload).ifPresent(home -> {
			HomeName.Result result = Deepgate.homes().rename(player, home, payload.getStringOr(KEY_NAME, ""));

			player.sendSystemMessage(Component.literal(result instanceof HomeName.Result.Invalid invalid
					? invalid.reason()
					: "Renamed to " + ((HomeName.Result.Valid) result).name()));
		});
	}

	private static void openDeleteConfirm(ServerPlayer player, HomeRecord home) {
		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_HOME, home.id().toString());

		Deepgate.dialogs().open(player, Dialogs.confirmation(
				"Delete " + home.name(),
				List.of(Dialogs.text("This cannot be undone. The beacon itself is not affected.")),
				Dialogs.commit(Deepgate.dialogs(), player.getUUID(),
						player.level().getServer().getTickCount(),
						Component.literal("Delete"), DELETE, payload),
				Dialogs.navigate(Component.literal("Back"), DETAIL, payload)));
	}

	private static void onDelete(ServerPlayer player, CompoundTag payload) {
		withHome(player, payload).ifPresent(home -> {
			if (Deepgate.homes().delete(player, home)) {
				player.sendSystemMessage(Component.literal("Deleted " + home.name() + "."));
			}
		});
	}

	// ------------------------------------------------------------ travel

	private static void onTravel(ServerPlayer player, CompoundTag payload) {
		withHome(player, payload).ifPresent(home -> travelTo(player, home));
	}

	/** Travel to a home, revalidating everything first. */
	public static void travelTo(ServerPlayer player, HomeRecord home) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);

		// forceLoad: a home must be reachable however far away it is, so the beacon chunk is pulled in
		// rather than the travel being refused for not being able to look.
		HomeService.Availability availability = Deepgate.homes().availability(player, home, rules, true);
		Optional<Failure> blocked = HomeService.toFailure(availability);

		if (blocked.isPresent()) {
			Feedback.refused(player, blocked.get());
			return;
		}

		Optional<Destination> destination = HomeService.findArrival(player, home);

		if (destination.isEmpty()) {
			Feedback.refused(player,
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That home is not available"));
			return;
		}

		Fare fare = Quotes.quote(player, destination.get().level(), destination.get().position(), rules);

		TeleportService.Result result = Deepgate.teleports()
				.execute(player, destination.get(), fare, List.of(), List.of(), rules);

		if (result instanceof TeleportService.Result.Failed failed) {
			Feedback.refused(player, failed.failure());
			return;
		}

		Feedback.teleported(player, home.name(), ((TeleportService.Result.Success) result).pointsCharged());
	}

	/**
	 * Text tinted to match the beam of the beacon.
	 *
	 * <p>The colour is whatever the beam actually ends up after any stained glass, including stacked
	 * panes, so a home named in the list is recognisable as the beam you can see in the world.
	 */
	private static Component beamColoured(String text, int colour) {
		return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(colour)));
	}

	// ------------------------------------------------------------ helpers

	/** Resolve the home a payload names, checking it really belongs to this player. */
	private static Optional<HomeRecord> withHome(ServerPlayer player, CompoundTag payload) {
		String raw = payload.getStringOr(KEY_HOME, "");

		if (raw.isEmpty()) {
			return Optional.empty();
		}

		UUID id;

		try {
			id = UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}

		Optional<HomeRecord> home = com.brendan.deepgate.state.DeepgateState
				.get(player.level().getServer()).home(id);

		if (home.isEmpty() || !home.get().owner().equals(player.getUUID())) {
			player.sendSystemMessage(Component.literal("That home no longer exists."));
			return Optional.empty();
		}

		return home;
	}
}
