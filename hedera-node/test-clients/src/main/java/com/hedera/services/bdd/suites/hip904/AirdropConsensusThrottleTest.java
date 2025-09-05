// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class AirdropConsensusThrottleTest {
    private static final Logger LOG = LogManager.getLogger(AirdropConsensusThrottleTest.class);

    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(50);
    private static final int EXPECTED_MAX_AIRDROPS_PER_SEC = 2;

    protected static final String OWNER = "owner";
    protected static final String FUNGIBLE_TOKEN = "fungibleToken";
    protected static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    final AtomicInteger airdropCounter = new AtomicInteger(0);
    final AtomicInteger numAssociationsCounter = new AtomicInteger(0);

    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"tokens.airdrops.enabled", "entities.unlimitedAutoAssociationsEnabled"},
            throttles = "testSystemFiles/mainnet-throttles.json")
    final Stream<DynamicTest> airdropsAreLimitedByConsThrottle() {
        return hapiTest(
                overriding("tokens.airdrops.enabled", "true"),
                runWithProvider(airdropFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get),
                verify(() -> {
                    LOG.info("Airdrop success count: {}", numAssociationsCounter.get());
                    assertTrue(
                            numAssociationsCounter.get() <= (EXPECTED_MAX_AIRDROPS_PER_SEC * duration.get()),
                            String.format(
                                    "Expected airdrop success be less than %d, but was %d",
                                    EXPECTED_MAX_AIRDROPS_PER_SEC * duration.get(), numAssociationsCounter.get()));
                }));
    }

    private Function<HapiSpec, OpProvider> airdropFactory() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        // base tokens
                        tokenCreate(FUNGIBLE_TOKEN)
                                .treasury(OWNER)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(100000L),
                        newKeyNamed("nftSupplyKey"),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .treasury(OWNER)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .name(NON_FUNGIBLE_TOKEN)
                                .supplyKey("nftSupplyKey"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                final var keyName = "airdropKey" + airdropCounter.incrementAndGet();
                final var op1 = newKeyNamed(keyName).shape(SECP256K1_ON);
                final var op2 = sourcingContextual(spec -> tokenAirdrop(moving(1, FUNGIBLE_TOKEN)
                                .between(OWNER, spec.registry().getKey(keyName).toByteString()))
                        .payingWith(OWNER)
                        .deferStatusResolution()
                        .hasPrecheckFrom(OK, BUSY)
                        .signedBy(OWNER)
                        .hasKnownStatusFrom(SUCCESS, THROTTLED_AT_CONSENSUS)
                        .tokenAssociationsObserver(numAssociationsCounter::addAndGet)
                        .noLogging());
                return Optional.of(blockingOrder(op1, op2));
            }
        };
    }
}
