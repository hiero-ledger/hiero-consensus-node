// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

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
 * Networks are started before and terminated after each test method.
 * READ_WRITE ensures multi-network tests run sequentially — they start real subprocess networks
 * that bind ports and use global static state in SubProcessNetwork.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({MultiNetworkExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK")
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
         * Opt-in: provision a per-network ECDSA P-384 CLPR CA before the node JVM starts, dropping
         * its cert + PKCS#8 key PEMs into each node's working dir at {@code data/clpr/ca.crt} /
         * {@code data/clpr/ca.key} (the relative paths a suite advertises via {@code clpr.caCrtPath}
         * / {@code clpr.caKeyPath}). The CA cert DER is stashed by network name so the suite can
         * advertise it on-chain in {@code ClprEndpoint.tls_certificate} via
         * {@link MultiNetworkExtension#clprMtlsCaDer(String)}. When {@code false} (the default) no CA
         * is provisioned and CLPR sync runs plaintext.
         */
        boolean enableClprMtls() default false;

        /**
         * Opt-in: cache the network's TSS-enriched genesis-network.json on first successful run,
         * and preload it (skipping the ~8 min cold WRAPS bootstrap) on subsequent runs.
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
