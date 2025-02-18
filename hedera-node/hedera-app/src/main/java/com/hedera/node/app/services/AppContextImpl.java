/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.services;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.throttle.Throttle;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.info.NodeInfo;
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
