// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hips.batch;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.anyResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withLongZeroAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.crypto.AutoAccountUpdateSuite.ALIAS;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of CreateWithAliasDisabledTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(SMART_CONTRACT)
@DisplayName("hip632ViewFunctions")
@HapiTestLifecycle
class AtomicAliasTest {

    @Contract(contract = "HRC632Contract", creationGas = 2_000_000L)
    static SpecContract hrc632Contract;

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount alice;

    com.esaulpaugh.headlong.abi.Address nonExtantAccount =
            com.esaulpaugh.headlong.abi.Address.wrap("0x0000000000000000000000000000000000000000");
    com.esaulpaugh.headlong.abi.Address nonExtantAlias =
            com.esaulpaugh.headlong.abi.Address.wrap("0x0123456789012345678901234567890123456789");

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false",
                "cryptoCreateWithAlias.enabled",
                "false"));
        testLifecycle.doAdhoc(
                newKeyNamed(ALIAS).shape(SECP_256K1_SHAPE),
                createHollow(1, i -> ALIAS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @Nested
    @DisplayName("isValidAlias")
    class IsValidAliasTest {

        private static final String IS_VALID_ALIAS_CALL = "isValidAliasCall";

        @HapiTest
        @DisplayName("returns true for a regular account")
        public Stream<DynamicTest> isValidAliasRegularAccount() {
            return hapiTest(hrc632Contract
                    .call(IS_VALID_ALIAS_CALL, alice)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasResults(
                            anyResult(),
                            resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, IS_VALID_ALIAS_CALL, hrc632Contract.name()),
                                            isLiteralResult(new Object[] {Boolean.TRUE})))));
        }

        @HapiTest
        @DisplayName("returns true for a account with valid alias")
        public Stream<DynamicTest> isValidAliasAliasAccount() {
            return hapiTest(withAddressOfKey(ALIAS, aliasAddress -> hrc632Contract
                    .call(IS_VALID_ALIAS_CALL, aliasAddress)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasResults(
                            anyResult(),
                            resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, IS_VALID_ALIAS_CALL, hrc632Contract.name()),
                                            isLiteralResult(new Object[] {Boolean.TRUE}))))));
        }

        @HapiTest
        @DisplayName("returns false for a non extant account")
        public Stream<DynamicTest> isValidAliasNonExtantAccount() {
            return hapiTest(hrc632Contract
                    .call(IS_VALID_ALIAS_CALL, nonExtantAccount)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasResults(
                            anyResult(),
                            resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, IS_VALID_ALIAS_CALL, hrc632Contract.name()),
                                            isLiteralResult(new Object[] {Boolean.FALSE})))));
        }

        @HapiTest
        @DisplayName("returns false for a non extant alias")
        public Stream<DynamicTest> isValidAliasNonExtantAlias() {
            return hapiTest(hrc632Contract
                    .call(IS_VALID_ALIAS_CALL, nonExtantAlias)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasResults(
                            anyResult(),
                            resultWith()
                                    .resultThruAbi(
                                            getABIFor(FUNCTION, IS_VALID_ALIAS_CALL, hrc632Contract.name()),
                                            isLiteralResult(new Object[] {Boolean.FALSE})))));
        }
    }

    @Nested
    @DisplayName("getEvmAddressAlias")
    class GetEvmAddressAliasTest {

        private static final String GET_EVM_ADDRESS_ALIAS_CALL = "getEvmAddressAliasCall";

        @HapiTest
        @DisplayName("succeeds for account with valid alias")
        public Stream<DynamicTest> evmAddressAliasGivenGoodAccount() {
            return hapiTest(withLongZeroAddress(ALIAS, aliasAddress -> hrc632Contract
                    .call(GET_EVM_ADDRESS_ALIAS_CALL, aliasAddress)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)));
        }

        @HapiTest
        @DisplayName("reverts when given non extant evm address")
        public Stream<DynamicTest> evmAddressAliasGivenNonExtantAlias() {
            return hapiTest(hrc632Contract
                    .call(GET_EVM_ADDRESS_ALIAS_CALL, nonExtantAlias)
                    .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("reverts when given non extant long zero address")
        public Stream<DynamicTest> evmAddressAliasGivenNonExtantLongZero() {
            return hapiTest(hrc632Contract
                    .call(GET_EVM_ADDRESS_ALIAS_CALL, nonExtantAccount)
                    .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }
    }

    @Nested
    @DisplayName("getHederaAccountNumAlias")
    class GetHederaAccountNumAliasTest {

        private static final String GET_HEDERA_ACCOUNT_NUM_ALIAS_CALL = "getHederaAccountNumAliasCall";

        @HapiTest
        @DisplayName("succeeds for account with valid alias")
        public Stream<DynamicTest> hederaAccountNumAliasGivenGoodAccount() {
            return hapiTest(withAddressOfKey(ALIAS, aliasAddress -> hrc632Contract
                    .call(GET_HEDERA_ACCOUNT_NUM_ALIAS_CALL, aliasAddress)
                    .wrappedInBatchOperation(BATCH_OPERATOR)
                    .gas(1_000_000L)));
        }

        @HapiTest
        @DisplayName("reverts when given non extant evm address")
        public Stream<DynamicTest> hederaAccountNumAliasGivenNonExtantAlias() {
            return hapiTest(hrc632Contract
                    .call(GET_HEDERA_ACCOUNT_NUM_ALIAS_CALL, nonExtantAlias)
                    .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }

        @HapiTest
        @DisplayName("reverts when given non extant long zero address")
        public Stream<DynamicTest> hederaAccountNumAliasGivenNonExtantLongZero() {
            return hapiTest(hrc632Contract
                    .call(GET_HEDERA_ACCOUNT_NUM_ALIAS_CALL, nonExtantAccount)
                    .wrappedInBatchOperation(BATCH_OPERATOR, OK, INNER_TRANSACTION_FAILED)
                    .gas(1_000_000L)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)));
        }
    }
}
