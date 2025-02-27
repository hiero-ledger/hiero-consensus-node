// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class PrecompileMintThrottlingCheck extends HapiSuite {

    private static final Logger LOG = LogManager.getLogger(PrecompileMintThrottlingCheck.class);
    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(50);
    private static final int EXPECTED_MAX_MINTS_PER_SEC = 50;
    private static final double ALLOWED_THROTTLE_NOISE_TOLERANCE = 0.15;
    private static final String NON_FUNGIBLE_TOKEN = "NON_FUNGIBLE_TOKEN";
    public static final int GAS_TO_OFFER = 1_000_000;

    public static void main(String... args) {
        new PrecompileMintThrottlingCheck().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(precompileNftMintsAreLimitedByConsThrottle());
    }

    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = "testSystemFiles/mainnet-throttles.json")
    final Stream<DynamicTest> precompileNftMintsAreLimitedByConsThrottle() {
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                runWithProvider(precompileMintsFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get),
                getTokenInfo(NON_FUNGIBLE_TOKEN)
                        .hasTotalSupplySatisfying(supply -> {
                            final var allowedMaxSupply = (int) (unit.get().toSeconds(duration.get())
                                    * EXPECTED_MAX_MINTS_PER_SEC
                                    * (1.0 + ALLOWED_THROTTLE_NOISE_TOLERANCE));
                            final var allowedMinSupply = (int) (unit.get().toSeconds(duration.get())
                                    * EXPECTED_MAX_MINTS_PER_SEC
                                    * (1.0 - ALLOWED_THROTTLE_NOISE_TOLERANCE));
                            Assertions.assertTrue(
                                    supply <= allowedMaxSupply,
                                    String.format(
                                            "Expected max supply to be less than %d, but was %d",
                                            allowedMaxSupply, supply));
                            Assertions.assertTrue(
                                    supply >= allowedMinSupply,
                                    String.format(
                                            "Expected min supply to be at least %d, but was %d",
                                            allowedMinSupply, supply));
                        })
                        .logged());
    }

    private Function<HapiSpec, OpProvider> precompileMintsFactory() {
        final AtomicReference<Address> mintContractAddress = new AtomicReference<>();
        final List<byte[]> someMetadata =
                IntStream.range(0, 100).mapToObj(TxnUtils::randomUtf8Bytes).toList();
        final SplittableRandom r = new SplittableRandom();
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        uploadInitCode("MintNFTContract"),
                        contractCreate("MintNFTContract").gas(GAS_TO_OFFER),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .contractKey(Set.of(TokenKeyType.SUPPLY_KEY), "MintNFTContract")
                                .initialSupply(0)
                                .exposingAddressTo(mintContractAddress::set),
                        getTokenInfo(NON_FUNGIBLE_TOKEN).logged());
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                final var numMetadataThisMint = r.nextInt(1, 2);
                final var metadata = r.ints(numMetadataThisMint, 0, someMetadata.size())
                        .mapToObj(someMetadata::get)
                        .toArray(byte[][]::new);
                var op = contractCall(
                                "MintNFTContract",
                                "mintNonFungibleTokenWithAddress",
                                mintContractAddress.get(),
                                metadata)
                        .gas(2L * GAS_TO_OFFER)
                        .payingWith(GENESIS)
                        .noLogging()
                        .deferStatusResolution()
                        .hasKnownStatusFrom(SUCCESS, CONTRACT_REVERT_EXECUTED);

                return Optional.of(op);
            }
        };
    }

    @Override
    protected Logger getResultsLogger() {
        return LOG;
    }
}
