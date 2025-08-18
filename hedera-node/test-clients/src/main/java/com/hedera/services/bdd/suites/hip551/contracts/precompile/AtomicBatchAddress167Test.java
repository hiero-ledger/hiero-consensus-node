// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts.precompile;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType.HAPI_IS_TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_167;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchAddress167Test {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
    private static final String ACCOUNT = "account";
    private static final String TOKEN_AND_TYPE_CHECK_CONTRACT = "TokenAndTypeCheck";
    private static final int GAS_TO_OFFER = 1_000_000;
    private static final String GET_TOKEN_TYPE = "getType";
    private static final String IS_TOKEN = "isAToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        // enable atomic batch
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        // create default batch operator
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    /**
     * TokenAndTypeCheckSuite
     */
    // Should just return false on isToken() check for missing token type
    @HapiTest
    final Stream<DynamicTest> atomicCheckTokenAndTypeNegativeCases() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final var notAnAddress = new byte[20];

        return hapiTest(
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                uploadInitCode(Optional.empty(), VARIANT_167, TOKEN_AND_TYPE_CHECK_CONTRACT),
                contractCreate(TOKEN_AND_TYPE_CHECK_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(contractCall(
                                                Optional.of(VARIANT_167),
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                IS_TOKEN,
                                                HapiParserUtil.asHeadlongAddress(notAnAddress))
                                        .via("FakeAddressTokenCheckTx")
                                        .payingWith(ACCOUNT)
                                        .gas(GAS_TO_OFFER)
                                        .batchKey(DEFAULT_BATCH_OPERATOR))
                                .payingWith(DEFAULT_BATCH_OPERATOR),
                        atomicBatch(contractCall(
                                                Optional.of(VARIANT_167),
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                GET_TOKEN_TYPE,
                                                HapiParserUtil.asHeadlongAddress(notAnAddress))
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                        .via("FakeAddressTokenTypeCheckTx")
                                        .payingWith(ACCOUNT)
                                        .gas(GAS_TO_OFFER)
                                        .batchKey(DEFAULT_BATCH_OPERATOR))
                                .payingWith(DEFAULT_BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        "FakeAddressTokenCheckTx",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(HAPI_IS_TOKEN)
                                                .withStatus(SUCCESS)
                                                .withIsToken(false)))),
                childRecordsCheck(
                        "FakeAddressTokenTypeCheckTx",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_TOKEN_ID)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(HAPI_IS_TOKEN)
                                                .withStatus(INVALID_TOKEN_ID)
                                                .withIsToken(false)))));
    }
}
