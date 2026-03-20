// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
@HapiTestLifecycle
public class JumboTransactionsEnabledSerialTest {

    private static final String PAYER = "payer";
    private static final String RECEIVER = "receiver";
    private static final String CONTRACT_CALLDATA_SIZE = "CalldataSize";
    private static final String FUNCTION = "callme";
    private static final String SERIAL_RELAYER = "jumboSerialRelayer";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(SERIAL_RELAYER).balance(ONE_MILLION_HBARS),
                uploadInitCode(CONTRACT_CALLDATA_SIZE),
                contractCreate(CONTRACT_CALLDATA_SIZE));

        testLifecycle.overrideInClass(Map.of(
                "jumboTransactions.maxBytesPerSec",
                "99999999999", // to avoid throttling
                "contracts.throttle.throttleByGas",
                "false", // to avoid gas throttling
                "hedera.transaction.maxMemoUtf8Bytes",
                "10000")); // to avoid memo size limit
    }

    private static HapiEthereumCall jumboEthCall(final byte[] payload) {
        return ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, (Object) payload)
                .markAsJumboTxn()
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(SERIAL_RELAYER)
                .gasLimit(1_000_000L);
    }

    @DisplayName("Jumbo transaction gets bytes throttled at ingest")
    @LeakyHapiTest(overrides = {"jumboTransactions.maxBytesPerSec"})
    public Stream<DynamicTest> jumboTransactionGetsThrottledAtIngest() {
        final var payloadSize = 127 * 1024;
        final var bytesPerSec = 130 * 1024;
        final var payload = new byte[payloadSize];
        return hapiTest(
                overriding("jumboTransactions.maxBytesPerSec", String.valueOf(bytesPerSec)),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                jumboEthCall(payload).markAsJumboTxn().fee(ONE_MILLION_HBARS).noLogging(),
                // Wait for the bytes throttle bucked to be emptied
                sleepFor(1_000),
                jumboEthCall(payload)
                        .markAsJumboTxn()
                        .fee(ONE_MILLION_HBARS)
                        .noLogging()
                        .deferStatusResolution(),
                jumboEthCall(payload)
                        .markAsJumboTxn()
                        .fee(ONE_MILLION_HBARS)
                        .noLogging()
                        .hasPrecheck(BUSY));
    }

    @HapiTest
    @DisplayName("Privileged account is exempt from bytes throttles")
    @LeakyHapiTest(overrides = {"jumboTransactions.maxBytesPerSec"})
    public Stream<DynamicTest> privilegedAccountIsExemptFromThrottles() {
        final var payloadSize = 127 * 1024;
        final var bytesPerSec = 60 * 1024;
        final var payload = new byte[payloadSize];
        final var initialNonce = new AtomicLong(0);
        return hapiTest(
                overriding("jumboTransactions.maxBytesPerSec", String.valueOf(bytesPerSec)),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        getAccountInfo(DEFAULT_PAYER).exposingEthereumNonceTo(initialNonce::set),
                        ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                                .nonce(initialNonce.get())
                                .markAsJumboTxn()
                                .gasLimit(1_000_000L)
                                .noLogging())));
    }

    @HapiTest
    @DisplayName("Non-jumbo transaction bigger than 6kb should fail")
    // JUMBO_N_07
    public Stream<DynamicTest> nonJumboTransactionBiggerThan6kb() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER),
                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, ONE_HUNDRED_HBARS))
                        .memo(StringUtils.repeat("a", 6145))
                        .payingWith(PAYER)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        .orUnavailableStatus());
    }
}
