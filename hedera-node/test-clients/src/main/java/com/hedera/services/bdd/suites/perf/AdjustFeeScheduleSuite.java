// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.perf;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;

import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * This is a suite for any tests with high volume requests of CryptoTransfer and
 * ConsensusSubmitMessage to avoid the potential INSUFFICIENT_PAYER_BALANCE while the test is
 * running.
 */
public class AdjustFeeScheduleSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(AdjustFeeScheduleSuite.class);

    public AdjustFeeScheduleSuite() {}

    public static void main(String... args) {
        new AdjustFeeScheduleSuite().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(updateFeesFor());
    }

    final Stream<DynamicTest> updateFeesFor() {
        final var fixedFee = ONE_HUNDRED_HBARS;
        return customHapiSpec("updateFees")
                .withProperties(Map.of("fees.useFixedOffer", "true", "fees.fixedOffer", "" + fixedFee))
                .given()
                .when()
                .then(
                        reduceFeeFor(CryptoTransfer, 2L, 3L, 3L),
                        reduceFeeFor(ConsensusSubmitMessage, 2L, 3L, 3L),
                        sleepFor(30000));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
