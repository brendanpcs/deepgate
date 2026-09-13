package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestChoiceTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();
	private static final UUID CARL = UUID.randomUUID();

	@Test
	void nothingPendingIsReportedAsSuch() {
		RequestChoice.Result result = RequestChoice.choose(List.of(), null);

		assertEquals(RequestChoice.Kind.NONE_PENDING, result.kind());
		assertFalse(result.isChosen());
	}

	@Test
	void aSingleRequestNeedsNoName() {
		RequestChoice.Result result = RequestChoice.choose(List.of(ALICE), null);

		assertTrue(result.isChosen());
		assertEquals(0, result.index());
	}

	@Test
	void severalRequestsWithNoNameIsRefusedRatherThanGuessed() {
		RequestChoice.Result result = RequestChoice.choose(List.of(ALICE, BOB), null);

		assertEquals(RequestChoice.Kind.AMBIGUOUS, result.kind());
		assertEquals(-1, result.index());
	}

	@Test
	void namingASenderPicksTheirRequestOutOfSeveral() {
		RequestChoice.Result result = RequestChoice.choose(List.of(ALICE, BOB, CARL), BOB);

		assertTrue(result.isChosen());
		assertEquals(1, result.index());
	}

	@Test
	void namingASenderWorksEvenWhenTheirsIsTheOnlyOne() {
		assertEquals(0, RequestChoice.choose(List.of(ALICE), ALICE).index());
	}

	@Test
	void namingSomeoneWithNoRequestIsDistinctFromHavingNone() {
		RequestChoice.Result result = RequestChoice.choose(List.of(ALICE, BOB), CARL);

		assertEquals(RequestChoice.Kind.NO_MATCH_FOR_SENDER, result.kind());
		assertFalse(result.isChosen());
	}

	@Test
	void namingSomeoneWhenNothingIsPendingReportsNonePending() {
		// "You have no requests" is more useful than "no request from Carl" when there are none at all.
		assertEquals(RequestChoice.Kind.NONE_PENDING, RequestChoice.choose(List.of(), CARL).kind());
	}

	@Test
	void theFirstRequestFromADuplicateSenderIsTaken() {
		// One outgoing request per player means this should not arise, but picking the earliest is
		// the predictable answer if it ever does.
		assertEquals(0, RequestChoice.choose(List.of(ALICE, ALICE), ALICE).index());
	}
}
