// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class CustomFeeRecursionFallbackTest {
    private static final String TREASURY = "treasury";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";
    private static final String COLLECTOR = "collector";

    @LeakyHapiTest(overrides = {"tokens.maxCustomFeeDepth"})
    @DisplayName("Fallback max plausible custom-fee recursion depth returns correct status")
    final Stream<DynamicTest> fallbackMaxPlausibleCustomFeeDepthReturnsCorrectStatus() {
        // We need maxCustomFeeDepth > MAX_PLAUSIBLE_LEVEL_NUM (=10) so the earlier guard doesn't fire first.
        // Then we create a chain of 12 tokens where token[i] charges a fixed HTS fee in token[i+1].
        final List<String> tokens =
                IntStream.range(0, 12).mapToObj(i -> "FEE_CHAIN_TOKEN_" + i).toList();

        final List<SpecOperation> ops = new ArrayList<>();
        ops.add(overriding("tokens.maxCustomFeeDepth", "50"));

        ops.add(cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS));
        ops.add(cryptoCreate(SENDER).balance(ONE_MILLION_HBARS));
        ops.add(cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(COLLECTOR).balance(0L));

        // Create token[11] first (no fee), then token[10] charges in token[11], ..., token[0] charges in token[1]
        ops.add(tokenCreate(tokens.get(11))
                .treasury(TREASURY)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L));
        // The fee collector must be associated with any denominating token used in custom fees at token creation time
        ops.add(tokenAssociate(COLLECTOR, tokens.get(11)));
        for (int i = 10; i >= 0; i--) {
            ops.add(tokenCreate(tokens.get(i))
                    .treasury(TREASURY)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000_000L)
                    .withCustom(fixedHtsFee(1, tokens.get(i + 1), COLLECTOR)));
            // Ensure collector is associated with token[i] before it becomes the denom token for token[i-1]
            ops.add(tokenAssociate(COLLECTOR, tokens.get(i)));
        }

        // Ensure the sender can pay fees at every level
        ops.add(tokenAssociate(SENDER, tokens.toArray(String[]::new)));
        ops.add(tokenAssociate(RECEIVER, tokens.get(0)));

        // Fund the sender with balances for the base transfer and each fee denomination token
        for (final var token : tokens) {
            ops.add(cryptoTransfer(moving(100L, token).between(TREASURY, SENDER)));
        }

        // This transfer should recurse beyond MAX_PLAUSIBLE_LEVEL_NUM; the expected behavior is the dedicated status,
        // not FAIL_INVALID via an unchecked exception.
        ops.add(cryptoTransfer(moving(1L, tokens.get(0)).between(SENDER, RECEIVER))
                .payingWith(SENDER)
                .hasKnownStatus(CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH));

        return hapiTest(flattened(ops));
    }
}
