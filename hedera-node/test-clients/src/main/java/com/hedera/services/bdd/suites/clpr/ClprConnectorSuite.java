// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-network HAPI tests for the CLPR Connector lifecycle.
 *
 */
@Tag(CLPR)
public class ClprConnectorSuite {

    @LeakyHapiTest(
            requirement = PROPERTY_OVERRIDES,
            overrides = {"clpr.enabled"})
    final Stream<DynamicTest> rejectsRegisterWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprRegisterConnector()
                        .channelId(new byte[32])
                        .connectorId(new byte[32])
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRegisterWithMalformedConnectorId() {
        return hapiTest(clprRegisterConnector()
                .channelId(new byte[32])
                .connectorId(new byte[16]) // wrong length
                .payingWith(GENESIS)
                .hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(
            requirement = PROPERTY_OVERRIDES,
            overrides = {"clpr.enabled"})
    final Stream<DynamicTest> rejectsDeregisterWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprDeregisterConnector()
                        .channelId(new byte[32])
                        .connectorId(new byte[32])
                        .stakeRecipient(GENESIS)
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }
}
