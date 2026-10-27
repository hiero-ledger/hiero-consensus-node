// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Tightens existing ledgers' CLPR limits once, when upgrading from software before 0.80. */
public final class V0800ClprSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(80).patch(0).build();

    public V0800ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void restart(@NonNull final MigrationContext<SemanticVersion> ctx) {
        requireNonNull(ctx);
        final var previousVersion = ctx.previousVersion();
        // Ignore prerelease/build labels: a restart of any 0.80 build must preserve later admin updates.
        if (previousVersion == null
                || SEMANTIC_VERSION_COMPARATOR.compare(
                                previousVersion.copyBuilder().pre("").build("").build(), VERSION)
                        >= 0) {
            return;
        }
        final var configState = ctx.newStates().<ClprLedgerConfiguration>getSingleton(LEDGER_CONFIGURATION_STATE_ID);
        final var existing = configState.get();
        if (existing == null) {
            // Post-upgrade initialization supplies the new defaults when CLPR did not exist before this upgrade.
            return;
        }
        final var throttles = existing.throttlesOrElse(ClprThrottles.DEFAULT);
        final var tightened = throttles
                .copyBuilder()
                .maxMessagesPerBundle(capped(throttles.maxMessagesPerBundle(), 100))
                .maxQueueDepth(capped(throttles.maxQueueDepth(), 1_000))
                .maxMessagePayloadBytes(capped(throttles.maxMessagePayloadBytes(), 4_096))
                .maxPeerEndpoints(capped(throttles.maxPeerEndpoints(), 10))
                .build();
        if (!tightened.equals(throttles)) {
            // Startup state changes are externalized by the migration stream. Advance the logical config time
            // so each channel lazily propagates these limits; no consensus transaction exists at startup yet.
            final var timestamp = existing.timestampOrElse(Timestamp.DEFAULT);
            final var nextTimestamp = timestamp.nanos() == 999_999_999
                    ? new Timestamp(Math.addExact(timestamp.seconds(), 1), 0)
                    : new Timestamp(timestamp.seconds(), timestamp.nanos() + 1);
            configState.put(existing.copyBuilder()
                    .throttles(tightened)
                    .timestamp(nextTimestamp)
                    .build());
        }
    }

    private static int capped(final int value, final int limit) {
        // The protobuf fields are uint32; zero includes the old unlimited peer-endpoint default.
        return value == 0 || Integer.compareUnsigned(value, limit) > 0 ? limit : value;
    }
}
