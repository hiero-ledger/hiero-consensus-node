// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Regression coverage for the {@code BlockStreamBuilder} legacy-API mismatch that surfaced on the
 * BNCE environment: with {@code blockStream.streamMode=BLOCKS}, several contract-op code paths
 * used to invoke deprecated record-stream-only methods on the active stream builder
 * ({@code contractCreateResult}, {@code contractCallResult}, {@code addContractBytecode},
 * {@code addContractActions}). The {@code BlockStreamBuilder} rejects these with
 * {@code UnsupportedOperationException}, which propagated out of {@code ContractCreateHandler}
 * and was remapped to {@code INVALID_TRANSACTION_BODY} by {@code TransactionDispatcher}'s
 * catch-all — so otherwise valid {@code ContractCreate} / {@code ContractCall} transactions failed
 * on any node running with {@code streamMode=BLOCKS}.
 *
 * <p>The fix gates each legacy call on the active stream mode. These tests fail fast (with
 * {@code INVALID_TRANSACTION_BODY}) if any of those guards is removed or regresses.
 */
class BlockStreamModeContractOperationsTest {
    private static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ContractCreate succeeds when blockStream.streamMode=BLOCKS")
    Stream<DynamicTest> contractCreateSucceedsWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).hasKnownStatus(SUCCESS));
    }

    @LeakyHapiTest(overrides = "blockStream.streamMode")
    @DisplayName("ContractCall succeeds when blockStream.streamMode=BLOCKS")
    Stream<DynamicTest> contractCallSucceedsWithStreamModeBlocks() {
        return hapiTest(
                overriding("blockStream.streamMode", "BLOCKS"),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).hasKnownStatus(SUCCESS),
                // Any call against the deployed contract exercises the
                // CallOutcome.addCallDetailsTo path that also gates contractCallResult.
                contractCall(EMPTY_CONSTRUCTOR_CONTRACT).hasKnownStatus(SUCCESS));
    }
}
