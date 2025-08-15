// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.throttle.Throttle;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.function.Supplier;

/**
 * Provides the context for the application.
 *
 * @param instantSource The source of the current instant.
 * @param signatureVerifier The verifier of signatures.
 * @param gossip The gossip interface.
 * @param configSupplier The configuration.
 * @param selfNodeInfoSupplier The supplier of the self-node info
 * @param metricsSupplier The supplier of metrics.
 * @param throttleFactory The factory for throttles.
 * @param feeChargingSupplier supplies the app's fee charging strategy implementation
 * @param idFactory The factory for entity ids.
 */
public record AppContextImpl(
        @NonNull InstantSource instantSource,
        @NonNull SignatureVerifier signatureVerifier,
        @NonNull Gossip gossip,
        @NonNull Supplier<Configuration> configSupplier,
        @NonNull Supplier<NodeInfo> selfNodeInfoSupplier,
        @NonNull Supplier<Metrics> metricsSupplier,
        @NonNull Throttle.Factory throttleFactory,
        @NonNull Supplier<FeeCharging> feeChargingSupplier,
        @NonNull EntityIdFactory idFactory)
        implements AppContext {
    public AppContextImpl {
        requireNonNull(instantSource);
        requireNonNull(signatureVerifier);
        requireNonNull(gossip);
        requireNonNull(configSupplier);
        requireNonNull(selfNodeInfoSupplier);
        requireNonNull(metricsSupplier);
        requireNonNull(throttleFactory);
        requireNonNull(feeChargingSupplier);
        requireNonNull(idFactory);
    }
}
