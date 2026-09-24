// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.TestTags.MULTINETWORK;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.clpr.HieroToHieroBase.multiNetworkHapiTest;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.ClprTssFixtureHarvester;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Home for the warm-cache fixture generators. Each test brings its network(s) up <b>cold</b> (its committed
 * fixture must be deleted first), at which point {@link MultiNetworkExtension} freezes, harvests the merged
 * per-ledger fixture, and restarts warm; the test body is just a liveness check (plus, for the multi-node
 * manifest network, an assertion that the merged fixture holds one distinct TSS key per node).
 *
 * <p>Every generator is {@link Disabled} so it never runs in a normal suite pass. Delete the fixture(s) you
 * want to refresh and run the specific test (removing its {@code @Disabled}, or with
 * {@code -Djunit.jupiter.conditions.deactivate=*}). Running the <b>whole</b> suite in one JVM regenerates
 * every fixture with globally-unique, non-overlapping ports, which is what lets a warm start reproduce a
 * fixture's ports without any patching (see {@code MultiNetworkExtension.ensureFixturePortReservations}).
 * The mTLS bases below are kept distinct from every other network's so a full run never collides.
 */
@Tag(MULTINETWORK)
@DisplayName("CLPR fixture generators")
@Disabled("Fixture generator")
public class ClprFixtureGeneratorSuite {
    // Kept in step with the consuming suites so a regenerated fixture matches how it is used.
    private static final int MTLS_PORT_LEDGER_A_MTLS = 41450; // ClprHieroToHieroMtlsSuite
    private static final int MTLS_PORT_LEDGER_B_MTLS = 42450;
    private static final int MTLS_PORT_LEDGER_A_MANIFEST = 43450; // ClprHieroToHieroManifestSuite
    private static final int MTLS_PORT_LEDGER_B_MANIFEST = 44450;

    @MultiNetworkHapiTest({@Network(name = "ledgerA"), @Network(name = "ledgerB")})
    @DisplayName("Generate ledgerA / ledgerB fixtures")
    Stream<DynamicTest> generateLedgerFixtures(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        return multiNetworkHapiTest(
                "generateLedgerFixtures",
                Stream.of(liveness("ledgerA liveness", ledgerA), liveness("ledgerB liveness", ledgerB)));
    }

    @MultiNetworkHapiTest({@Network(name = "ledgerA_restart"), @Network(name = "ledgerB_restart")})
    @DisplayName("Generate ledgerA / ledgerB fixtures")
    Stream<DynamicTest> generateRestartLedgerFixtures(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        return multiNetworkHapiTest(
                "generateLedgerFixtures",
                Stream.of(liveness("ledgerA liveness", ledgerA), liveness("ledgerB liveness", ledgerB)));
    }

    @MultiNetworkHapiTest({
        @Network(name = "ledgerA_mtls", enableClprMtls = true, firstMtlsPort = MTLS_PORT_LEDGER_A_MTLS),
        @Network(name = "ledgerB_mtls", enableClprMtls = true, firstMtlsPort = MTLS_PORT_LEDGER_B_MTLS)
    })
    @DisplayName("Generate ledgerA_mtls / ledgerB_mtls fixtures")
    Stream<DynamicTest> generateMtlsLedgerFixtures(final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        return multiNetworkHapiTest(
                "generateMtlsLedgerFixtures",
                Stream.of(liveness("ledgerA_mtls liveness", ledgerA), liveness("ledgerB_mtls liveness", ledgerB)));
    }

    @MultiNetworkHapiTest({
        @Network(
                name = "ledgerA_manifest",
                size = 2,
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_LEDGER_A_MANIFEST,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")}),
        @Network(
                name = "ledgerB_manifest",
                enableClprMtls = true,
                firstMtlsPort = MTLS_PORT_LEDGER_B_MANIFEST,
                setupOverrides = {@ConfigOverride(key = "clpr.endpointManifestEnabled", value = "true")})
    })
    @DisplayName("Generate ledgerA_manifest (size 2) / ledgerB_manifest fixtures")
    Stream<DynamicTest> generateManifestLedgerFixtures(
            final SubProcessNetwork ledgerA, final SubProcessNetwork ledgerB) {
        return multiNetworkHapiTest(
                "generateManifestLedgerFixtures",
                Stream.of(
                        liveness("ledgerA_manifest liveness", ledgerA),
                        liveness("ledgerB_manifest liveness", ledgerB),
                        // The multi-node fixture is a merge of each node's freeze export; assert it captured a
                        // distinct blsPrivateKey per node (a bad merge would duplicate one and break node1's warm
                        // start).
                        networkHapiTest(
                                        "Assert ledgerA_manifest fixture carries distinct per-node keys",
                                        ledgerA,
                                        withOpContext((spec, opLog) ->
                                                ClprTssFixtureHarvester.assertPerNodeFixturesHaveDistinctKeys(
                                                        ledgerA.name(), 2)))
                                .findFirst()
                                .orElseThrow()));
    }

    private static DynamicTest liveness(final String name, final SubProcessNetwork network) {
        return networkHapiTest(name, network, cryptoCreate("liveness").balance(ONE_HUNDRED_HBARS))
                .findFirst()
                .orElseThrow();
    }
}
