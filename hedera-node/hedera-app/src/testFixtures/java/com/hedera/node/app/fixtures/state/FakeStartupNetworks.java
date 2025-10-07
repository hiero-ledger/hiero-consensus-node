// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.services.StartupNetworks;
import com.hedera.node.internal.network.Network;
import com.swirlds.config.api.Configuration;
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
