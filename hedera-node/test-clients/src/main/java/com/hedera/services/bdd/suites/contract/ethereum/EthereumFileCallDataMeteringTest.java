// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that {@code jumboTransactions.chargeHydratedCallDataSize} controls how a file-referenced
 * {@code EthereumTransaction} is accounted against the jumbo bytes throttle: with the flag enabled it
 * is charged by the decoded size of its hydrated callData, exactly as an equivalent inline payload is;
 * with the flag disabled it is charged only its on-wire size.
 */
@Tag(SMART_CONTRACT)
public class EthereumFileCallDataMeteringTest {
    private static final String CONTRACT = "CalldataSize";
    private static final String FUNCTION = "callme";
    private static final String CHARGE_HYDRATED = "jumboTransactions.chargeHydratedCallDataSize";
    private static final String MAX_BYTES_PER_SEC = "jumboTransactions.maxBytesPerSec";
    private static final String THROTTLE_BY_GAS = "contracts.throttle.throttleByGas";
    // 64 KiB/sec bucket: a 127 KiB hydrated payload (~121 KiB of excess above the 6 KiB floor) can never
    // fit once its hydrated size is counted, so it is rejected on the first attempt.
    private static final String SIXTY_FOUR_KIB = String.valueOf(64 * 1024);

    @LeakyHapiTest(overrides = {CHARGE_HYDRATED, MAX_BYTES_PER_SEC, THROTTLE_BY_GAS})
    @DisplayName("Metering ON: a file-referenced eth txn is bytes-throttled by its hydrated callData size")
    final Stream<DynamicTest> fileReferencedTxnIsThrottledByHydratedSizeWhenEnabled() {
        final var payload = new byte[127 * 1024];
        return hapiTest(
                overriding(CHARGE_HYDRATED, "true"),
                overriding(MAX_BYTES_PER_SEC, SIXTY_FOUR_KIB),
                overriding(THROTTLE_BY_GAS, "false"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                ethereumCall(CONTRACT, FUNCTION, (Object) payload)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .createCallDataFile() // file-referenced: tiny on the wire, 127 KiB once hydrated
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        // small gas limit keeps this a pure bytes-throttle assertion (ingest sees empty inline
                        // callData, so the gas floor is trivially met and gas throttling is not engaged)
                        .gasLimit(100_000L)
                        .hasPrecheck(BUSY));
    }

    @LeakyHapiTest(overrides = {CHARGE_HYDRATED, MAX_BYTES_PER_SEC, THROTTLE_BY_GAS})
    @DisplayName("Metering OFF: the same file-referenced eth txn is charged only its on-wire size")
    final Stream<DynamicTest> fileReferencedTxnNotMeteredWhenDisabled() {
        final var payload = new byte[127 * 1024];
        return hapiTest(
                // with hydrated-size metering disabled, the file-referenced callData is charged only its
                // on-wire size
                overriding(CHARGE_HYDRATED, "false"),
                overriding(MAX_BYTES_PER_SEC, SIXTY_FOUR_KIB),
                overriding(THROTTLE_BY_GAS, "false"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                // Same 127 KiB payload under the same 64 KiB/sec bucket, but metered at its ~0 on-wire excess,
                // so it is admitted and executes normally.
                ethereumCall(CONTRACT, FUNCTION, (Object) payload)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .createCallDataFile()
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(9_000_000L)
                        .hasKnownStatus(SUCCESS));
    }
}
