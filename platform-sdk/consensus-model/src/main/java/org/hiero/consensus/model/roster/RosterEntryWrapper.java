// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.X509Certificate;
import java.util.List;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.consensus.model.node.NodeId;

/**
 * A wrapper around a {@link RosterEntry} that provides additional functionality and convenience methods.
 */
public class RosterEntryWrapper {

    @NonNull
    private final RosterEntry rosterEntry;

    @NonNull
    private final NodeId nodeId;

    @Nullable
    private final X509Certificate gossipCaCertificate;

    /**
     * Constructs a new {@link RosterEntryWrapper} instance.
     *
     * @param rosterEntry the {@link RosterEntry} to wrap
     */
    public RosterEntryWrapper(@NonNull final RosterEntry rosterEntry) {
        this.rosterEntry = requireNonNull(rosterEntry);
        this.nodeId = NodeId.of(rosterEntry.nodeId());
        X509Certificate certificate;
        try {
            certificate = CryptoUtils.decodeCertificate(
                    rosterEntry.gossipCaCertificate().toByteArray());
        } catch (final CryptographyException e) {
            certificate = null;
        }
        this.gossipCaCertificate = certificate;
    }

    /**
     * Returns the {@link NodeId} associated with this roster entry.
     *
     * @return the {@link NodeId} of this roster entry
     */
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * Returns the weight of this roster entry.
     *
     * @return the weight of this roster entry
     */
    public long weight() {
        return rosterEntry.weight();
    }

    /**
     * Returns the gossip CA certificate associated with this roster entry.
     *
     * @return the gossip CA certificate of this roster entry
     */
    @Nullable
    public X509Certificate gossipCaCertificate() {
        return gossipCaCertificate;
    }

    /**
     * Returns the gossip endpoints associated with this roster entry.
     *
     * @return the gossip endpoints of this roster entry
     */
    @NonNull
    public List<ServiceEndpoint> gossipEndpoint() {
        return rosterEntry.gossipEndpoint();
    }

    /**
     * Returns the underlying {@link RosterEntry} instance.
     *
     * @return the underlying {@link RosterEntry}
     */
    @NonNull
    public RosterEntry toPbj() {
        return rosterEntry;
    }
}
