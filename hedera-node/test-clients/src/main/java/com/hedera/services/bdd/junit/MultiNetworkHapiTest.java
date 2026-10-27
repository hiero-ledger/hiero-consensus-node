// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.ClprNetworkLocksProvider;
import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a HAPI test factory that provisions multiple isolated subprocess networks and injects them
 * as {@code SubProcessNetwork} parameters in declaration order.
 *
 * <p>This annotation replaces {@link HapiTest} for multi-network scenarios; do not combine them.
 *
 * <p><b>Lifecycle.</b> Networks are shared by name across all tests in the run. All tests that declare the
 * same set of networks form a network group: its networks are started on the group's first test, stay up
 * across its other tests, and are terminated after its last test. Groups are admitted under a total node
 * budget, so a group may wait in {@code beforeEach} until running groups finish (see
 * {@code MultiNetworkGroupQueue}).
 *
 * <p><b>Concurrency.</b> These tests share real subprocess networks (by name, across the JVM), so two
 * tests that touch the same network must be coordinated. Serialization is done by JUnit via
 * {@link ClprNetworkLocksProvider} (the {@code @ResourceLock} provider below), which derives one lock per
 * network from this test's {@link Network} set: {@code READ} by default (readers on a network run
 * concurrently) or {@code READ_WRITE} when the test is {@link MultiNetworkLeakyHapiTest} (it mutates shared state, so it runs
 * alone). Tests on disjoint networks (e.g. general vs. tls vs. manifest) run in parallel. A new suite is
 * scheduled correctly just by declaring its {@link Network}s; mark it {@link MultiNetworkLeakyHapiTest} if it changes ledger
 * throttles/config, overrides a network property, or restarts a network.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({MultiNetworkExtension.class, SpecNamingExtension.class})
@ResourceLock(providers = ClprNetworkLocksProvider.class)
public @interface MultiNetworkHapiTest {
    Network[] value() default {
        @Network(name = "PRIMARY"), @Network(name = "PEER"),
    };

    @interface Network {
        /**
         * Shorthand for {@link #name()}: enables the terse {@code @Network("ledgerA")} form.
         *
         * <p>{@code @Network(name = "ledgerA")} remains valid and is required when other
         * parameters ({@code firstGrpcPort}, {@code setupOverrides}, {@code size}, …) are
         * specified alongside. When both {@link #value()} and {@link #name()} are non-empty
         * they must agree — the reader ({@code MultiNetworkExtension.resolveName}) throws on
         * mismatch. Use {@link MultiNetworkExtension#resolveName}
         * rather than reading either element directly.
         */
        String value() default "";

        /** See {@link #value()} — same field, exposed for the explicit {@code @Network(name = "…", …)} form. */
        String name() default "";

        int size() default 1;

        long shard() default -1;

        long realm() default -1;
        /** Starting gRPC port. Pass -1 to auto-allocate (risk of collision with other networks). */
        int firstGrpcPort() default -1;

        ConfigOverride[] setupOverrides() default {};

        /**
         * Opt-in: start each node's HAPI TLS listener, and with it CLPR sync over TLS. Before the node JVM
         * starts, a per-network self-signed ECDSA P-384 certificate and its PKCS#8 key are written into each
         * node's working dir at the default {@code netty.tlsCrt.path} / {@code netty.tlsKey.path}
         * ({@code hedera.crt} / {@code hedera.key}). Each node then advertises its HAPI TLS port and that
         * certificate in its {@code ClprEndpoint}, and peers dial it over TLS. When {@code false} (the default)
         * no certificate is written, no HAPI TLS listener starts, and CLPR sync runs plaintext on the plain
         * HAPI port. Set it only alongside {@link #firstHapiTlsPort()}.
         */
        boolean enableHapiTls() default false;

        /**
         * Base {@code grpc.tlsPort} for the HAPI TLS listener, seeded into each node's
         * {@code application.properties} at boot. Like {@link #firstGrpcPort()}, this is a <b>base</b>:
         * co-located subprocess nodes all bind {@code 127.0.0.1}, so node {@code i} gets
         * {@code firstHapiTlsPort + i} (the base thus consumes the range
         * {@code [firstHapiTlsPort, firstHapiTlsPort + size)} — leave room when picking bases for other
         * networks). {@code -1} (the default) seeds nothing; set it only alongside
         * {@code enableHapiTls = true}. Prefer this over a {@code @ConfigOverride("grpc.tlsPort")}:
         * the per-node offset is applied automatically and stays discoverable at the call site.
         */
        int firstHapiTlsPort() default -1;

        /**
         * Opt-in: cache the network's TSS-enriched genesis-network.json on first successful run,
         * and preload it (skipping the cold TSS bootstrap) on subsequent runs.
         *
         * <p>When {@code true}, {@code MultiNetworkExtension}:
         * <ul>
         *   <li>before subprocess start, resolves a cached fixture from
         *       {@code hedera-node/test-clients/tss-startup-assets/<name>-genesis-network.json[.gz]}
         *       (preferring the committed {@code .gz} form over any stale local raw {@code .json}),
         *       gunzips on the fly if needed, and installs it as
         *       {@code <node>/data/config/genesis-network.json};</li>
         *   <li>on a cold run (no cached fixture), injects
         *       {@code networkAdmin.diskNetworkExport=ONLY_FREEZE_BLOCK} +
         *       {@code networkAdmin.diskNetworkExportTss=true}, waits for the WRAPS sync-point, then
         *       freezes the network so a single TSS-enriched {@code output/network.json} is flushed;</li>
         *   <li>harvests that snapshot, gzips it into the cache dir as {@code <name>-genesis-network.json.gz}
         *       (committable, ~10x smaller), and restarts the network warm-preloaded from it so the
         *       test runs against the same warm path as later runs.</li>
         * </ul>
         *
         * <p>The {@code .gz} fixtures live under {@code tss-startup-assets/} and are tracked in
         * git so CI / fresh clones get a warm preload for free. Raw {@code .json} variants in the
         * same dir are gitignored (dev-local convenience). To force regeneration: delete the
         * committed {@code .gz} files and rerun any {@code tssPreload = true} test — the cold path
         * will harvest fresh ones.
         */
        boolean tssPreload() default true;
    }
}
