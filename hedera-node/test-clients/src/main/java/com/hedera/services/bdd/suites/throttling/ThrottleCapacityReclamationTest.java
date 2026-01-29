// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.SUPPLY_KEY;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests that verify throttle capacity is properly reclaimed when child transactions fail or are reverted,
 * and importantly, that capacity is NOT double-reclaimed.
 *
 * <p>The key scenarios tested are:
 * <ol>
 *     <li>Child dispatch that reverts should reclaim exactly the capacity it used</li>
 *     <li>Multiple reverted child dispatches should not "stack" reclaimed capacity</li>
 * </ol>
 */
@Tag(TOKEN)
@Tag(MATS)
public class ThrottleCapacityReclamationTest {

    /**
     * Verifies that throttle capacity is not double-reclaimed when a child dispatch reverts.
     * With a 1 TPS throttle:
     * <ol>
     *     <li>Revert an inner mint (should reclaim exactly 1 capacity unit)</li>
     *     <li>Do a successful outer mint (should use the reclaimed capacity)</li>
     *     <li>Try a third mint which should be THROTTLED (proving we didn't double-reclaim)</li>
     * </ol>
     */
    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"tokens.nfts.mintThrottleScaleFactor", "contracts.throttle.throttleByGas"},
            throttles = "testSystemFiles/one-tps-nft-mint.json")
    @DisplayName("No double-reclaim: reverted mint + successful mint = third mint throttled")
    final Stream<DynamicTest> noDoubleReclaimAfterRevertedMint(
            @NonFungibleToken SpecNonFungibleToken nft,
            @Contract(contract = "ReclaimCheck", creationGas = 3_000_000) SpecContract doubleReclaimCheck) {
        return hapiTest(
                overridingTwo(
                        "tokens.nfts.mintThrottleScaleFactor", "1:1",
                        "contracts.throttle.throttleByGas", "false"),
                nft.authorizeContracts(doubleReclaimCheck).alsoAuthorizing(SUPPLY_KEY),
                // This contract call will:
                // 1. Revert an inner mint (reclaiming capacity)
                // 2. Do a successful outer mint (using reclaimed capacity)
                // 3. Try a third mint which should be throttled (proving no double-reclaim)
                doubleReclaimCheck
                        .call(
                                "testNoDoubleReclaim",
                                nft,
                                new byte[][] {{(byte) 0xAA}}, // meta1 - will be reverted
                                new byte[][] {{(byte) 0xBB}}, // meta2 - should succeed
                                new byte[][] {{(byte) 0xCC}}) // meta3 - should be throttled
                        .gas(3_000_000L));
    }

    /**
     * Verifies that multiple reverted mints don't "stack" reclaimed capacity.
     * With a 1 TPS throttle, reverting 2 mints should still only allow 1 successful mint.
     */
    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"tokens.nfts.mintThrottleScaleFactor", "contracts.throttle.throttleByGas"},
            throttles = "testSystemFiles/one-tps-nft-mint.json")
    @DisplayName("Multiple reverts don't stack: 2 reverted mints still only allow 1 successful mint")
    final Stream<DynamicTest> multipleRevertsDoNotStackCapacity(
            @NonFungibleToken SpecNonFungibleToken nft,
            @Contract(contract = "ReclaimCheck", creationGas = 3_000_000) SpecContract doubleReclaimCheck) {
        return hapiTest(
                overridingTwo(
                        "tokens.nfts.mintThrottleScaleFactor", "1:1",
                        "contracts.throttle.throttleByGas", "false"),
                nft.authorizeContracts(doubleReclaimCheck).alsoAuthorizing(SUPPLY_KEY),
                // This contract call will:
                // 1. Revert first inner mint
                // 2. Revert second inner mint
                // 3. Do ONE successful mint (should work)
                // 4. Try another mint which should be throttled (proving reverts don't stack)
                doubleReclaimCheck
                        .call(
                                "testMultipleRevertsNoExtraReclaim",
                                nft,
                                new byte[][] {{(byte) 0x11}}, // revertMeta1
                                new byte[][] {{(byte) 0x22}}, // revertMeta2
                                new byte[][] {{(byte) 0x33}}, // successMeta
                                new byte[][] {{(byte) 0x44}}) // throttledMeta
                        .gas(3_000_000L));
    }
}
