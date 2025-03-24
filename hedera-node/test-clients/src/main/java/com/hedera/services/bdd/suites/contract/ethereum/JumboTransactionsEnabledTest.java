// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 123)
@HapiTestLifecycle
@OrderedInIsolation
public class JumboTransactionsEnabledTest implements LifecycleTest {

    private static String CONTRACT = "CalldataSize";
    private static String FUNCTION = "callme";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT));
    }

    @HapiTest
    @Order(1)
    @DisplayName("Jumbo transaction should fail if feature flag is disabled")
    public Stream<DynamicTest> jumboTransactionDisabled() {

        final var jumboPayload = new byte[10 * 1024];
        return hapiTest(ethereumCall(CONTRACT, FUNCTION, jumboPayload)
                .markAsJumboTxn()
                .type(EthTxData.EthTransactionType.EIP1559)
                .gasLimit(1_000_000L)
                // gRPC request terminated immediately
                .orUnavailableStatus());
    }

    @HapiTest
    @Order(2)
    @DisplayName("Jumbo transaction should pass")
    public Stream<DynamicTest> jumboTransactionShouldPass() {
        final var jumboPayload = new byte[10 * 1024];
        final var tooBigPayload = new byte[130 * 1024 + 1];
        return hapiTest(
                // The feature flag is only used once at startup (when building gRPC ServiceDefinitions),
                // so we can't toggle it via overriding(). Instead, we need to upgrade to the config version.
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),

                // send jumbo payload to non jumbo endpoint
                contractCall(CONTRACT, FUNCTION, jumboPayload)
                        .gas(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send too big payload to jumbo endpoint
                ethereumCall(CONTRACT, FUNCTION, tooBigPayload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send jumbo payload to jumbo endpoint
                ethereumCall(CONTRACT, FUNCTION, jumboPayload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        // Ethereum call should pass
                        // (TRANSACTION_OVERSIZE will be returned on ingest until we merge the ingest checks)
                        .hasPrecheckFrom(OK, TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName("Ethereum Call jumbo transaction with ethereum data overflow should fail")
    public Stream<DynamicTest> ethereumCallJumboTxnWithEthereumDataOverflow() {
        final var contract = "CalldataSize";
        final var function = "callme";
        final var size = 131072 + 1; // Exceeding the limit
        final var payload = new byte[size];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                uploadInitCode(contract),
                contractCreate(contract),
                ethereumCall(contract, function, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .gasLimit(1_000_000L)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @DisplayName("Allows Ethereum jumbo contract create jumbo above max transaction size of 6kb")
    public Stream<DynamicTest> ethereumContractCreateJumboTxnMoreThen6Kb() {
        final var contract = "TokenCreateContract";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                uploadInitCode(contract),
                ethereumContractCreate(contract)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(1_000_000L)
                        .via("payTxn"));
    }
}
