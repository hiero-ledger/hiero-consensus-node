// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * A {@link NetworkInfo} implementation that throws {@link UnsupportedOperationException} for all methods,
 * needed as a non-null placeholder for {@link com.hedera.node.app.services.ServiceMigrator} calls made to
 * initialize platform state before the network information is known.
 */
public enum UnavailableNetworkInfo implements NetworkInfo {
    UNAVAILABLE_NETWORK_INFO;

    @NonNull
    @Override
    public Bytes ledgerId() {
        throw new UnsupportedOperationException("Ledger ID is not available");
    }

    @NonNull
    @Override
    public NodeInfo selfNodeInfo() {
        throw new UnsupportedOperationException("Self node info is not available");
    }

    @NonNull
    @Override
    public List<NodeInfo> addressBook() {
        throw new UnsupportedOperationException("Address book is not available");
    }

    @Nullable
    @Override
    public NodeInfo nodeInfo(final long nodeId) {
        throw new UnsupportedOperationException("Node info is not available");
    }

    @Override
    public boolean containsNode(final long nodeId) {
        throw new UnsupportedOperationException("Node info is not available");
    }

    @Override
    public void updateFrom(final State state) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
