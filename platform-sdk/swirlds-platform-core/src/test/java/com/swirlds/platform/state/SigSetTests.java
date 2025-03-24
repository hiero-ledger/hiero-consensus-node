// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.test.fixtures.RandomUtils.randomSignature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.io.streams.SerializableDataInputStreamImpl;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.platform.state.signed.SigSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SigSet Tests")
class SigSetTests {

    private static Set<NodeId> getSigningNodes(final SigSet sigSet) {
        final Set<NodeId> nodes = new HashSet<>();
        sigSet.iterator().forEachRemaining(nodes::add);
        return nodes;
    }

    private static Map<NodeId, Signature> generateSignatureMap(final Random random) {
        final Map<NodeId, Signature> signatures = new HashMap<>();

        for (int i = 0; i < 1_000; i++) {
            // There will be a few duplicates, but that doesn't really matter
            final NodeId nodeId = NodeId.of(random.nextLong(0, 10_000));
            final Signature signature = randomSignature(random);
            signatures.put(nodeId, signature);
        }

        return signatures;
    }

    @Test
    @DisplayName("Basic Operation Test")
    void basicOperationTest() {
        final Random random = getRandomPrintSeed();

        final Map<NodeId, Signature> signatures = generateSignatureMap(random);

        final SigSet sigSet = new SigSet();
        final Set<NodeId> addedNodes = new HashSet<>();

        for (final NodeId node : signatures.keySet()) {

            sigSet.addSignature(node, signatures.get(node));
            addedNodes.add(node);

            // Sometimes add twice. This should have no effect.
            if (random.nextBoolean()) {
                sigSet.addSignature(node, signatures.get(node));
            }

            assertEquals(addedNodes.size(), sigSet.size());

            for (final NodeId metaNode : signatures.keySet()) {
                if (addedNodes.contains(metaNode)) {
                    assertTrue(sigSet.hasSignature(metaNode));
                    assertSame(signatures.get(metaNode), sigSet.getSignature(metaNode));
                } else {
                    assertFalse(sigSet.hasSignature(metaNode));
                    assertNull(sigSet.getSignature(metaNode));
                }
            }

            assertEquals(addedNodes, getSigningNodes(sigSet));
        }
    }

    @Test
    @DisplayName("Serialization Test")
    void serializationTest() throws IOException, ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("");

        final Random random = getRandomPrintSeed();
        final SigSet sigSet = new SigSet();

        generateSignatureMap(random).forEach(sigSet::addSignature);

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStreamImpl(byteOut);

        out.writeSerializable(sigSet, true);

        final SerializableDataInputStream in =
                new SerializableDataInputStreamImpl(new ByteArrayInputStream(byteOut.toByteArray()));

        final SigSet deserializedSigSet = in.readSerializable();

        assertEquals(sigSet.size(), deserializedSigSet.size());
        assertEquals(getSigningNodes(sigSet), getSigningNodes(deserializedSigSet));

        for (final NodeId node : sigSet) {
            assertEquals(sigSet.getSignature(node), deserializedSigSet.getSignature(node));
        }
    }
}
