/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.fixtures.state;

import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.Network;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.StartupNetworks;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

public class FakeStartupNetworks implements StartupNetworks {
    private final Network genesisNetwork;

    public FakeStartupNetworks(@NonNull final Network genesisNetwork) {
        this.genesisNetwork = requireNonNull(genesisNetwork);
    }

    @Override
    public Network genesisNetworkOrThrow(@NonNull final Configuration platformConfig) {
        return genesisNetwork;
    }

    @Override
    public Optional<Network> overrideNetworkFor(long roundNumber, Configuration platformConfig) {
        return Optional.empty();
    }

    @Override
    public void setOverrideRound(final long roundNumber) {
        // No-op
    }

    @Override
    public void archiveStartupNetworks() {
        // No-op
    }

    @Override
    public Network migrationNetworkOrThrow(final Configuration configuration) {
        return Network.DEFAULT;
    }
}
