package com.brendan.deepgate.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.Fare;
import com.brendan.deepgate.core.Quotes;
import com.brendan.deepgate.core.RequestChoice;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.dialog.DialogService;
import com.brendan.deepgate.dialog.Dialogs;
import com.brendan.deepgate.request.RequestService;
import com.brendan.deepgate.request.TpaRequest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.level.ServerPlayer;

/**
 * The screens and messages for {@code /tpa}, {@code /tpahere} and {@code /tparequests}
 * (spec section 8).
 *
 * <p>Incoming requests never force a dialog open. A recipient is told in chat and opens the inbox
 * when they choose to, so a request cannot interrupt what someone is doing.
 */
public final class TpaUi {
	private static final Identifier SELECT = Deepgate.id("tpa/select");
	private static final Identifier SELECTOR = Deepgate.id("tpa/selector");
	private static final Identifier SEND = Deepgate.id("tpa/send");
	private static final Identifier ACCEPT = Deepgate.id("tpa/accept");
	private static final Identifier DENY = Deepgate.id("tpa/deny");
	private static final Identifier APPROVE_FARE = Deepgate.id("tpa/approve_fare");

	private static final String KEY_TARGET = "target";
	private static final String KEY_KIND = "kind";
	private static final String KEY_REQUEST = "request";

	private TpaUi() {
	}

	public static void registerHandlers(DialogService dialogs) {
		dialogs.registerNavigation(SELECT, TpaUi::onSelect);
		dialogs.registerNavigation(SELECTOR, (player, payload) -> openPlayerSelector(player, kindOf(payload)));
		dialogs.register(SEND, TpaUi::onSend);
		dialogs.register(ACCEPT, TpaUi::onAccept);
		dialogs.register(DENY, TpaUi::onDeny);
		dialogs.register(APPROVE_FARE, TpaUi::onApproveFare);
	}

	// ------------------------------------------------------------ screens

	/** The online-player selector opened by a bare {@code /tpa} or {@code /tpahere}. */
	public static void openPlayerSelector(ServerPlayer player, TpaRequest.Kind kind) {
		MinecraftServer server = player.level().getServer();

		List<ActionButton> buttons = new ArrayList<>();

		for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
			if (candidate.getUUID().equals(player.getUUID())) {
				continue;
			}

			CompoundTag payload = new CompoundTag();
			payload.putString(KEY_TARGET, candidate.getUUID().toString());
			payload.putString(KEY_KIND, kind.name());

			buttons.add(Dialogs.navigate(Component.literal(candidate.getGameProfile().name()), SELECT, payload));
		}

		String title = kind == TpaRequest.Kind.TPA ? "Teleport to" : "Summon here";

		if (buttons.isEmpty()) {
			Deepgate.dialogs().open(player, Dialogs.notice(title, "Nobody else is online."));
			return;
		}

		Deepgate.dialogs().open(player, Dialogs.menu(
				title,
				List.of(Dialogs.text("Choose a player.")),
				buttons,
				Dialogs.close(),
				2));
	}

	/**
	 * The confirmation shown before a request is sent, carrying the current quote.
	 *
	 * @param fromSelector true when the player arrived here by picking a name off the selector, which
	 *                     is the parent this screen goes back to. A bare {@code /tpa <player>} opens
	 *                     this screen directly, so there is nothing behind it and it dismisses instead
	 *                     (spec section 45: a root screen closes, a child screen goes back).
	 */
	public static void openRequestConfirmation(ServerPlayer sender, ServerPlayer target, TpaRequest.Kind kind,
			boolean fromSelector) {
		MinecraftServer server = sender.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		long tick = server.getTickCount();

		ServerPlayer mover = kind == TpaRequest.Kind.TPA ? sender : target;
		ServerPlayer anchor = kind == TpaRequest.Kind.TPA ? target : sender;
		Fare fare = Quotes.quote(mover, anchor, rules);

		String targetName = target.getGameProfile().name();
		List<DialogBody> body = new ArrayList<>();

		if (kind == TpaRequest.Kind.TPA) {
			body.add(Dialogs.text("Ask " + targetName + " to let you teleport to them."));
			body.add(Dialogs.text("You move, and you pay."));
		} else {
			body.add(Dialogs.text("Ask " + targetName + " to teleport to you."));
			body.add(Dialogs.text("They move, and they pay."));
		}

		body.add(Dialogs.text("Distance: " + Math.round(fare.distance()) + " blocks"));
		body.add(Dialogs.text(describeFare(fare)));

		if (fare.crossDimension()) {
			body.add(Dialogs.text("Crosses dimensions."));
		}

		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_TARGET, target.getUUID().toString());
		payload.putString(KEY_KIND, kind.name());

		ActionButton dismiss;

		if (fromSelector) {
			CompoundTag backPayload = new CompoundTag();
			backPayload.putString(KEY_KIND, kind.name());
			dismiss = Dialogs.navigate(Component.literal("Back"), SELECTOR, backPayload);
		} else {
			dismiss = Dialogs.close();
		}

		Deepgate.dialogs().open(sender, Dialogs.confirmation(
				"Send request",
				body,
				Dialogs.commit(Deepgate.dialogs(), sender.getUUID(), tick,
						Component.literal("Send"), SEND, payload),
				dismiss));
	}

	/** The inbox opened by {@code /tparequests}. */
	public static void openInbox(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		long tick = server.getTickCount();
		List<TpaRequest> incoming = Deepgate.requests().incoming(player.getUUID());

		if (incoming.isEmpty()) {
			player.sendSystemMessage(Component.literal("You have no teleport requests."));
			Deepgate.dialogs().open(player, Dialogs.notice("Requests", "You have no teleport requests."));
			return;
		}

		// Chat carries the same list, with the same actions, so the inbox screen stays optional.
		for (TpaRequest request : incoming) {
			ServerPlayer from = server.getPlayerList().getPlayer(request.senderId());

			if (from == null) {
				continue;
			}

			String name = from.getGameProfile().name();
			String summary = request.kind() == TpaRequest.Kind.TPA
					? name + " wants to come to you. "
					: name + " wants you to go to them. ";

			player.sendSystemMessage(Component.literal(summary)
					.append(Component.literal("[Accept]").withStyle(style -> style
							.withUnderlined(true)
							.withClickEvent(new ClickEvent.RunCommand("/tpaccept " + name))))
					.append(Component.literal(" "))
					.append(Component.literal("[Deny]").withStyle(style -> style
							.withUnderlined(true)
							.withClickEvent(new ClickEvent.RunCommand("/tpadeny " + name)))));
		}

		List<ActionButton> buttons = new ArrayList<>();

		for (TpaRequest request : incoming) {
			ServerPlayer sender = server.getPlayerList().getPlayer(request.senderId());

			if (sender == null) {
				continue;
			}

			String senderName = sender.getGameProfile().name();
			String label = request.kind() == TpaRequest.Kind.TPA
					? "Accept " + senderName + " to you"
					: "Accept going to " + senderName;

			CompoundTag payload = new CompoundTag();
			payload.putString(KEY_REQUEST, request.id().toString());

			buttons.add(Dialogs.commit(Deepgate.dialogs(), player.getUUID(), tick,
					Component.literal(label), ACCEPT, payload));
			buttons.add(Dialogs.commit(Deepgate.dialogs(), player.getUUID(), tick,
					Component.literal("Deny " + senderName), DENY, payload));
		}

		Deepgate.dialogs().open(player, Dialogs.menu(
				"Requests",
				List.of(Dialogs.text("Requests waiting on you.")),
				buttons,
				Dialogs.close(),
				1));
	}

	/** Shown to the payer when the fare rose or the destination dimension changed (section 8). */
	private static void openFareApproval(ServerPlayer payer, TpaRequest request, Fare fare,
			boolean dimensionChanged) {
		long tick = payer.level().getServer().getTickCount();

		List<DialogBody> body = new ArrayList<>();
		body.add(Dialogs.text(dimensionChanged
				? "The destination moved to another dimension."
				: "The fare went up before you travelled."));
		body.add(Dialogs.text("New price: " + describeFare(fare)));
		body.add(Dialogs.text("Distance: " + Math.round(fare.distance()) + " blocks"));

		CompoundTag payload = new CompoundTag();
		payload.putString(KEY_REQUEST, request.id().toString());

		Deepgate.dialogs().open(payer, Dialogs.confirmation(
				"Approve new fare",
				body,
				Dialogs.commit(Deepgate.dialogs(), payer.getUUID(), tick,
						Component.literal("Travel"), APPROVE_FARE, payload),
				// Nothing sits behind this prompt; dismissing changes no state and the request simply
				// stays pending until the payer retries or it expires.
				Dialogs.close()));
	}

	// ------------------------------------------------------------ handlers

	private static void onSelect(ServerPlayer player, CompoundTag payload) {
		resolveTarget(player, payload).ifPresent(target ->
				openRequestConfirmation(player, target, kindOf(payload), true));
	}

	private static void onSend(ServerPlayer player, CompoundTag payload) {
		Optional<ServerPlayer> target = resolveTarget(player, payload);

		if (target.isEmpty()) {
			return;
		}

		send(player, target.get(), kindOf(payload));
	}

	/** Open a request and tell the recipient, without opening anything on their screen. */
	public static void send(ServerPlayer sender, ServerPlayer target, TpaRequest.Kind kind) {
		MinecraftServer server = sender.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);
		RequestService.CreateOutcome outcome = Deepgate.requests().create(sender, target, kind, rules);

		if (outcome instanceof RequestService.CreateOutcome.Refused refused) {
			sender.sendSystemMessage(Component.literal(refused.failure().message()));
			return;
		}

		boolean replaced = ((RequestService.CreateOutcome.Created) outcome).replaced();
		String senderName = sender.getGameProfile().name();
		String targetName = target.getGameProfile().name();

		// One outgoing request per player, so say plainly when this one displaced an earlier one.
		// The fare goes in the same line: with no confirmation screen in the way, this is where the
		// payer finds out what the trip costs.
		TpaRequest created = ((RequestService.CreateOutcome.Created) outcome).request();
		String price = created.approvedFare().isFree()
				? "It is free."
				: (kind == TpaRequest.Kind.TPA
						? "You will pay " + fareAmount(created.approvedFare()) + " if they accept."
						: "They will pay " + fareAmount(created.approvedFare()) + " if they accept.");

		sender.sendSystemMessage(Component.literal(
				(replaced ? "Replaced your previous request. " : "")
						+ "Request sent to " + targetName + ". " + price + " Expires in 30 seconds."));

		Component prompt = Component.literal(kind == TpaRequest.Kind.TPA
				? senderName + " wants to teleport to you. "
				: senderName + " wants you to teleport to them. ");

		// A chat nudge, not a forced screen: an incoming request never interrupts the recipient.
		target.sendSystemMessage(prompt.copy()
				.append(Component.literal("[Accept]").withStyle(style -> style
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.RunCommand("/tpaccept " + senderName))))
				.append(Component.literal(" "))
				.append(Component.literal("[Deny]").withStyle(style -> style
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.RunCommand("/tpadeny " + senderName)))));
	}

	private static void onAccept(ServerPlayer player, CompoundTag payload) {
		MinecraftServer server = player.level().getServer();
		Optional<UUID> requestId = readUuid(payload, KEY_REQUEST);

		if (requestId.isEmpty()) {
			return;
		}

		Optional<TpaRequest> maybe = Deepgate.requests().byId(requestId.get());

		// Re-check that this player is really the recipient: the payload came from a client.
		if (maybe.isEmpty() || !maybe.get().targetId().equals(player.getUUID())) {
			player.sendSystemMessage(Component.literal("That request is no longer available."));
			return;
		}

		handleOutcome(server, Deepgate.requests().accept(server, requestId.get(), DeepgateRules.snapshot(server)),
				player);
	}

	private static void onDeny(ServerPlayer player, CompoundTag payload) {
		Optional<UUID> requestId = readUuid(payload, KEY_REQUEST);

		if (requestId.isEmpty()) {
			return;
		}

		Optional<TpaRequest> maybe = Deepgate.requests().byId(requestId.get());

		if (maybe.isEmpty() || !maybe.get().targetId().equals(player.getUUID())) {
			return;
		}

		denyRequest(player, maybe.get());
	}

	private static void denyRequest(ServerPlayer player, TpaRequest request) {
		Deepgate.requests().deny(request.id());

		player.sendSystemMessage(Component.literal("Request denied."));

		ServerPlayer sender = player.level().getServer().getPlayerList().getPlayer(request.senderId());

		if (sender != null) {
			sender.sendSystemMessage(
					Component.literal(player.getGameProfile().name() + " denied your request."));
		}
	}

	// ------------------------------------------------------------ commands

	/**
	 * {@code /tpaccept [player]}: agree to whatever is waiting on you.
	 *
	 * <p>That is usually an incoming request, but it is also how a payer approves a fare that rose
	 * before travel. Answering both from one command is what keeps the dialog optional rather than
	 * required - no Deepgate action should need a client to complete.
	 */
	public static void acceptCommand(ServerPlayer player, ServerPlayer namedSender) {
		MinecraftServer server = player.level().getServer();
		RuleSnapshot rules = DeepgateRules.snapshot(server);

		Optional<TpaRequest> awaitingFare = Deepgate.requests().awaitingFareApproval(player.getUUID());

		if (awaitingFare.isPresent()) {
			handleOutcome(server,
					Deepgate.requests().approveFare(server, awaitingFare.get().id(), rules), player);
			return;
		}

		pickIncoming(player, namedSender).ifPresent(request ->
				handleOutcome(server, Deepgate.requests().accept(server, request.id(), rules), player));
	}

	/** {@code /tpadeny [player]}: refuse an incoming request, or a fare waiting on you. */
	public static void denyCommand(ServerPlayer player, ServerPlayer namedSender) {
		Optional<TpaRequest> awaitingFare = Deepgate.requests().awaitingFareApproval(player.getUUID());

		if (awaitingFare.isPresent()) {
			TpaRequest request = awaitingFare.get();
			Deepgate.requests().deny(request.id());
			player.sendSystemMessage(Component.literal("Declined the new fare. Nothing was charged."));

			UUID otherId = request.payerId().equals(request.senderId())
					? request.targetId()
					: request.senderId();
			ServerPlayer other = player.level().getServer().getPlayerList().getPlayer(otherId);

			if (other != null) {
				other.sendSystemMessage(Component.literal(
						player.getGameProfile().name() + " declined the new fare."));
			}

			return;
		}

		pickIncoming(player, namedSender).ifPresent(request -> denyRequest(player, request));
	}

	/**
	 * Work out which pending request a command meant, and explain it when there is no single answer.
	 *
	 * <p>With several waiting and no name given, this refuses rather than guessing: picking wrong
	 * would teleport someone somewhere they did not ask to go and charge them for it.
	 */
	private static Optional<TpaRequest> pickIncoming(ServerPlayer player, ServerPlayer namedSender) {
		List<TpaRequest> incoming = Deepgate.requests().incoming(player.getUUID());
		List<UUID> senders = incoming.stream().map(TpaRequest::senderId).toList();
		UUID wanted = namedSender == null ? null : namedSender.getUUID();

		RequestChoice.Result choice = RequestChoice.choose(senders, wanted);

		switch (choice.kind()) {
			case CHOSEN -> {
				return Optional.of(incoming.get(choice.index()));
			}
			case NONE_PENDING -> player.sendSystemMessage(
					Component.literal("You have no teleport requests."));
			case NO_MATCH_FOR_SENDER -> player.sendSystemMessage(Component.literal(
					"No request from " + namedSender.getGameProfile().name() + "."));
			case AMBIGUOUS -> player.sendSystemMessage(Component.literal(
					"You have " + incoming.size() + " requests: " + senderNames(player, incoming)
							+ ". Name one, or use /tparequests."));
		}

		return Optional.empty();
	}

	private static String senderNames(ServerPlayer player, List<TpaRequest> incoming) {
		MinecraftServer server = player.level().getServer();

		return incoming.stream()
				.map(request -> server.getPlayerList().getPlayer(request.senderId()))
				.filter(sender -> sender != null)
				.map(sender -> sender.getGameProfile().name())
				.collect(java.util.stream.Collectors.joining(", "));
	}

	private static void onApproveFare(ServerPlayer player, CompoundTag payload) {
		MinecraftServer server = player.level().getServer();
		Optional<UUID> requestId = readUuid(payload, KEY_REQUEST);

		if (requestId.isEmpty()) {
			return;
		}

		Optional<TpaRequest> maybe = Deepgate.requests().byId(requestId.get());

		// Only the payer may approve a fare, and only their own.
		if (maybe.isEmpty() || !maybe.get().payerId().equals(player.getUUID())) {
			player.sendSystemMessage(Component.literal("That request is no longer available."));
			return;
		}

		handleOutcome(server,
				Deepgate.requests().approveFare(server, requestId.get(), DeepgateRules.snapshot(server)), player);
	}

	private static void handleOutcome(MinecraftServer server, RequestService.Outcome outcome, ServerPlayer actor) {
		switch (outcome) {
			case RequestService.Outcome.Completed completed -> {
				ServerPlayer mover = server.getPlayerList().getPlayer(completed.request().moverId());
				ServerPlayer anchor = server.getPlayerList().getPlayer(completed.request().anchorId());

				if (mover != null) {
					Feedback.teleported(mover, anchorName(anchor), completed.pointsCharged());
				}

				if (anchor != null && anchor != mover) {
					anchor.sendSystemMessage(Component.literal("Teleport complete."));
				}
			}
			case RequestService.Outcome.NeedsApproval needs -> {
				ServerPlayer payer = server.getPlayerList().getPlayer(needs.request().payerId());

				if (payer == null) {
					return;
				}

				openFareApproval(payer, needs.request(), needs.newFare(), needs.dimensionChanged());

				// The dialog is the nicer path, never the only one.
				payer.sendSystemMessage(Component.literal(
								(needs.dimensionChanged()
										? "The destination changed dimension. "
										: "The fare went up. ")
										+ "New price: " + describeFare(needs.newFare()) + ". ")
						.copy()
						.append(Component.literal("[Travel]").withStyle(style -> style
								.withUnderlined(true)
								.withClickEvent(new ClickEvent.RunCommand("/tpaccept"))))
						.append(Component.literal(" "))
						.append(Component.literal("[Cancel]").withStyle(style -> style
								.withUnderlined(true)
								.withClickEvent(new ClickEvent.RunCommand("/tpadeny")))));

				if (payer != actor) {
					actor.sendSystemMessage(
							Component.literal("Waiting for " + payer.getGameProfile().name()
									+ " to approve the new fare."));
				}
			}
			case RequestService.Outcome.Refused refused ->
					actor.sendSystemMessage(Component.literal(refused.failure().message()));
		}
	}

	// ------------------------------------------------------------ helpers

	private static String anchorName(ServerPlayer anchor) {
		return anchor == null ? "your destination" : anchor.getGameProfile().name();
	}

	/** Just the amount and unit, for use inside a sentence. */
	public static String fareAmount(Fare fare) {
		return fare.inLevels() ? fare.amount() + " Levels" : "XP " + fare.amount();
	}

	/** Fare rendered in whichever unit the gamerule selected (section 6). */
	public static String describeFare(Fare fare) {
		if (fare.isFree()) {
			return "Teleport - free";
		}

		return fare.inLevels()
				? "Teleport - " + fare.amount() + " Levels"
				: "Teleport - XP " + fare.amount();
	}

	private static TpaRequest.Kind kindOf(CompoundTag payload) {
		return TpaRequest.Kind.TPA_HERE.name().equals(payload.getStringOr(KEY_KIND, ""))
				? TpaRequest.Kind.TPA_HERE
				: TpaRequest.Kind.TPA;
	}

	private static Optional<ServerPlayer> resolveTarget(ServerPlayer player, CompoundTag payload) {
		Optional<UUID> targetId = readUuid(payload, KEY_TARGET);

		if (targetId.isEmpty()) {
			return Optional.empty();
		}

		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId.get());

		if (target == null) {
			player.sendSystemMessage(Component.literal("That player is no longer online."));
			return Optional.empty();
		}

		return Optional.of(target);
	}

	private static Optional<UUID> readUuid(CompoundTag payload, String key) {
		String raw = payload.getStringOr(key, "");

		if (raw.isEmpty()) {
			return Optional.empty();
		}

		try {
			return Optional.of(UUID.fromString(raw));
		} catch (IllegalArgumentException e) {
			// A malformed id can only come from a hand-crafted packet; ignore it.
			return Optional.empty();
		}
	}
}
