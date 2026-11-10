// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.RECEIVER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>NFT</b> block-stream amplification vectors (catalog entries: NFT transfer relinks, NFT
 * serial approvals, and other NFT operations).
 *
 * <p>NFT ownership is a house-style doubly-linked list on the account, and each NFT carries its owner/spender pointers.
 * Moving, approving or minting NFTs touches those entries, each of which is re-emitted as a state change. These are the
 * cases {@code blockStream.stateChangeDeltaMinSaving=16} most helps (the relink entries go partial), but a flood of them
 * still fills a block; this suite shows that under mainnet throttles the budget is still crossed and further
 * transactions are rejected {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>Three vectors are exercised: transferring distinct serials (owner/receiver relinks), per-serial approvals (spender
 * pointer set on each NFT), and minting batches (new NFT entries). The token is created and pre-minted with
 * throttle-exempt (GENESIS-paid) setup; the attacker's operations are the only budget-eligible transactions.
 *
 * <p><b>High-volume note:</b> {@code TokenMint} is a HIP-1313 high-volume functionality (HVTokenMintThrottles); the mint
 * proof below marks mints high-volume. {@code CryptoTransfer} and {@code CryptoApproveAllowance} high-volume routing is
 * covered in the allowance suite, so the transfer/approval proofs here use the normal lanes.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class NftAmplificationTest {
    private static final int BUDGET_BYTES = 8_192; // 8 KiB
    private static final String NFT = "amplificationNft";
    private static final int PRE_MINTED = 20;
    private static final int ATTACK_ATTEMPTS = 16;

    private static ByteString meta(final int i) {
        final byte[] b = new byte[48];
        for (int j = 0; j < b.length; j++) {
            b[j] = (byte) ('a' + ((i + j) % 26));
        }
        return ByteString.copyFrom(b);
    }

    /** Creates the NFT with {@code ATTACKER} as treasury and supply key, then mints {@code PRE_MINTED} serials. */
    private static SpecOperation createAndPreMint() {
        return withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    tokenCreate(NFT)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(ATTACKER)
                            .treasury(ATTACKER)
                            .payingWith(GENESIS)
                            .signedBy(GENESIS, ATTACKER));
            for (int start = 0; start < PRE_MINTED; start += 10) {
                final List<ByteString> batch = new ArrayList<>();
                for (int i = start; i < Math.min(start + 10, PRE_MINTED); i++) {
                    batch.add(meta(i));
                }
                allRunFor(spec, mintToken(NFT, batch).payingWith(GENESIS).signedBy(GENESIS, ATTACKER));
            }
        });
    }

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
                            "blockStream.maxBlockExecutionSizeBytes", "8192",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> nftTransfersFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_MILLION_HBARS),
                createAndPreMint(),
                tokenAssociate(RECEIVER, NFT).payingWith(GENESIS).signedBy(GENESIS, RECEIVER),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "nftTransfer",
                        ATTACK_ATTEMPTS,
                        // Each transfer moves a distinct serial, relinking both owner lists
                        i -> cryptoTransfer(movingUnique(NFT, 1L + i).between(ATTACKER, RECEIVER))
                                .payingWith(ATTACKER)
                                .noLogging()));
    }

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
                            "blockStream.maxBlockExecutionSizeBytes", "8192",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> nftSerialApprovalsFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                createAndPreMint(),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "nftSerialApproval",
                        ATTACK_ATTEMPTS,
                        // Each approval sets the spender pointer on a distinct serial, re-emitting that NFT
                        i -> cryptoApproveAllowance()
                                .addNftAllowance(ATTACKER, NFT, RECEIVER, false, List.of(1L + i))
                                .payingWith(ATTACKER)
                                .noLogging()));
    }

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
                            "blockStream.maxBlockExecutionSizeBytes", "8192",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> nftMintsFloodBlockOnHighVolumeLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(ATTACKER)
                        .treasury(ATTACKER)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, ATTACKER),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "nftMint",
                        ATTACK_ATTEMPTS,
                        // Each mint creates ten new NFT entries; TokenMint has a dedicated high-volume lane
                        i -> mintToken(
                                        NFT,
                                        List.of(
                                                meta(10 * i),
                                                meta(10 * i + 1),
                                                meta(10 * i + 2),
                                                meta(10 * i + 3),
                                                meta(10 * i + 4),
                                                meta(10 * i + 5),
                                                meta(10 * i + 6),
                                                meta(10 * i + 7),
                                                meta(10 * i + 8),
                                                meta(10 * i + 9)))
                                .payingWith(ATTACKER)
                                .withHighVolume()
                                .noLogging()));
    }
}
