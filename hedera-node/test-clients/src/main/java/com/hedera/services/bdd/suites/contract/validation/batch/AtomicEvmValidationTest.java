// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.validation.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.toAddressString;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("evmValidation")
@HapiTestLifecycle
public class AtomicEvmValidationTest {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @Nested
    @DisplayName("calling balanceOf")
    class BalanceOf {

        @Contract(contract = "BalanceChecker46Version", creationGas = 10_000_000L)
        static SpecContract balanceChecker46Version;

        @HapiTest
        @DisplayName("succeeds on non-existent contract")
        @Tag(MATS)
        public Stream<DynamicTest> canCallBalanceOperationNonExtantContract() {
            final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
            return hapiTest(balanceChecker46Version
                    .call("balanceOf", asHeadlongAddress(INVALID_ADDRESS))
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .andAssert(txn -> txn.hasKnownStatuses(SUCCESS)));
        }
    }

    @Nested
    @DisplayName("calling touchAccountContract")
    class TouchAccountContract {

        private static String touchAccountContract = "TouchAccountContract";

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(uploadInitCode(touchAccountContract), contractCreate(touchAccountContract));
        }

        @Nested
        @DisplayName("fails")
        class CallFails {

            @HapiTest
            @DisplayName("when transferring value to long zero address 00000000000000000000000000000000001117d0 ")
            public Stream<DynamicTest> lazyCreateToLongZeroFails() {
                final Function<HapiSpec, String> longZeroAddress = (spec) -> toAddressString("1117d0");
                return callContractWithValue(longZeroAddress, CONTRACT_REVERT_EXECUTED);
            }

            @HapiTest
            @DisplayName("when transferring value to long zero burn address 000000000000000000000000000000000000dEaD ")
            public Stream<DynamicTest> lazyCreateToLongZeroBurnAddressFails() {
                final Function<HapiSpec, String> longZeroBurnAddress = (spec) -> toAddressString("dEaD");
                return callContractWithValue(longZeroBurnAddress, CONTRACT_REVERT_EXECUTED);
            }

            @HapiTest
            @DisplayName("when transferring value to all zero address 0000000000000000000000000000000000000000 ")
            public Stream<DynamicTest> lazyCreateToAllZeroFails() {
                final var ALL_ZERO_ADDRESS = "0000000000000000000000000000000000000000";
                return callContractWithValue(ALL_ZERO_ADDRESS, INVALID_CONTRACT_ID);
            }
        }

        @Nested
        @DisplayName("succeeds")
        class CallSucceeds {

            @HapiTest
            @DisplayName("when transferring value to evm address 0000000100000000000000020000000000000003")
            public Stream<DynamicTest> lazyCreateToEvmAddressSucceeds() {
                final var EVM_ADDRESS = "0000000100000000000000020000000000000003";
                return callContractWithValue(EVM_ADDRESS, ResponseCodeEnum.SUCCESS);
            }

            @HapiTest
            @DisplayName("when transferring value to realistic evm address 388C818CA8B9251b393131C08a736A67ccB19297")
            public Stream<DynamicTest> lazyCreateToRealisticEvmAddressSucceeds() {
                final var REALISTIC_EVM_ADDRESS = "388C818CA8B9251b393131C08a736A67ccB19297";
                return callContractWithValue(REALISTIC_EVM_ADDRESS, ResponseCodeEnum.SUCCESS);
            }
        }

        private static Stream<DynamicTest> callContractWithValue(
                final String address, final ResponseCodeEnum expectedStatus) {
            return hapiTest(atomicBatch(contractCall(touchAccountContract, "touchAccount", asHeadlongAddress(address))
                            .gas(100_000L)
                            .sending(ONE_HBAR)
                            .hasKnownStatus(expectedStatus)
                            .batchKey(BATCH_OPERATOR))
                    .payingWith(BATCH_OPERATOR)
                    .hasKnownStatus(expectedStatus.equals(SUCCESS) ? SUCCESS : INNER_TRANSACTION_FAILED));
        }

        private static Stream<DynamicTest> callContractWithValue(
                Function<HapiSpec, String> address, final ResponseCodeEnum expectedStatus) {
            return hapiTest(withOpContext((spec, log) -> {
                final var addressValue = address.apply(spec);
                final var callOp = atomicBatch(
                                contractCall(touchAccountContract, "touchAccount", asHeadlongAddress(addressValue))
                                        .gas(100_000L)
                                        .sending(ONE_HBAR)
                                        .hasKnownStatus(expectedStatus)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(expectedStatus.equals(SUCCESS) ? SUCCESS : INNER_TRANSACTION_FAILED);
                allRunFor(spec, callOp);
            }));
        }
    }

    @Nested
    @DisplayName("empty contract")
    class EmptyContract {

        private static String emptyContract = "EmptyContract";

        @HapiTest
        @DisplayName("should fail to deploy")
        public Stream<DynamicTest> canCallBalanceOperationNonExtantContract() {
            return hapiTest(
                    uploadInitCode(emptyContract),
                    atomicBatch(contractCreate(emptyContract)
                                    .inlineInitCode(ByteString.copyFrom(new byte[0]))
                                    .hasKnownStatus(CONTRACT_BYTECODE_EMPTY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }
    }
}
