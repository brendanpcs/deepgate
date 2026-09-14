package com.brendan.deepgate.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Failure;
import com.brendan.deepgate.core.Fare;
import com.brendan.deepgate.core.Quotes;
import com.brendan.deepgate.core.ReapprovalPolicy;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.TeleportService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Outstanding {@code /tpa} and {@code /tpahere} requests (spec section 8).
 *
 * <p>A request holds a quote, not a charge. Immediately before travel everything is recalculated:
 * position, dimension, combat, destination, safety and fare. A fare at or below what the payer
 * approved is charged silently; a higher one, or any change of destination dimension, needs fresh
 * approval from the payer before anything is taken.
 *
 * <p>All state here is memory-only and does not survive a restart (section 48).
 */
public final class RequestService {
	private static final int TICKS_PER_SECOND = 20;
	private static final int LIFETIME_SECONDS = 30;

	private final TeleportService teleports;
	private final List<TpaRequest> requests = new ArrayList<>();

	public RequestService(TeleportService teleports) {
		this.teleports = teleports;
	}

	/** What happened when someone acted on an existing request. */
	public sealed interface Outcome {
		/** The teleport ran and was charged. */
		record Completed(TpaRequest request, int pointsCharged) implements Outcome {
		}

		/** The fare moved against the payer, who now has to approve {@code newFare}. */
		record NeedsApproval(TpaRequest request, Fare newFare, boolean dimensionChanged) implements Outcome {
		}

		record Refused(Failure failure) implements Outcome {
		}
	}

	/** What happened when someone opened a request. Separate, because nothing has travelled yet. */
	public sealed interface CreateOutcome {
		/** The request is now pending, and {@code replaced} says whether it displaced an older one. */
		record Created(TpaRequest request, boolean replaced) implements CreateOutcome {
		}

		record Refused(Failure failure) implements CreateOutcome {
		}
	}

	// ------------------------------------------------------------ lifecycle

	/** Every request awaiting this player, newest last. Recipients may hold several at once. */
	public List<TpaRequest> incoming(UUID targetId) {
		return requests.stream().filter(r -> r.targetId().equals(targetId)).toList();
	}

	public Optional<TpaRequest> byId(UUID requestId) {
		return requests.stream().filter(r -> r.id().equals(requestId)).findFirst();
	}

	/**
	 * The request, if any, holding a changed fare that this player has to agree to.
	 *
	 * <p>The payer is not necessarily the recipient, so this is deliberately not part of
	 * {@link #incoming(UUID)}: for {@code /tpa} the payer is the sender, and their own request is
	 * what is waiting on them.
	 */
	public Optional<TpaRequest> awaitingFareApproval(UUID payerId) {
		return requests.stream()
				.filter(r -> r.state() == TpaRequest.State.PENDING_PAYER)
				.filter(r -> r.payerId().equals(payerId))
				.findFirst();
	}

	/**
	 * Open a request.
	 *
	 * <p>The quote recorded here is what the payer is shown; it is never what they are charged.
	 */
	public CreateOutcome create(ServerPlayer sender, ServerPlayer target, TpaRequest.Kind kind, RuleSnapshot rules) {
		if (sender.getUUID().equals(target.getUUID())) {
			return new CreateOutcome.Refused(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "You cannot send a request to yourself"));
		}

		// One outgoing request per player: opening a new one replaces whatever was pending.
		boolean replaced = requests.removeIf(r -> r.senderId().equals(sender.getUUID()));

		MinecraftServer server = sender.level().getServer();
		ServerPlayer mover = kind == TpaRequest.Kind.TPA ? sender : target;
		ServerPlayer anchor = kind == TpaRequest.Kind.TPA ? target : sender;

		Fare quote = Quotes.quote(mover, anchor, rules);

		TpaRequest request = new TpaRequest(
				sender.getUUID(),
				target.getUUID(),
				kind,
				quote,
				anchor.level().dimension(),
				server.getTickCount() + (long) LIFETIME_SECONDS * TICKS_PER_SECOND);

		requests.add(request);
		return new CreateOutcome.Created(request, replaced);
	}

	/** Cancel whatever this player sent. */
	public boolean cancel(UUID senderId) {
		return requests.removeIf(r -> r.senderId().equals(senderId));
	}

	public void deny(UUID requestId) {
		requests.removeIf(r -> r.id().equals(requestId));
	}

	/** Drop everything involving a player; a disconnect cancels their requests either way. */
	public void onPlayerGone(UUID playerId) {
		requests.removeIf(r -> r.involves(playerId));
	}

	/** Expire silently. An expired request charges nothing, because nothing was ever taken. */
	public List<TpaRequest> tick(MinecraftServer server) {
		long now = server.getTickCount();
		List<TpaRequest> expired = requests.stream().filter(r -> r.isExpired(now)).toList();
		requests.removeAll(expired);
		return expired;
	}

	// ------------------------------------------------------------ commit

	/**
	 * The recipient accepted. Recalculates everything and either travels or asks the payer to
	 * approve a fare that has moved.
	 */
	public Outcome accept(MinecraftServer server, UUID requestId, RuleSnapshot rules) {
		Optional<TpaRequest> maybe = byId(requestId);

		if (maybe.isEmpty()) {
			return new Outcome.Refused(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That request is no longer available"));
		}

		TpaRequest request = maybe.get();

		if (request.isExpired(server.getTickCount())) {
			requests.remove(request);
			return new Outcome.Refused(Failure.of(Failure.Reason.DESTINATION_MISSING, "That request expired"));
		}

		return commit(server, request, rules, false);
	}

	/** The payer approved a fare that had gone up. Revalidate from scratch, then travel. */
	public Outcome approveFare(MinecraftServer server, UUID requestId, RuleSnapshot rules) {
		Optional<TpaRequest> maybe = byId(requestId);

		if (maybe.isEmpty()) {
			return new Outcome.Refused(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That request is no longer available"));
		}

		TpaRequest request = maybe.get();

		if (request.isExpired(server.getTickCount())) {
			requests.remove(request);
			return new Outcome.Refused(Failure.of(Failure.Reason.DESTINATION_MISSING, "That request expired"));
		}

		if (request.state() != TpaRequest.State.PENDING_PAYER) {
			return new Outcome.Refused(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "There is no fare awaiting your approval"));
		}

		return commit(server, request, rules, true);
	}

	/**
	 * Recalculate and either travel or bounce back for approval.
	 *
	 * @param payerJustApproved true when the payer has explicitly agreed to the fare being quoted
	 *                          now, which stops an unchanged fare from looping for approval forever
	 */
	private Outcome commit(MinecraftServer server, TpaRequest request, RuleSnapshot rules,
			boolean payerJustApproved) {
		ServerPlayer mover = server.getPlayerList().getPlayer(request.moverId());
		ServerPlayer anchor = server.getPlayerList().getPlayer(request.anchorId());

		if (mover == null || anchor == null) {
			// A disconnect cancels the request outright.
			requests.remove(request);
			return new Outcome.Refused(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That player is no longer online"));
		}

		// The destination is wherever the anchoring player stands right now. Deepgate does not look
		// for a nearby block: that player is standing there, so it is a place a player can stand.
		Destination destination = new Destination(
				anchor.level(), anchor.position(), anchor.getYRot(), anchor.getXRot());

		Fare fare = Quotes.quote(mover, anchor, rules);
		boolean dimensionChanged = !destination.level().dimension().equals(request.approvedDimension());

		// Compare what each fare would actually take from this payer. With mixed units a bare amount
		// is not comparable: two levels and twenty points are not two numbers on the same scale.
		int payerHeld = com.brendan.deepgate.core.XpAccount.totalPoints(
				server.getPlayerList().getPlayer(request.payerId()));
		int approvedPoints = request.approvedFare().pointsFor(payerHeld);
		int currentPoints = fare.pointsFor(payerHeld);

		if (!payerJustApproved
				&& ReapprovalPolicy.needsApproval(approvedPoints, currentPoints, dimensionChanged)) {
			request.state(TpaRequest.State.PENDING_PAYER);
			request.approve(fare, destination.level().dimension());
			return new Outcome.NeedsApproval(request, fare, dimensionChanged);
		}

		// Charge what it actually costs now, which may be less than was approved.
		List<Failure> preFailures = new ArrayList<>();

		// Both participants must be out of combat, not just the one who moves.
		teleports.combat().check(server, mover.getUUID(), rules).ifPresent(preFailures::add);
		teleports.combat().check(server, anchor.getUUID(), rules).ifPresent(preFailures::add);

		TeleportService.Result result = teleports.execute(mover, destination, fare, preFailures, List.of(), rules);

		if (result instanceof TeleportService.Result.Failed failed) {
			// The request survives a failed commit only if it has not expired; the recipient can try
			// again. Nothing was charged.
			return new Outcome.Refused(failed.failure());
		}

		requests.remove(request);
		return new Outcome.Completed(request, ((TeleportService.Result.Success) result).pointsCharged());
	}

	public void clearAll() {
		requests.clear();
	}
}
