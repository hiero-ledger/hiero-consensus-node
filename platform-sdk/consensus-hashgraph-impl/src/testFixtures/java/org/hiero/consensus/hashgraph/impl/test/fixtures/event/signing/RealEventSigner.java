// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.signing;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RosterWithKeys;

/**
 * An {@link GeneratorEventSigner} that produces real cryptographic signatures using the private keys from a
 * {@link RosterWithKeys}. Each node's signer is resolved from the roster at construction time.
 */
public class RealEventSigner implements GeneratorEventSigner {
    private final Map<NodeId, BytesSigner> signers;

    /**
     * Creates a new {@code RealEventSigner} that can sign events on behalf of any node in the given roster.
     *
     * @param rosterWithKeys the roster containing the cryptographic keys for each node
     */
    public RealEventSigner(final RosterWithKeys rosterWithKeys) {
        this.signers = new HashMap<>();
        for (final RosterEntry entry : rosterWithKeys.getRoster().rosterEntries()) {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final KeyPair keyPair = rosterWithKeys.getKeysAndCerts(nodeId).sigKeyPair();
            final BytesSigner signer = SigningFactory.createSigner(keyPair);
            signers.put(nodeId, signer);
        }
    }

    @Override
    public Bytes signEvent(final UnsignedEvent unsignedEvent) {
        final BytesSigner signer = signers.get(unsignedEvent.getMetadata().getCreatorId());
        if (signer == null) {
            throw new IllegalStateException("No signer found for node ID: "
                    + unsignedEvent.getMetadata().getCreatorId());
        }
        if (unsignedEvent.getHash() == null) {
            throw new IllegalStateException("The event must have a hash before it can be signed");
        }

        return signer.sign(unsignedEvent.getHash().getBytes());
    }
}
