// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
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
                jumboEthCall(jumboPayload, RELAYER)
                        // gRPC request terminated immediately
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(2)
    @DisplayName("Update the config")
    public Stream<DynamicTest> updateTheConfig() {
        return hapiTest(
                // The feature flag is only used once at startup (when building gRPC ServiceDefinitions),
                // so we can't toggle it via overriding(). Instead, we need to upgrade to the config version.
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "jumboTransactions.isEnabled", "true",
                                "hedera.transaction.maxMemoUtf8Bytes", "10000"),
                        noOp()));
    }

    @HapiTest
    @Order(3)
    @DisplayName("Jumbo transaction should pass")
    public Stream<DynamicTest> jumboTransactionShouldPass() {
        final var jumboPayload = new byte[10 * 1024];
        final var halfJumboPayload = new byte[5 * 1024];
        final var thirdJumboPayload = new byte[3 * 1024];
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
                jumboEthCall(tooBigPayload, RELAYER)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send jumbo payload to jumbo endpoint and assert the used gas
                jumboEthCall(jumboPayload, RELAYER)
                        .gasLimit(800000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(640000, gasUsed)),
                jumboEthCall(halfJumboPayload, RELAYER)
                        .gasLimit(500000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(400000, gasUsed)),
                jumboEthCall(thirdJumboPayload, RELAYER)
                        .gasLimit(300000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(240000, gasUsed)));
    }

    @Order(3)
    @DisplayName("Jumbo transaction gets bytes throttled at ingest")
    @LeakyHapiTest(overrides = {"jumboTransactions.isEnabled", "jumboTransactions.maxBytesPerSec"})
    public Stream<DynamicTest> jumboTransactionGetsThrottledAtIngest() {
        final var payloadSize = 127 * 1024;
        final var bytesPerSec = 130 * 1024;
        final var payload = new byte[payloadSize];
        return hapiTest(
                overridingTwo(
                        "jumboTransactions.isEnabled",
                        "true",
                        "jumboTransactions.maxBytesPerSec",
                        String.valueOf(bytesPerSec)),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                jumboEthCall(payload, RELAYER).noLogging(),
                sleepFor(1_000),
                jumboEthCall(payload, RELAYER).noLogging().deferStatusResolution(),
                jumboEthCall(payload, RELAYER).noLogging().hasPrecheck(BUSY));
    }

    @Order(4)
    @DisplayName("Privileged account is exempt from bytes throttles")
    @LeakyHapiTest(overrides = {"jumboTransactions.isEnabled", "jumboTransactions.maxBytesPerSec"})
    public Stream<DynamicTest> privilegedAccountIsExemptFromThrottles() {
        final var payloadSize = 127 * 1024;
        final var bytesPerSec = 60 * 1024;
        final var payload = new byte[payloadSize];
        final var initialNonce = new AtomicLong(0);
        return hapiTest(
                overridingTwo(
                        "jumboTransactions.isEnabled",
                        "true",
                        "jumboTransactions.maxBytesPerSec",
                        String.valueOf(bytesPerSec)),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        getAccountInfo(DEFAULT_PAYER).exposingEthereumNonceTo(initialNonce::set),
                        ethereumCall(CONTRACT, FUNCTION, payload)
                                .nonce(initialNonce.get())
                                .markAsJumboTxn()
                                .gasLimit(1_000_000L)
                                .noLogging())));
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

    @HapiTest
    @Order(6)
    @DisplayName("Ethereum Call jumbo transaction with ethereum data overflow should fail")
    public Stream<DynamicTest> ethereumCallJumboTxnWithEthereumDataOverflow() {
        final var size = 131072 + 1; // Exceeding the limit
        final var payload = new byte[size];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                ethereumCall(CONTRACT, FUNCTION, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .gasLimit(1_000_000L)
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @HapiTest
    @Order(7)
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

    @HapiTest
    @Order(8)
    @DisplayName("Non-jumbo transaction bigger then 6kb should fail")
    public Stream<DynamicTest> nonJumboTransactionBiggerThen6kb() {
        return hapiTest(
                cryptoCreate("receiver"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "receiver", ONE_HUNDRED_HBARS))
                        .memo(StringUtils.repeat("a", 6145))
                        .hasKnownStatus(TRANSACTION_OVERSIZE)
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(9)
    @DisplayName("Three jumbo transactions one after the other")
    public Stream<DynamicTest> treeJumboTransactionOneAfterTheOther() {
        final var payload = new byte[127 * 1024];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                ethereumCall(CONTRACT, FUNCTION, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L),
                sleepFor(1000),
                ethereumCall(CONTRACT, FUNCTION, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L),
                sleepFor(1000),
                ethereumCall(CONTRACT, FUNCTION, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L));
    }

    @HapiTest
    @Order(10)
    @DisplayName("Mix of jumbo and non-jumbo transactions")
    public Stream<DynamicTest> mixOfJumboAndNonJumboTransactions() {
        final var payload = new byte[50 * 1024];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                ethereumCall(CONTRACT, FUNCTION, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L),
                cryptoCreate("receiver"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "receiver", ONE_HUNDRED_HBARS)));
    }

    private static HapiEthereumCall jumboEthCall(byte[] payload, String payer) {
        return ethereumCall(CONTRACT, FUNCTION, payload)
                .markAsJumboTxn()
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(payer)
                .gasLimit(1_000_000L);
    }
}
