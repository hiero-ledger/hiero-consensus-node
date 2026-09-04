// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Verifies node due-diligence charging when an unauthorized system-file update reaches consensus.
 *
 * <p>Whether a payer may update a system file (here the exchange-rate file 0.0.112) is determined from the
 * payer and the transaction body alone, so a node can decide it during ingest. When such an update is
 * rejected at ingest it never reaches consensus. If it does reach consensus — for example when it is
 * submitted directly to a non-default node so ingest is skipped — then the submitting node, rather than the
 * named payer, is charged: reaching handle means the node did not perform this check itself.
 *
 * <p>The test submits to non-default node 0.0.4 under {@code MUST_SKIP_INGEST} so the update lands at
 * handle, then asserts the submitting node is the charged account. The transaction resolves to
 * {@code AUTHORIZATION_FAILED}, and the handle-side due-diligence check attributes this to the node that
 * failed to reject it at ingest, so the submitting node is charged rather than the named payer.
 *
 * <p>Also documents the same scenario inside an atomic batch, where the submitting node is currently not
 * charged: inner-transaction dispatches skip creator charging, so the batch fails with
 * {@code INNER_TRANSACTION_FAILED} and the batch payer is charged instead.
 */
public class SystemFileUpdateDueDiligenceTest {
    // 0.0.4 is a non-default node; submitting to it bypasses ingest in embedded mode
    private static final String SUBMITTING_NODE_ACCOUNT_ID = "4";

    @EmbeddedHapiTest(value = MUST_SKIP_INGEST)
    @DisplayName("an unauthorized system-file update that bypassed ingest is a node due-diligence failure")
    final Stream<DynamicTest> unauthorizedSystemFileUpdateThatBypassedIngestChargesTheNode() {
        final var nonPrivilegedPayer = "nonPrivilegedPayer";
        final var dueDiligenceTxn = "dueDiligenceTxn";
        return hapiTest(
                cryptoCreate(nonPrivilegedPayer),
                // Fund the submitting node so its due-diligence charge can be collected
                cryptoTransfer(tinyBarsFromTo(GENESIS, SUBMITTING_NODE_ACCOUNT_ID, ONE_HBAR)),
                // Submit to a non-default node so ingest is skipped and the update reaches handle. Reaching
                // handle means the node did not reject the update itself, so the node — not the named payer
                // — is charged.
                fileUpdate(EXCHANGE_RATES)
                        .contents("Should be impossible!")
                        .payingWith(nonPrivilegedPayer)
                        .via(dueDiligenceTxn)
                        .setNode(SUBMITTING_NODE_ACCOUNT_ID)
                        .hasKnownStatus(AUTHORIZATION_FAILED),
                // The handle-side due-diligence check charges the submitting node 0.0.4, not the payer.
                validateChargedAccount(dueDiligenceTxn, SUBMITTING_NODE_ACCOUNT_ID));
    }

    @EmbeddedHapiTest(value = MUST_SKIP_INGEST)
    @DisplayName("an unauthorized system-file update with a top-level batch key that bypassed ingest charges the node")
    final Stream<DynamicTest> unauthorizedSystemFileUpdateWithTopLevelBatchKeyChargesTheNode() {
        final var nonPrivilegedPayer = "nonPrivilegedPayer";
        final var batchOperator = "batchOperator";
        final var dueDiligenceTxn = "dueDiligenceTxn";
        return hapiTest(
                cryptoCreate(nonPrivilegedPayer),
                cryptoCreate(batchOperator),
                // Fund the submitting node so its due-diligence charge can be collected
                cryptoTransfer(tinyBarsFromTo(GENESIS, SUBMITTING_NODE_ACCOUNT_ID, ONE_HBAR)),
                // A stray batch key on a top-level (non-inner) transaction is invalid, but the authorization
                // failure is decidable from the payer and body, so a node that lets this reach consensus is
                // charged for its due diligence. The batch key must not mask the authorization failure and
                // shift the charge to the payer.
                fileUpdate(EXCHANGE_RATES)
                        .contents("Should be impossible!")
                        .payingWith(nonPrivilegedPayer)
                        .batchKey(batchOperator)
                        .via(dueDiligenceTxn)
                        .setNode(SUBMITTING_NODE_ACCOUNT_ID)
                        .hasKnownStatus(AUTHORIZATION_FAILED),
                validateChargedAccount(dueDiligenceTxn, SUBMITTING_NODE_ACCOUNT_ID));
    }

    @EmbeddedHapiTest(value = MUST_SKIP_INGEST)
    @DisplayName(
            "an unauthorized system-file update inside an atomic batch that bypassed ingest charges the batch payer")
    final Stream<DynamicTest> unauthorizedSystemFileUpdateInAtomicBatchThatBypassedIngestChargesTheBatchPayer() {
        final var nonPrivilegedPayer = "nonPrivilegedPayer";
        final var batchOperator = "batchOperator";
        final var batchTxn = "batchTxn";
        return hapiTest(
                cryptoCreate(nonPrivilegedPayer),
                cryptoCreate(batchOperator),
                // Fund the submitting node so a due-diligence charge could be collected from it
                cryptoTransfer(tinyBarsFromTo(GENESIS, SUBMITTING_NODE_ACCOUNT_ID, ONE_HBAR)),
                // Ingest privilege-checks batch inner transactions too, so an honest node rejects this batch
                // outright; submitting to a non-default node bypasses ingest and lets it reach consensus.
                // This documents the current charging for that case: pre-handle flags the inner update as a
                // node due-diligence failure, but BATCH_INNER dispatches skip creator charging, so the batch
                // fails with INNER_TRANSACTION_FAILED and the batch payer — not the submitting node — is
                // charged. Whether the node should be charged instead is tracked as a follow-up.
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .contents("Should be impossible!")
                                .payingWith(nonPrivilegedPayer)
                                .batchKey(batchOperator)
                                .hasKnownStatus(AUTHORIZATION_FAILED))
                        .payingWith(batchOperator)
                        .via(batchTxn)
                        .setNode(SUBMITTING_NODE_ACCOUNT_ID)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedAccount(batchTxn, batchOperator));
    }
}
