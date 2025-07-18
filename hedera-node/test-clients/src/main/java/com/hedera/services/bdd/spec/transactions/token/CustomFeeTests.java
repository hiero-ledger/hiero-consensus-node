// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.builtFixedHts;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.builtFractional;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.builtRoyaltyNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.builtRoyaltyWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.CustomFeeLimit;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Assertions;

public class CustomFeeTests {
    public static BiConsumer<HapiSpec, List<CustomFee>> fixedHbarFeeInSchedule(long amount, String collector) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtFixedHbar(amount, collector, false, spec);
            failUnlessPresent("fixed ℏ", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFee>> fixedHtsFeeInSchedule(
            long amount, String denom, String collector) {
        return (spec, actual) -> {
            final var expected = builtFixedHts(amount, denom, collector, false, spec);
            failUnlessPresent("fixed HTS", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFee>> fractionalFeeInSchedule(
            long numerator, long denominator, long min, OptionalLong max, boolean netOfTransfers, String collector) {
        return (spec, actual) -> {
            final var expected =
                    builtFractional(numerator, denominator, min, max, netOfTransfers, collector, false, spec);
            failUnlessPresent("fractional", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFee>> royaltyFeeWithoutFallbackInSchedule(
            long numerator, long denominator, String collector) {
        return (spec, actual) -> {
            final var expected = builtRoyaltyNoFallback(numerator, denominator, collector, false, spec);
            failUnlessPresent("royalty", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFee>> royaltyFeeWithFallbackInHbarsInSchedule(
            long numerator, long denominator, long fallbackAmount, String collector) {
        return (spec, actual) -> {
            final var expected = builtRoyaltyWithFallback(
                    numerator,
                    denominator,
                    collector,
                    false,
                    fixedHbarFeeInheritingRoyaltyCollector(fallbackAmount),
                    spec);
            failUnlessPresent("royalty", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFee>> royaltyFeeWithFallbackInTokenInSchedule(
            long numerator, long denominator, long fallbackAmount, String fallbackDenom, String collector) {
        return (spec, actual) -> {
            final var expected = builtRoyaltyWithFallback(
                    numerator,
                    denominator,
                    collector,
                    false,
                    fixedHtsFeeInheritingRoyaltyCollector(fallbackAmount, fallbackDenom),
                    spec);
            failUnlessPresent("royalty", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<FixedCustomFee>> expectedConsensusFixedHbarFee(
            long amount, String collector) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtConsensusFixedHbar(amount, collector, spec);
            failUnlessConsensusFeePresent("fixed ℏ", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<FixedCustomFee>> expectedConsensusFixedHbarFee(
            long amount, AccountID collector) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtConsensusFixedHbar(amount, collector);
            failUnlessConsensusFeePresent("fixed ℏ", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<FixedCustomFee>> expectedConsensusFixedHTSFee(
            long amount, String token, String collector) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtConsensusFixedHts(amount, token, collector, spec);
            failUnlessConsensusFeePresent("fixed hts", actual, expected);
        };
    }

    private static void failUnlessPresent(String detail, List<CustomFee> actual, CustomFee expected) {
        for (var customFee : actual) {
            if (expected.equals(customFee)) {
                return;
            }
        }
        Assertions.fail("Expected a " + detail + " fee " + expected + ", but only had: " + actual);
    }

    public static BiConsumer<HapiSpec, List<CustomFeeLimit>> expectedCustomFeeLimit(String account, long amount) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtCustomFeeLimit(account, amount, spec);
            failUnlessFeeLimitPresent("fixed ℏ", actual, expected);
        };
    }

    public static BiConsumer<HapiSpec, List<CustomFeeLimit>> expectedCustomFeeLimitHts(
            String account, String token, long amount) {
        return (spec, actual) -> {
            final var expected = CustomFeeSpecs.builtCustomFeeLimitHts(account, token, amount, spec);
            failUnlessFeeLimitPresent("fixed HTS", actual, expected);
        };
    }

    private static void failUnlessConsensusFeePresent(
            String detail, List<FixedCustomFee> actual, FixedCustomFee expected) {
        for (var customFee : actual) {
            if (expected.equals(customFee)) {
                return;
            }
        }
        Assertions.fail("Expected a " + detail + " fee " + expected + ", but only had: " + actual);
    }

    private static void failUnlessFeeLimitPresent(String detail, List<CustomFeeLimit> actual, CustomFeeLimit expected) {
        for (var feeLimit : actual) {
            if (expected.equals(feeLimit)) {
                return;
            }
        }
        Assertions.fail("Expected a " + detail + " fee limit " + expected + ", but only had: " + actual);
    }
}
