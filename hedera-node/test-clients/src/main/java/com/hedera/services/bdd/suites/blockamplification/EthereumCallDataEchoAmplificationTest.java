// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.initcodeReturning;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.payload;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>EthereumTransaction call-data echo</b> vector (bug bounty report 95044).
 *
 * <p>On a failed {@code EthereumTransaction}, the caller-supplied call data is copied into the result
 * ({@code functionParameters} / {@code call_data}), so a large call data is echoed into the block even though the call
 * reverted and the caller never pays for it as execution. The call data can be up to the jumbo limit
 * ({@code jumboTransactions.ethereumMaxCallDataSize} = 131,072 bytes); the jumbo bytes throttle caps the <em>rate</em>
 * (~130 KB/s, so ~one such transaction per second), which is why this ranks lower — but a single echo already exceeds a
 * block budget. The mitigation evaluated is to never echo the call data into the result.
 *
 * <p>This proof deploys a contract that always reverts, then sends one {@code EthereumTransaction} carrying
 * {@value #CALL_DATA_BYTES} bytes of call data to it: the call reverts, the call data is echoed into the block and
 * crosses the {@value #BUDGET_BYTES}-byte budget. A short flood of cheap follow-up transfers is then rejected
 * {@code THROTTLED_AT_CONSENSUS}, confirming the echo filled the block.
 *
 * <p><b>High-volume note:</b> {@code EthereumTransaction} is not a HIP-1313 high-volume functionality; its rate is
 * bounded by the jumbo bytes throttle, which is the natural mitigation lever for this vector.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class EthereumCallDataEchoAmplificationTest {
    private static final int BUDGET_BYTES = 65_536; // 64 KiB
    private static final int CALL_DATA_BYTES = 100_000; // ~100 KiB echoed on revert
    private static final String REVERTER = "ethEchoReverter";
    private static final int FOLLOW_ON_ATTEMPTS = 6;

    // PUSH1 0x00 ; PUSH1 0x00 ; REVERT  -> reverts immediately whatever the input
    private static final byte[] REVERT_RUNTIME = {0x60, 0x00, 0x60, 0x00, (byte) 0xfd};

    @HapiTest
    @GenesisSubprocessTest(
            networkSize = 1,
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE",
                            "blockStream.enableStateChangeDeltas", "true",
                            "blockStream.stateChangeDeltaMinSaving", "16",
                            "blockStream.maxBlockExecutionSizeBytes", "65536",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> ethereumCallDataEchoFloodsBlock() {
        final String bigCallDataHex = CommonUtils.hex(payload(CALL_DATA_BYTES));
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                doingContextual(spec -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                contractCreate(REVERTER)
                        .inlineInitCode(initcodeReturning(REVERT_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                // One reverting Ethereum call echoes ~100 KiB of call data into the block, crossing the budget
                new HapiEthereumCall(REVERTER)
                        .withExplicitParams(() -> bigCallDataHex)
                        .markAsJumboTxn()
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(0L)
                        // Relayer-covered gas; the allowance must leave the relayer balance for the transaction fee
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        // ~100 KiB of non-zero call data costs ~1.6M gas intrinsic (16 gas/byte); give ample headroom
                        .gasLimit(12_000_000L)
                        .via("ethEcho")
                        .hasKnownStatusFrom(
                                CONTRACT_REVERT_EXECUTED, INSUFFICIENT_GAS, SUCCESS, THROTTLED_AT_CONSENSUS),
                // The block is now full; cheap follow-up transfers are rejected
                floodAssertingBudgetRejection(
                        "ethEchoFollowOn",
                        FOLLOW_ON_ATTEMPTS,
                        () -> cryptoTransfer(tinyBarsFromTo(ATTACKER, FUNDING, 1L))
                                .payingWith(ATTACKER)
                                .noLogging()));
    }
}
