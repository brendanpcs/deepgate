package com.brendan.deepgate.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks what a teleport transaction has consumed so it can all be handed back on failure.
 *
 * <p>Resources are rolled back in reverse order of consumption. Rollback is best effort: one
 * failing undo must not strand the others, so failures are collected and returned for the caller
 * to log rather than thrown.
 */
public final class TxnLedger {
	private final Deque<TxnResource> consumed = new ArrayDeque<>();

	/**
	 * Consume a resource, recording it for rollback if it succeeds.
	 *
	 * @return true if the resource was taken
	 */
	public boolean consume(TxnResource resource) {
		if (!resource.consume()) {
			return false;
		}

		consumed.push(resource);
		return true;
	}

	/**
	 * Hand everything back, most recently consumed first.
	 *
	 * @return descriptions of resources whose rollback threw, empty when all succeeded
	 */
	public List<String> rollbackAll() {
		List<String> failed = new ArrayList<>();

		while (!consumed.isEmpty()) {
			TxnResource resource = consumed.pop();

			try {
				resource.rollback();
			} catch (RuntimeException e) {
				failed.add(resource.describe() + ": " + e);
			}
		}

		return failed;
	}

	/** Called once the teleport has landed; there is nothing left to undo. */
	public void commit() {
		consumed.clear();
	}

	public boolean isEmpty() {
		return consumed.isEmpty();
	}
}
