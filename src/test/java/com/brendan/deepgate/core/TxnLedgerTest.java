package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TxnLedgerTest {
	/** An in-memory stand-in for experience or an anchor charge. */
	private static final class FakeResource implements TxnResource {
		private final String name;
		private final boolean consumable;
		private final boolean rollbackThrows;
		private final List<String> log;
		boolean consumed;
		boolean rolledBack;

		FakeResource(String name, boolean consumable, boolean rollbackThrows, List<String> log) {
			this.name = name;
			this.consumable = consumable;
			this.rollbackThrows = rollbackThrows;
			this.log = log;
		}

		@Override
		public boolean consume() {
			if (!consumable) {
				return false;
			}

			consumed = true;
			log.add("consume:" + name);
			return true;
		}

		@Override
		public void rollback() {
			rolledBack = true;
			log.add("rollback:" + name);

			if (rollbackThrows) {
				throw new IllegalStateException("boom");
			}
		}

		@Override
		public String describe() {
			return name;
		}
	}

	@Test
	void rollbackRunsInReverseOrderOfConsumption() {
		List<String> log = new ArrayList<>();
		TxnLedger ledger = new TxnLedger();

		ledger.consume(new FakeResource("xp", true, false, log));
		ledger.consume(new FakeResource("anchor", true, false, log));

		assertTrue(ledger.rollbackAll().isEmpty());
		assertEquals(List.of("consume:xp", "consume:anchor", "rollback:anchor", "rollback:xp"), log);
	}

	@Test
	void experienceAndAnchorChargeBothComeBackOnAFailedMove() {
		List<String> log = new ArrayList<>();
		FakeResource xp = new FakeResource("xp", true, false, log);
		FakeResource anchor = new FakeResource("anchor", true, false, log);

		TxnLedger ledger = new TxnLedger();
		assertTrue(ledger.consume(xp));
		assertTrue(ledger.consume(anchor));

		// The teleport fails here.
		ledger.rollbackAll();

		assertTrue(xp.rolledBack, "experience must be refunded");
		assertTrue(anchor.rolledBack, "anchor charge must be restored");
		assertTrue(ledger.isEmpty());
	}

	@Test
	void aResourceThatRefusesToBeConsumedIsNotRecorded() {
		List<String> log = new ArrayList<>();
		TxnLedger ledger = new TxnLedger();
		FakeResource refused = new FakeResource("anchor", false, false, log);

		assertFalse(ledger.consume(refused));
		assertTrue(ledger.isEmpty());

		ledger.rollbackAll();
		assertFalse(refused.rolledBack, "never consumed, so never rolled back");
	}

	@Test
	void oneFailingRollbackDoesNotStrandTheOthers() {
		List<String> log = new ArrayList<>();
		FakeResource xp = new FakeResource("xp", true, false, log);
		FakeResource anchor = new FakeResource("anchor", true, true, log);

		TxnLedger ledger = new TxnLedger();
		ledger.consume(xp);
		ledger.consume(anchor);

		List<String> failures = ledger.rollbackAll();

		assertEquals(1, failures.size());
		assertTrue(failures.get(0).startsWith("anchor"), failures.get(0));
		assertTrue(xp.rolledBack, "experience must still be refunded after the anchor undo threw");
	}

	@Test
	void commitLeavesNothingToUndo() {
		List<String> log = new ArrayList<>();
		FakeResource xp = new FakeResource("xp", true, false, log);

		TxnLedger ledger = new TxnLedger();
		ledger.consume(xp);
		ledger.commit();

		assertTrue(ledger.isEmpty());
		ledger.rollbackAll();
		assertFalse(xp.rolledBack, "a committed transaction must never refund");
	}
}
