// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.WRONG_NONCE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class JumboTxnConsensusThrottleTest {
    private static final Logger LOG = LogManager.getLogger(JumboTxnConsensusThrottleTest.class);

    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(50);

    private static final int EXPECTED_MAX_JUMBO_TXNS_PER_SEC = 24;

    @AfterAll
    public static void afterAll() {
        HapiEthereumCall.successfulJumboTransactions = null;
    }

    @LeakyHapiTest(overrides = {"jumboTransactions.isEnabled", "jumboTransactions.maxBytesPerSec"})
    final Stream<DynamicTest> jumboTransactionsAreLimitedByConsensusThrottle() {
        HapiEthereumCall.successfulJumboTransactions = new AtomicInteger(0);
        final var maxBytesPerSec = EXPECTED_MAX_JUMBO_TXNS_PER_SEC * 126 * 1024;
        return hapiTest(
                overridingTwo(
                        "jumboTransactions.isEnabled",
                        "true",
                        "jumboTransactions.maxBytesPerSec",
                        String.valueOf(maxBytesPerSec)),
                runWithProvider(jumboTxnFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get),
                verify(() -> {
                    // Assert that the max number of successful jumbo transactions is less than the expected and add a
                    // buffer of 10 to reduce flakiness
                    LOG.info("Jumbo success count: {}", HapiEthereumCall.successfulJumboTransactions);
                    assertTrue(
                            HapiEthereumCall.successfulJumboTransactions.get()
                                    <= (EXPECTED_MAX_JUMBO_TXNS_PER_SEC * duration.get()) + 10,
                            String.format(
                                    "Expected Jumbo success be less than %d, but was %d",
                                    EXPECTED_MAX_JUMBO_TXNS_PER_SEC * duration.get(),
                                    HapiEthereumCall.successfulJumboTransactions.get()));
                }));
    }

    private Function<HapiSpec, OpProvider> jumboTxnFactory() {
        final AtomicInteger nextNode = new AtomicInteger(0);
        return spec -> new OpProvider() {
            final String contract = "CalldataSize";
            final String function = "callme";

            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                        cryptoTransfer(
                                tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                        uploadInitCode(contract),
                        contractCreate(contract));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                final var size = 126 * 1024;
                final var payload = new byte[size];
                final var op1 = sourcingContextual(spec -> ethereumCall(contract, function, payload)
                        .markAsJumboTxn()
                        .noLogging()
                        .setNode(asEntityString(3 + nextNode.accumulateAndGet(1, (a, b) -> a == 3 ? 0 : a + b)))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .gasLimit(1_000_000L)
                        .hasPrecheckFrom(OK, BUSY)
                        .hasKnownStatusFrom(SUCCESS, WRONG_NONCE, THROTTLED_AT_CONSENSUS)
                        .deferStatusResolution());

                return Optional.of(op1);
            }
        };
    }
}
