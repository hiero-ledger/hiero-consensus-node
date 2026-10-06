// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.signed;

import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.hiero.base.crypto.Signature;
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

    public static Map<NodeId, Signature> generateSignatureMap(final Random random) {
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
    void serializationTest() throws IOException, ParseException {
        final Random random = getRandomPrintSeed();

        final Map<NodeId, Signature> signatures = generateSignatureMap(random);
        final SigSet sigSet = new SigSet();
        for (final NodeId node : signatures.keySet()) {
            sigSet.addSignature(node, signatures.get(node));
        }

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (final WritableStreamingData out = new WritableStreamingData(baos)) {
            sigSet.serialize(out);
        }

        final byte[] serializedBytes = baos.toByteArray();
        final ByteArrayInputStream bais = new ByteArrayInputStream(serializedBytes);
        try (final ReadableStreamingData in = new ReadableStreamingData(bais)) {
            final SigSet deserialized = new SigSet();
            deserialized.deserialize(in);

            assertEqualsSigSet(sigSet, deserialized);
        }
    }

    private static void assertEqualsSigSet(final SigSet expected, final SigSet actual) {
        assertEquals(expected.size(), actual.size());
        for (final NodeId nodeId : expected) {
            assertTrue(actual.hasSignature(nodeId));
            assertEquals(expected.getSignature(nodeId), actual.getSignature(nodeId));
        }
    }
}
