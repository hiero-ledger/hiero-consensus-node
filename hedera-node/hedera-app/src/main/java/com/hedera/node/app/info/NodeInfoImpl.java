// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

public record NodeInfoImpl(
        long nodeId,
        @NonNull AccountID accountId,
        long weight,
        List<ServiceEndpoint> gossipEndpoints,
        @Nullable Bytes sigCertBytes,
        @NonNull List<ServiceEndpoint> hapiEndpoints,
        boolean declineReward,
        @Nullable Bytes grpcCertHash)
        implements NodeInfo {
    @NonNull
    public static NodeInfo fromRosterWithCurrentMetadata(
            @NonNull final RosterEntry rosterEntry, @NonNull final Node node) {
        return new NodeInfoImpl(
                rosterEntry.nodeId(),
                node.accountIdOrThrow(),
                rosterEntry.weight(),
                rosterEntry.gossipEndpoint(),
                rosterEntry.gossipCaCertificate(),
                node.serviceEndpoint(),
                node.declineReward(),
                node.grpcCertificateHash().length() > 0 ? node.grpcCertificateHash() : null);
    }

    @NonNull
    public static NodeInfo fromRosterEntry(
            @NonNull final RosterEntry rosterEntry, @NonNull final AccountID nodeAccountID) {
        return new NodeInfoImpl(
                rosterEntry.nodeId(),
                nodeAccountID,
                rosterEntry.weight(),
                rosterEntry.gossipEndpoint(),
                rosterEntry.gossipCaCertificate(),
                List.of(),
                true,
                null);
    }
}
