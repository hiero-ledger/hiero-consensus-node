// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.primitives.ProtoString;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.state.test.fixtures.merkle.singleton.SingletonNode;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@Deprecated
class SingletonNodeTest extends MerkleTestBase {

    private SingletonNode<ProtoString> node;
    private String expectedValue;

    @BeforeEach
    void setUp() {
        expectedValue = randomString(7);
        node = new SingletonNode<>(
                FIRST_SERVICE,
                STEAM_STATE_KEY,
                SingletonNode.CLASS_ID,
                ProtoString.PROTOBUF,
                ProtoString.newBuilder().value(expectedValue).build());
        // emulate hashing
        node.setHash(new Hash(DigestType.SHA_384));
    }

    @Test
    public void testGetValue() {
        assertEquals(expectedValue, node.getValue().value());
    }

    @Test
    public void testSetValue() {
        final String newValue = randomString(7);
        node.setValue(ProtoString.newBuilder().value(newValue).build());

        assertEquals(newValue, node.getValue().value());
        assertNull(node.getRight().getHash());
    }
}
