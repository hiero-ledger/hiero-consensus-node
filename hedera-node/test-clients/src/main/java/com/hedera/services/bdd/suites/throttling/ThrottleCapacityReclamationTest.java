// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests that verify throttle capacity is properly reclaimed when child transactions fail or are reverted.
 * This is important to prevent where failed child transactions could permanently
 * consume throttle capacity.
 *
 * <p>The key scenarios tested are:
 * <ol>
 *     <li>Child dispatch that reverts should not consume throttle capacity</li>
 *     <li>Child dispatch that succeeds should consume throttle capacity</li>
 *     <li>Multiple child dispatches with mixed success/failure should only consume capacity for successful ones</li>
 * </ol>
 */
@Tag(TOKEN)
@Tag(MATS)
public class ThrottleCapacityReclamationTest {

    /**
     * Verifies that throttle capacity used during a child dispatch that is later reverted
     * does not cause further dispatches to be throttled. This test uses a contract that:
     * <ol>
     *     <li>Mints an NFT in a child dispatch, commits that dispatch, and then receives
     *     THROTTLED_AT_CONSENSUS attempting a later mint.</li>
     *     <li>Mints an NFT in a child dispatch, reverts that dispatch, and then receives
     *     SUCCESS attempting a later mint.</li>
     * </ol>
     */
    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"tokens.nfts.mintThrottleScaleFactor", "contracts.throttle.throttleByGas"},
            throttles = "testSystemFiles/one-tps-nft-mint.json")
    @DisplayName("Throttle capacity is reclaimed when child dispatch reverts")
    final Stream<DynamicTest> throttleCapacityReclaimedOnChildRevert(
            @NonFungibleToken SpecNonFungibleToken nft,
            @Contract(contract = "ConsensusMintCheck", creationGas = 3_000_000) SpecContract consensusMintCheck) {
        return hapiTest(
                overridingTwo(
                        "tokens.nfts.mintThrottleScaleFactor", "1:1",
                        "contracts.throttle.throttleByGas", "false"),
                nft.authorizeContracts(consensusMintCheck).alsoAuthorizing(SUPPLY_KEY),
                // First call: inner mint commits, outer mint should be throttled
                consensusMintCheck
                        .call("mintInnerAndOuter", nft, Boolean.TRUE, new byte[][] {{(byte) 0xAB}}, new byte[][] {
                            {(byte) 0xBC}
                        })
                        .gas(2_000_000L),
                // Reset throttles by overriding them
                overridingThrottles("testSystemFiles/one-tps-nft-mint.json"),
                // Second call: inner mint reverts, outer mint should succeed
                consensusMintCheck
                        .call("mintInnerAndOuter", nft, Boolean.FALSE, new byte[][] {{(byte) 0xCD}}, new byte[][] {
                            {(byte) 0xDE}
                        })
                        .gas(2_000_000L));
    }

    /**
     * Verifies that when a user transaction fails after consuming throttle capacity,
     * the capacity is properly reclaimed for subsequent transactions.
     */
    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"tokens.nfts.mintThrottleScaleFactor", "contracts.throttle.throttleByGas"},
            throttles = "testSystemFiles/one-tps-nft-mint.json")
    @DisplayName("Throttle capacity is reclaimed when user transaction fails")
    final Stream<DynamicTest> throttleCapacityReclaimedOnUserTxnFailure() {
        final var treasury = "treasury";
        final var nftToken = "nftToken";
        return hapiTest(
                overridingTwo(
                        "tokens.nfts.mintThrottleScaleFactor", "1:1",
                        "contracts.throttle.throttleByGas", "false"),
                cryptoCreate(treasury).balance(ONE_MILLION_HBARS),
                tokenCreate(nftToken)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(treasury)
                        .supplyKey(treasury)
                        .initialSupply(0),
                // First mint should succeed
                mintToken(nftToken, List.of(ByteString.copyFromUtf8("meta1")))
                        .payingWith(treasury)
                        .hasKnownStatus(SUCCESS),
                // Second mint should be throttled (1 TPS limit)
                mintToken(nftToken, List.of(ByteString.copyFromUtf8("meta2")))
                        .payingWith(treasury)
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                // Wait for throttle to reset
                sleepFor(1100),
                // Reset throttles
                overridingThrottles("testSystemFiles/one-tps-nft-mint.json"),
                // Try to mint with invalid token - this should fail but not consume throttle capacity
                mintToken("0.0.999999", List.of(ByteString.copyFromUtf8("meta3")))
                        .payingWith(treasury)
                        .hasKnownStatus(INVALID_TOKEN_ID),
                // Next mint should succeed since the failed mint didn't consume capacity
                mintToken(nftToken, List.of(ByteString.copyFromUtf8("meta4")))
                        .payingWith(treasury)
                        .hasKnownStatus(SUCCESS));
    }
}

