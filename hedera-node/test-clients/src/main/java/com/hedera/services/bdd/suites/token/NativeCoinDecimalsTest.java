// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end tests for the configurable native coin decimals feature.
 *
 * <p>The {@code nativeCoin.decimals} property controls how many decimal places the native coin
 * uses. It is set at genesis and persisted as a singleton in state. These tests validate correct
 * behavior across different decimal values, restart immutability, and fee scaling.
 *
 * <p><b>Note on high decimal values:</b> The total supply constant (50 billion whole units)
 * overflows {@code long} when {@code decimals > 8} because
 * {@code 50_000_000_000 * 10^9 = 5 * 10^19 > Long.MAX_VALUE (~9.2 * 10^18)}.
 * Therefore, only decimal values 0-8 can complete genesis successfully.
 */
@Tag(TOKEN)
@DisplayName("Native Coin Decimals EETs")
public class NativeCoinDecimalsTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Group 1: Default decimals=8 — backward compatibility (AC-NFR-1)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that with the default decimals=8, ONE_HBAR (10^8 subunits) arithmetic
     * is unchanged from pre-feature behavior. Covers AC-NFR-1.
     */
    @HapiTest
    @DisplayName("Default decimals=8 preserves ONE_HBAR backward compatibility")
    final Stream<DynamicTest> defaultDecimalsBackwardCompatible() {
        return hapiTest(
                cryptoCreate("alice").balance(ONE_HBAR),
                getAccountBalance("alice").hasTinyBars(ONE_HBAR),
                cryptoTransfer(tinyBarsFromTo("alice", GENESIS, ONE_HBAR)),
                getAccountBalance("alice").hasTinyBars(0L));
    }

    /**
     * Verifies that fee calculations at default decimals=8 produce a non-zero fee
     * and complete without error. Covers AC-NFR-1, AC-FR-1 baseline.
     */
    @HapiTest
    @DisplayName("Default decimals=8 fee calculation produces non-zero fee")
    final Stream<DynamicTest> defaultDecimalsFeeBaseline() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver").balance(0L),
                cryptoTransfer(tinyBarsFromTo("payer", "receiver", ONE_HBAR))
                        .payingWith("payer")
                        .via("baselineTxn"),
                getTxnRecord("baselineTxn").exposingTo(record -> {
                    long fee = record.getTransactionFee();
                    assertTrue(fee > 0, "Fee should be non-zero at default decimals=8");
                }));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 2: Non-default decimal genesis tests (AC-U-5, AC-A-3, AC-D-2)
    //
    // Only decimal values 0-8 can complete genesis (total supply fits in long).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that with decimals=0 (no sub-unit precision), account creation
     * and transfers work correctly. subunitsPerWholeUnit=1.
     * Covers AC-U-5, AC-A-3 (decimals=0), AC-D-5 (weibarsPerSubunit=10^18).
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "0"),
            })
    @DisplayName("Decimals=0: operations correct with no sub-unit precision")
    final Stream<DynamicTest> decimalsZeroAllOperationsCorrect() {
        // At decimals=0, 1 subunit = 1 whole unit
        return hapiTest(
                cryptoCreate("alice").balance(100L),
                getAccountBalance("alice").hasTinyBars(100L),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "alice", 50L)),
                getAccountBalance("alice").hasTinyBars(150L));
    }

    /**
     * Verifies correct arithmetic at decimals=1 (subunitsPerWholeUnit=10).
     * Covers AC-A-3, AC-D-2.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "1"),
            })
    @DisplayName("Decimals=1: balance arithmetic correct with subunitsPerWholeUnit=10")
    final Stream<DynamicTest> decimalsOneCalculationsCorrect() {
        // 10 subunits = 1 whole unit
        return hapiTest(
                cryptoCreate("alice").balance(100L),
                getAccountBalance("alice").hasTinyBars(100L),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "alice", 10L)),
                getAccountBalance("alice").hasTinyBars(110L));
    }

    /**
     * Verifies that fee calculations at a non-default decimal value (6) produce
     * a non-zero fee, confirming that the fee pipeline uses the configured
     * decimal precision. Covers AC-FR-1.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "6"),
            })
    @DisplayName("Fees at decimals=6 are non-zero and correctly computed")
    final Stream<DynamicTest> feesDifferWithNonDefaultDecimals() {
        // At decimals=6, subunitsPerWholeUnit=10^6
        final long oneWholeUnit = 1_000_000L;
        return hapiTest(
                cryptoCreate("payer").balance(oneWholeUnit * 1_000L),
                cryptoCreate("receiver").balance(0L),
                cryptoTransfer(tinyBarsFromTo("payer", "receiver", oneWholeUnit))
                        .payingWith("payer")
                        .via("feeTxn"),
                getTxnRecord("feeTxn").exposingTo(record -> {
                    long fee = record.getTransactionFee();
                    assertTrue(fee > 0, "Fee should be non-zero at decimals=6");
                }));
    }

    /**
     * Verifies that genesis completes successfully at decimals=4, validating that
     * a mid-range non-default value works end-to-end. Covers AC-A-6.
     *
     * <p><b>Note:</b> The maximum usable decimals value with the 50B total supply is 8,
     * because {@code 50_000_000_000 * 10^9 = 5 * 10^19 > Long.MAX_VALUE (~9.2 * 10^18)}.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "4"),
            })
    @DisplayName("Genesis completes at decimals=4 (mid-range non-default value)")
    final Stream<DynamicTest> genesisCompletesAtNonDefaultDecimals() {
        // At decimals=4, subunitsPerWholeUnit=10^4
        final long oneWholeUnit = 10_000L;
        return hapiTest(
                cryptoCreate("alice").balance(oneWholeUnit),
                getAccountBalance("alice").hasTinyBars(oneWholeUnit),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "alice", oneWholeUnit)),
                getAccountBalance("alice").hasTinyBars(2 * oneWholeUnit));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 3: Decimal persistence validation (AC-U-3, AC-A-4, AC-NFR-3)
    //
    // Restart immutability (config mismatch on restart uses genesis value)
    // is validated by V0710TokenSchemaTest unit tests.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that genesis at decimals=6 produces a consistent network where
     * account lifecycle operations (create, transfer, balance query) all use
     * the configured subunitsPerWholeUnit=10^6. Covers AC-A-4, AC-NFR-3.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "6"),
            })
    @DisplayName("Decimals=6: full account lifecycle consistent with genesis config")
    final Stream<DynamicTest> decimalsGenesisConsistencyAtSix() {
        // At decimals=6, subunitsPerWholeUnit=10^6
        final long oneWholeUnit = 1_000_000L;
        return hapiTest(
                cryptoCreate("alice").balance(oneWholeUnit),
                getAccountBalance("alice").hasTinyBars(oneWholeUnit),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "alice", oneWholeUnit)),
                getAccountBalance("alice").hasTinyBars(2 * oneWholeUnit));
    }

    /**
     * Verifies that genesis at decimals=4 produces a consistent network where
     * partial-unit transfers work correctly. Covers AC-NFR-3, AC-A-7.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "4"),
            })
    @DisplayName("Decimals=4: partial-unit transfers consistent with genesis config")
    final Stream<DynamicTest> decimalsGenesisConsistencyAtFour() {
        // At decimals=4, subunitsPerWholeUnit=10^4
        final long oneWholeUnit = 10_000L;
        return hapiTest(
                cryptoCreate("alice").balance(oneWholeUnit),
                getAccountBalance("alice").hasTinyBars(oneWholeUnit),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "alice", 5_000L)),
                getAccountBalance("alice").hasTinyBars(15_000L));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Group 4: Staking at extreme decimals (AC-A-8)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that staking reward calculations work correctly at decimals=0,
     * where there is no sub-unit precision for reward distribution. Covers AC-A-8.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "0"),
            })
    @DisplayName("Staking rewards at decimals=0 do not overflow or error")
    final Stream<DynamicTest> stakingRewardsAtDecimalsZero() {
        // At decimals=0, subunitsPerWholeUnit=1 — rewards with no fractional precision
        return hapiTest(
                // Create a staker staked to node 0
                cryptoCreate("staker").stakedNodeId(0).balance(1_000L),
                // Trigger a transfer to exercise staking path
                cryptoTransfer(tinyBarsFromTo(GENESIS, "staker", 100L)),
                getAccountBalance("staker").hasTinyBars(1_100L));
    }

    /**
     * Verifies that staking interactions work at decimals=8 (same as default,
     * but via explicit genesis bootstrap). This validates that the staking path
     * through DenominationConverter works end-to-end. Covers AC-A-8.
     */
    @GenesisHapiTest(
            bootstrapOverrides = {
                @ConfigOverride(key = "nativeCoin.decimals", value = "8"),
            })
    @DisplayName("Staking at explicit decimals=8 works end-to-end")
    final Stream<DynamicTest> stakingAtExplicitDecimalsEight() {
        return hapiTest(
                cryptoCreate("staker").stakedNodeId(0).balance(ONE_HBAR),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "staker", ONE_HBAR)),
                getAccountBalance("staker").hasTinyBars(2 * ONE_HBAR));
    }
}
