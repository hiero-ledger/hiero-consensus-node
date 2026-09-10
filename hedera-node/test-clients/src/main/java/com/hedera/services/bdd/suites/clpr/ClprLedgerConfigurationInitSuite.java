// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Happy-path HAPI tests for CLPR ledger configuration initialization (spec §6.1,
 * test-spec §3.1.1 and §3.1.2).
 *
 * Simulates a chain administrator initializing the CLPR ledger configuration for the first time
 *
 * <p>Timeline exercised by {@link #ledgerConfigInitializationHappyPath()}:
 * <ol>
 *   <li>Genesis: {@code ClprServiceImpl.doGenesisSetup()} seeds the singleton with chain_id,
 *       protocol_version, service_address=0x16e, and conservative default throttles.
 *   <li>Admin queries genesis config via {@code getLedgerConfiguration} — confirms it is always
 *       present and contains the seeded values (test-spec §3.1.2: "never absent").
 *   <li>Admin calls {@code updateLedgerConfiguration} with new throttles, service_address, and
 *       one seed endpoint. Per the spec (§6.1 / handler), chain_id and protocol_version are
 *       preserved from genesis; timestamp is auto-set to the consensus time; all mutable fields
 *       are replaced with the caller-supplied values.
 *   <li>Admin queries config again — verifies the mutable fields were persisted, the immutable
 *       fields were preserved, and the timestamp advanced (test-spec §3.1.1:
 *       "Two sequential config updates produce different timestamps; query returns the latest").
 * </ol>
 */
@Tag(CLPR)
public class ClprLedgerConfigurationInitSuite {

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> ledgerConfigInitializationHappyPath() {
        // Capture the genesis configuration before the update so we can verify immutable fields.
        final var genesisConfig = new AtomicReference<ClprLedgerConfiguration>();

        final var updatedThrottles = ClprThrottles.newBuilder()
                .setMaxMessagesPerBundle(200)
                .setMaxMessagePayloadBytes(131_072) // 128 KB
                .setMaxGasPerMessage(5_000_000L)
                .setMaxQueueDepth(500)
                .setMaxSyncBytes(2_097_152L) // 2 MB
                .build();

        // Compressed secp256k1 public keys (33 bytes each): 0x02/0x03 prefix + 32 distinct bytes.
        final var ecdsaKey1 = new byte[33];
        ecdsaKey1[0] = 0x02;
        for (int i = 1; i < 33; i++) ecdsaKey1[i] = (byte) i;

        final var ecdsaKey2 = new byte[33];
        ecdsaKey2[0] = 0x02;
        for (int i = 1; i < 33; i++) ecdsaKey2[i] = (byte) (i + 32);

        final var ecdsaKey3 = new byte[33];
        ecdsaKey3[0] = 0x03;
        for (int i = 1; i < 33; i++) ecdsaKey3[i] = (byte) (i + 64);

        final var seedEndpoint1 =
                HapiClprUpdateLedgerConfiguration.seedEndpoint("10.0.0.1", 50211, new byte[] {1, 2, 3, 4}, ecdsaKey1);
        final var seedEndpoint2 =
                HapiClprUpdateLedgerConfiguration.seedEndpoint("10.0.0.2", 50211, new byte[] {5, 6, 7, 8}, ecdsaKey2);
        final var seedEndpoint3 = HapiClprUpdateLedgerConfiguration.seedEndpoint(
                "10.0.0.3", 50211, new byte[] {9, 10, 11, 12}, ecdsaKey3);

        return hapiTest(
                overriding("clpr.enabled", "true"),

                // Step 1: Verify genesis configuration is present and well-formed.
                clprGetLedgerConfiguration().exposingConfigTo(genesisConfig::set),

                // Step 2: Admin updates the ledger configuration with new mutable fields.
                clprUpdateLedgerConfiguration()
                        .serviceAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x6e})
                        .throttles(updatedThrottles)
                        .seedEndpoint(seedEndpoint1)
                        .seedEndpoint(seedEndpoint2)
                        .seedEndpoint(seedEndpoint3)
                        .payingWith(GENESIS),

                // Step 3: Query again and verify mutable fields persisted while immutable fields
                //         (chain_id, protocol_version) were preserved from genesis.
                clprGetLedgerConfiguration()
                        .maxMessagesPerBundle(200)
                        .maxQueueDepth(500)
                        .maxGasPerMessage(5_000_000L)
                        .exposingConfigTo(updatedConfig -> {
                            final var genesis = genesisConfig.get();
                            Assertions.assertEquals(
                                    genesis.getChainId(),
                                    updatedConfig.getChainId(),
                                    "chain_id must be preserved across updates (immutable)");
                            Assertions.assertEquals(
                                    genesis.getProtocolVersion(),
                                    updatedConfig.getProtocolVersion(),
                                    "protocol_version must be preserved across updates (immutable)");
                            // Timestamp must have advanced from the genesis sentinel (seconds=1).
                            Assertions.assertTrue(
                                    updatedConfig.getTimestamp().getSeconds() > 1L,
                                    "Timestamp must be > genesis sentinel after an update");
                            // Endpoints must reflect what was supplied in the update. The handler
                            // stores the local ledger's endpoints list verbatim; per spec §1.1
                            // (ClprLedgerConfiguration.endpoints), §1.4 (ClprThrottles.max_local_endpoints
                            // bounds local-side registrations, not the config field itself), and §2.4.2
                            // (the max_peer_endpoints truncation rule applies only to peer configs
                            // received via ConfigUpdate). So three in → three out.
                            Assertions.assertEquals(
                                    3,
                                    updatedConfig.getEndpointsCount(),
                                    "Should have three endpoints after the update");
                        }));
    }
}
