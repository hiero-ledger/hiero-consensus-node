// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 123)
@HapiTestLifecycle
@OrderedInIsolation
public class JumboTransactionsEnabledTest implements LifecycleTest {

    @HapiTest
    @DisplayName("Jumbo transaction should pass")
    public Stream<DynamicTest> jumboTransactionShouldPass() {
        final var contract = "CalldataSize";
        final var function = "callme";
        final var size = 10 * 1024;
        final var payload = new byte[size];
        return hapiTest(
                // The feature flag is only used once at startup (when building gRPC ServiceDefinitions),
                // so we can't toggle it via overriding(). Instead, we need to upgrade to the config version.
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, function, payload)
                        .gas(1_000_000L)
                        .hasPrecheckFrom(TRANSACTION_OVERSIZE)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),
                ethereumCall(contract, function, payload)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .gasLimit(1_000_000L));
    }

    @HapiTest
    @DisplayName("Ethereum Call jumbo transaction with ethereum data overflow should fail")
    public Stream<DynamicTest> ethereumCallJumboTxnWithEthereumDataOverflow() {
        final var contract = "CalldataSize";
        final var function = "callme";
        final var size = 131072 + 1; // Exceeding the limit
        final var payload = new byte[size];
        return hapiTest(
            prepareFakeUpgrade(),
            upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
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
    @DisplayName("Ethereum contract create jumbo transaction more then 6kb smart contract should pass")
    public Stream<DynamicTest> ethereumContractCreateJumboTxnMoreThen6Kb() {
        final var contract = "TokenCreateContract";
        return hapiTest(
            prepareFakeUpgrade(),
            upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
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
