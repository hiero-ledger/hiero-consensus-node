// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicit;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CrsPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsKeyPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsPartialSignature;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HintsPreprocessingVote;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryAssemblySignature;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofKeyPublication;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.HistoryProofVote;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.MigrationRootHashVote;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;

import com.google.protobuf.ByteString;
import com.hedera.hapi.services.auxiliary.blockrecords.legacy.MigrationRootHashVoteTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsPartialSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.legacy.HintsPreprocessingVoteTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.history.legacy.HistoryProofVoteTransactionBody;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiExplicitTxn;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that an atomic batch cannot carry a node-protocol (TSS) transaction, even when a superuser pays for it.
 */
@Tag(ATOMIC_BATCH)
public class AtomicBatchNodeTransactionBlacklistTest {

    private static final int SHA_384_HASH_LENGTH = 48;

    @HapiTest
    final Stream<DynamicTest> nodeTransactionsAreRejectedInBatch() {
        return hapiTest(Stream.of(
                        explicit(
                                HintsKeyPublication,
                                (spec, b) -> b.setHintsKeyPublication(
                                        HintsKeyPublicationTransactionBody.getDefaultInstance())),
                        explicit(
                                HintsPreprocessingVote,
                                (spec, b) -> b.setHintsPreprocessingVote(
                                        HintsPreprocessingVoteTransactionBody.getDefaultInstance())),
                        explicit(
                                HintsPartialSignature,
                                (spec, b) -> b.setHintsPartialSignature(
                                        HintsPartialSignatureTransactionBody.getDefaultInstance())),
                        explicit(
                                HistoryAssemblySignature,
                                (spec, b) -> b.setHistoryProofSignature(
                                        HistoryProofSignatureTransactionBody.getDefaultInstance())),
                        explicit(
                                HistoryProofKeyPublication,
                                (spec, b) -> b.setHistoryProofKeyPublication(
                                        HistoryProofKeyPublicationTransactionBody.getDefaultInstance())),
                        explicit(
                                HistoryProofVote,
                                (spec, b) ->
                                        b.setHistoryProofVote(HistoryProofVoteTransactionBody.getDefaultInstance())),
                        explicit(
                                CrsPublication,
                                (spec, b) -> b.setCrsPublication(CrsPublicationTransactionBody.getDefaultInstance())),
                        explicit(
                                MigrationRootHashVote,
                                // A structurally valid vote, so it passes the inner pure checks
                                (spec, b) ->
                                        b.setMigrationRootHashVote(MigrationRootHashVoteTransactionBody.newBuilder()
                                                .setPreviousWrappedRecordBlockRootHash(
                                                        ByteString.copyFrom(new byte[SHA_384_HASH_LENGTH])))))
                .map(AtomicBatchNodeTransactionBlacklistTest::batchOf)
                .toArray(SpecOperation[]::new));
    }

    private static SpecOperation batchOf(final HapiExplicitTxn inner) {
        return atomicBatch(inner.payingWith(GENESIS).fee(ONE_HBAR).batchKey(GENESIS))
                .payingWith(GENESIS)
                .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST);
    }
}
