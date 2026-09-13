package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReapprovalPolicyTest {
	@Test
	void anUnchangedFareTravelsWithoutAskingAgain() {
		assertFalse(ReapprovalPolicy.needsApproval(15, 15, false));
	}

	@Test
	void aCheaperFareTravelsAndIsChargedAtTheLowerAmount() {
		assertFalse(ReapprovalPolicy.needsApproval(15, 4, false));
		assertFalse(ReapprovalPolicy.needsApproval(15, 0, false));
	}

	@Test
	void aHigherFareNeedsApproval() {
		assertTrue(ReapprovalPolicy.needsApproval(15, 16, false));
		assertTrue(ReapprovalPolicy.needsApproval(0, 1, false));
	}

	@Test
	void aDimensionChangeAlwaysNeedsApprovalEvenWhenCheaper() {
		assertTrue(ReapprovalPolicy.needsApproval(100, 1, true));
		assertTrue(ReapprovalPolicy.needsApproval(15, 15, true));
		assertTrue(ReapprovalPolicy.needsApproval(15, 40, true));
	}

	@Test
	void aFreeQuoteThatStaysFreeNeedsNothing() {
		assertFalse(ReapprovalPolicy.needsApproval(0, 0, false));
	}
}
