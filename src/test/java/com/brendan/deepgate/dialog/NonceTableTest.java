package com.brendan.deepgate.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NonceTableTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();

	@Test
	void anIssuedNonceIsAcceptedExactlyOnce() {
		NonceTable table = new NonceTable();
		String nonce = table.issue(ALICE, 0L);

		assertTrue(table.consume(ALICE, nonce, 1L));
		// The second press of the same button, or a duplicated packet, must do nothing.
		assertFalse(table.consume(ALICE, nonce, 1L));
	}

	@Test
	void anotherPlayerCannotSpendYourNonce() {
		NonceTable table = new NonceTable();
		String nonce = table.issue(ALICE, 0L);

		assertFalse(table.consume(BOB, nonce, 1L));
		assertTrue(table.consume(ALICE, nonce, 1L));
	}

	@Test
	void aForgedOrEmptyNonceIsRejected() {
		NonceTable table = new NonceTable();
		table.issue(ALICE, 0L);

		assertFalse(table.consume(ALICE, "not-a-real-nonce", 1L));
		assertFalse(table.consume(ALICE, "", 1L));
		assertFalse(table.consume(ALICE, null, 1L));
	}

	@Test
	void anExpiredNonceIsRejected() {
		NonceTable table = new NonceTable();
		String nonce = table.issue(ALICE, 0L);

		// Well past the five minute lifetime.
		assertFalse(table.consume(ALICE, nonce, 20L * 600L));
	}

	@Test
	void nonceJustInsideItsLifetimeStillWorks() {
		NonceTable table = new NonceTable();
		String nonce = table.issue(ALICE, 0L);

		assertTrue(table.consume(ALICE, nonce, 20L * 300L - 1L));
	}

	@Test
	void issuedNoncesAreDistinct() {
		NonceTable table = new NonceTable();
		Set<String> seen = new HashSet<>();

		for (int i = 0; i < 500; i++) {
			assertTrue(seen.add(table.issue(ALICE, 0L)), "issued a duplicate nonce");
		}

		assertNotEquals(0, seen.size());
	}

	@Test
	void severalButtonsOnOneScreenEachGetTheirOwnSingleUseNonce() {
		NonceTable table = new NonceTable();
		String teleport = table.issue(ALICE, 0L);
		String rename = table.issue(ALICE, 0L);

		assertTrue(table.consume(ALICE, teleport, 1L));
		// Spending one button does not invalidate the others on the same screen.
		assertTrue(table.consume(ALICE, rename, 1L));
	}

	@Test
	void disconnectDropsEverythingForThatPlayerOnly() {
		NonceTable table = new NonceTable();
		String alice = table.issue(ALICE, 0L);
		String bob = table.issue(BOB, 0L);

		table.clear(ALICE);

		assertFalse(table.consume(ALICE, alice, 1L));
		assertTrue(table.consume(BOB, bob, 1L));
	}

	@Test
	void theTableStaysBoundedWhenAClientSpamsScreenOpens() {
		NonceTable table = new NonceTable();
		String first = table.issue(ALICE, 0L);

		// Far more than the per-player ceiling, all at the same tick.
		for (int i = 0; i < 500; i++) {
			table.issue(ALICE, 0L);
		}

		// The oldest is dropped rather than the table growing without limit.
		assertFalse(table.consume(ALICE, first, 1L));
	}
}
