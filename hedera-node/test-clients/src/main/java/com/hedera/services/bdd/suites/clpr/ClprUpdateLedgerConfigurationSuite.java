// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_TOO_MANY_SEED_ENDPOINTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI tests for CLPR ledger configuration management (CLPR-1.2).
 */
@Tag(CLPR)
public class ClprUpdateLedgerConfigurationSuite {

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> canUpdateLedgerConfigurationAsSystemAdmin() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                        .seedEndpoint(HapiClprUpdateLedgerConfiguration.seedEndpoint(
                                "192.168.1.1", 50211, new byte[] {1, 2, 3, 4}, new byte[33]))
                        .payingWith(GENESIS));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> nonAdminCannotUpdateLedgerConfiguration() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                cryptoCreate("civilian").balance(ONE_HUNDRED_HBARS),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                        .seedEndpoint(HapiClprUpdateLedgerConfiguration.seedEndpoint(
                                "192.168.1.1", 50211, new byte[] {1, 2, 3, 4}, new byte[33]))
                        .payingWith("civilian")
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsConfigurationWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsInvalidThrottles() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                        .throttles(ClprThrottles.newBuilder()
                                .setMaxMessagesPerBundle(0)
                                .setMaxMessagePayloadBytes(65536)
                                .setMaxQueueDepth(1000)
                                .build())
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_CLPR_CONFIGURATION));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsServiceAddressOtherThanClprSystemContract() {
        // Peers verify this config against the ledger's endpoint manifest, whose service address
        // is fixed to the CLPR system contract's (spec §4.8), so any other address is rejected.
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(new byte[] {0, 0, 1})
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_CLPR_CONFIGURATION));
    }

    @org.junit.jupiter.api.Disabled("CLPR_TOO_MANY_SEED_ENDPOINTS precheck no longer fires. Per the "
            + "refreshed upstream spec (§1.1 ClprLedgerConfiguration renamed seed_endpoints→endpoints, "
            + "§1.4 ClprThrottles.max_peer_endpoints added with 'stores only the first max_peer_endpoints "
            + "entries…and discards the remainder' semantics, §2.4.2 Peer Endpoint Roster), excess peer "
            + "endpoints are now silently truncated by the *receiver* of a ConfigUpdate, not rejected at "
            + "update-time precheck. This test's assertion contradicts the new spec and should be rewritten "
            + "as a positive 'receiver stores only N' check against the peer-side path.")
    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsTooManySeedEndpoints() {
        final var op = clprUpdateLedgerConfiguration()
                .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                .payingWith(GENESIS)
                .hasPrecheck(CLPR_TOO_MANY_SEED_ENDPOINTS);
        for (int i = 0; i < 11; i++) {
            op.seedEndpoint(HapiClprUpdateLedgerConfiguration.seedEndpoint(
                    "192.168.1." + i, 50211, new byte[] {1, 2, 3, 4}, new byte[33]));
        }
        return hapiTest(overriding("clpr.enabled", "true"), op);
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> ignoresImmutableFieldsOnUpdate() {
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprUpdateLedgerConfiguration()
                        .configuration(ClprLedgerConfiguration.newBuilder()
                                .setChainId("should-be-ignored")
                                .setProtocolVersion(999)
                                .setServiceAddress(com.google.protobuf.ByteString.copyFrom(CLPR_SERVICE_ADDRESS_20))
                                .setThrottles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                                .build())
                        .payingWith(GENESIS));
    }
}
