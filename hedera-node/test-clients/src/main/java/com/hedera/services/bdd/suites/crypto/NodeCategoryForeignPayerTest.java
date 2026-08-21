// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.reducedFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.unchangedFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Verifies that a NODE-category transaction (one with an empty signature map) cannot debit a foreign
 * account named as its payer.
 *
 * <p>A transaction with an empty signature map is categorized as a {@code NODE} transaction (see
 * {@code ParentTxnFactory#getTxnCategory}), and NODE transactions skip payer-signature verification.
 * Such a transaction can reach consensus without ingest checks when it is submitted directly to a
 * non-default node. If it names a foreign account as its payer (which is also the sole sender, so the
 * payer key is the very key NODE category skips), then without a guard the transfer would debit that
 * account with no signature at all.
 *
 * <p>{@code DispatchValidator} treats a NODE-category dispatch whose payer is neither the system admin
 * account nor the creator node's own account as a node due-diligence failure: the transaction fails with
 * {@code INVALID_PAYER_ACCOUNT_ID}, the named payer is left untouched, and the submitting node is charged
 * the network fee. This mirrors the existing {@code DuplicateManagementTest} behavior for an authorized
 * (USER) transaction submitted without a valid payer signature.
 */
public class NodeCategoryForeignPayerTest {
    private static final String FOREIGN_PAYER = "foreignPayer";
    // 0.0.4 is a non-default node; submitting to it skips ingest in embedded mode
    private static final String SUBMITTING_NODE_ACCOUNT_ID = "4";

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST, requirement = SYSTEM_ACCOUNT_BALANCES)
    @DisplayName("a node cannot debit a foreign payer with an unsigned NODE-category transaction")
    final Stream<DynamicTest> nodeCannotDebitForeignPayerWithUnsignedNodeTransaction() {
        return hapiTest(
                cryptoCreate(FOREIGN_PAYER).balance(ONE_HUNDRED_HBARS),
                // Fund the submitting node so we can observe it being charged the network fee
                cryptoTransfer(tinyBarsFromTo(GENESIS, SUBMITTING_NODE_ACCOUNT_ID, ONE_HBAR)),
                balanceSnapshot("foreignPayerPre", FOREIGN_PAYER),
                balanceSnapshot("nodePre", SUBMITTING_NODE_ACCOUNT_ID),
                // Submit an unsigned transfer directly to a non-default node (skipping ingest) that names
                // the foreign account as payer; the empty signature map makes it a NODE transaction, which
                // skips payer-sig checks
                cryptoTransfer(tinyBarsFromTo(FOREIGN_PAYER, SUBMITTING_NODE_ACCOUNT_ID, ONE_HBAR))
                        .payingWithNoSig(FOREIGN_PAYER)
                        .signedBy()
                        .fee(ONE_HBAR)
                        .setNode(SUBMITTING_NODE_ACCOUNT_ID)
                        .hasKnownStatus(INVALID_PAYER_ACCOUNT_ID),
                // The foreign payer keeps every tinybar...
                getAccountBalance(FOREIGN_PAYER).hasTinyBars(unchangedFromSnapshot("foreignPayerPre")),
                // ...and the submitting node is charged the network fee for its due-diligence failure
                getAccountBalance(SUBMITTING_NODE_ACCOUNT_ID).hasTinyBars(reducedFromSnapshot("nodePre")));
    }
}
