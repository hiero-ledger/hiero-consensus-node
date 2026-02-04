// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.signer;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.test.fixtures.addressbook.RosterWithKeys;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.NodeId;

public class RealEventSigner implements EventSigner {
    private final Map<NodeId, BytesSigner> signers;

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
