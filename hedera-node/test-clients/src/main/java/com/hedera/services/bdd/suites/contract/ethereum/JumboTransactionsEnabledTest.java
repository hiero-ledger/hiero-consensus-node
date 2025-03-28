// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
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

    private static final String CONTRACT = "CalldataSize";
    private static final String FUNCTION = "callme";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT));
    }

    @HapiTest
    @Order(1)
    @DisplayName("Jumbo transaction should fail if feature flag is disabled")
    public Stream<DynamicTest> jumboTransactionDisabled() {

        final var jumboPayload = new byte[10 * 1024];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                ethereumCall(CONTRACT, FUNCTION, jumboPayload)
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
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
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),

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
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send jumbo payload to jumbo endpoint
                ethereumCall(CONTRACT, FUNCTION, jumboPayload)
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L));
    }

    @HapiTest
    @Order(5)
    @DisplayName("Jumbo transaction send to the wrong gRPC endpoint")
    public Stream<DynamicTest> sendToTheWrongGRPCEndpoint() {
        final var sixKbPayload = new byte[6 * 1024];
        final var moreThenSixKbPayload = new byte[6 * 1024 + 1];
        final var limitPayload = new byte[128 * 1024];
        final var tooBigPayload = new byte[130 * 1024];

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                ethereumCall(CONTRACT, FUNCTION, sixKbPayload)
                        .markAsJumboTxn()
                        // Override the hedera functionality to make the framework send the request to the wrong
                        // endpoint
                        .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        .orUnavailableStatus(),
                ethereumCall(CONTRACT, FUNCTION, moreThenSixKbPayload)
                        .markAsJumboTxn()
                        // Override the hedera functionality to make the framework send the request to the wrong
                        // endpoint
                        .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        .orUnavailableStatus(),
                ethereumCall(CONTRACT, FUNCTION, limitPayload)
                        .markAsJumboTxn()
                        // Override the hedera functionality to make the framework send the request to the wrong
                        // endpoint
                        .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        .orUnavailableStatus(),
                ethereumCall(CONTRACT, FUNCTION, tooBigPayload)
                        .markAsJumboTxn()
                        // Override the hedera functionality to make the framework send the request to the wrong
                        // endpoint
                        .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        .orUnavailableStatus());
    }
}
