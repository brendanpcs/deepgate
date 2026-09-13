package com.brendan.deepgate.request;

import java.util.UUID;

import com.brendan.deepgate.core.Fare;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One outstanding teleport request (spec section 8).
 *
 * <p>Requests are memory-only and die with the server, a disconnect, or thirty seconds of silence.
 * Nothing is charged until a commit actually lands.
 */
public final class TpaRequest {
	/** Which way the teleport runs, which decides who moves and who pays. */
	public enum Kind {
		/** {@code /tpa}: the sender moves to the target, and the sender pays. */
		TPA,
		/** {@code /tpahere}: the target moves to the sender, and the target pays. */
		TPA_HERE
	}

	public enum State {
		/** Waiting for the recipient to accept or deny. */
		PENDING_TARGET,
		/** Accepted, but the fare moved against the payer, who now has to approve the new one. */
		PENDING_PAYER
	}

	private final UUID id = UUID.randomUUID();
	private final UUID senderId;
	private final UUID targetId;
	private final Kind kind;
	private final long expiresAtTick;

	private State state = State.PENDING_TARGET;

	/** The fare the payer has agreed to. A commit at or below this amount needs no further consent. */
	private Fare approvedFare;

	/** The destination dimension the payer agreed to; any change always forces fresh approval. */
	private ResourceKey<Level> approvedDimension;

	public TpaRequest(UUID senderId, UUID targetId, Kind kind, Fare approvedFare,
			ResourceKey<Level> approvedDimension, long expiresAtTick) {
		this.senderId = senderId;
		this.targetId = targetId;
		this.kind = kind;
		this.approvedFare = approvedFare;
		this.approvedDimension = approvedDimension;
		this.expiresAtTick = expiresAtTick;
	}

	public UUID id() {
		return id;
	}

	public UUID senderId() {
		return senderId;
	}

	public UUID targetId() {
		return targetId;
	}

	public Kind kind() {
		return kind;
	}

	public State state() {
		return state;
	}

	public void state(State state) {
		this.state = state;
	}

	public Fare approvedFare() {
		return approvedFare;
	}

	public ResourceKey<Level> approvedDimension() {
		return approvedDimension;
	}

	/** Record that the payer has agreed to a new fare and destination dimension. */
	public void approve(Fare fare, ResourceKey<Level> dimension) {
		this.approvedFare = fare;
		this.approvedDimension = dimension;
	}

	public boolean isExpired(long currentTick) {
		return currentTick >= expiresAtTick;
	}

	/** Whoever ends up moving. */
	public UUID moverId() {
		return kind == Kind.TPA ? senderId : targetId;
	}

	/** Whoever ends up paying, which is always the player who moves. */
	public UUID payerId() {
		return moverId();
	}

	/** Whoever stands still and defines the destination. */
	public UUID anchorId() {
		return kind == Kind.TPA ? targetId : senderId;
	}

	public boolean involves(UUID playerId) {
		return senderId.equals(playerId) || targetId.equals(playerId);
	}
}
