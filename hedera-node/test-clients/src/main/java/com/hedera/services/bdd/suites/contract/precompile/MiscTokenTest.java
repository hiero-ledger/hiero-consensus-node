// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("token")
@SuppressWarnings("java:S1192")
@Disabled
@HapiTestLifecycle
public class MiscTokenTest {

    @Contract(contract = "InternalCall", creationGas = 1_000_000L)
    static SpecContract internalCall;

    @FungibleToken(name = "fungibleToken")
    static SpecFungibleToken fungibleToken;

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @DisplayName("cannot transfer value to HTS")
    public Stream<DynamicTest> cannotTransferValueToHts() {
        return hapiTest(internalCall
                .call("isATokenWithCall", fungibleToken)
                .sending(100L)
                .andAssert(txn -> txn.hasKnownStatus(INVALID_CONTRACT_ID)));
    }

    @HapiTest
    @DisplayName("cannot transfer value to HTS")
    public Stream<DynamicTest> atomicCannotTransferValueToHts() {
        return hapiTest(internalCall
                .call("isATokenWithCall", fungibleToken)
                .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                .sending(100L)
                .andAssert(txn -> txn.hasKnownStatus(INVALID_CONTRACT_ID)));
    }
}
