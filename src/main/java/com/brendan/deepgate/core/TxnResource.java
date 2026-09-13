package com.brendan.deepgate.core;

/**
 * Something a teleport consumes that must be given back if the teleport does not complete.
 *
 * <p>Spec section 50 requires an experience rollback when the final move fails, and section 12
 * additionally requires the experience charge, the respawn-anchor charge and the teleport to commit
 * as one unit. Modelling both as resources with their own undo keeps that promise honest: there is
 * no path where a failed move silently eats an anchor charge.
 */
public interface TxnResource {
	/**
	 * Take the resource.
	 *
	 * @return true if it was taken; false leaves the resource untouched and fails the transaction
	 */
	boolean consume();

	/** Give the resource back. Only ever called for a resource that was successfully consumed. */
	void rollback();

	/** Short description used in rollback failure logging. */
	String describe();
}
