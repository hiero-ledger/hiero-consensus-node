// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle;

import static com.swirlds.demo.virtualmerkle.VirtualMerkleLeafHasher.hashOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKeySerializer;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValue;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValueSerializer;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VirtualMerkleLeafHasherTest {

    static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();
    static Path storeDir;
    static SmartContractByteCodeMapKeySerializer keySerializer;
    static SmartContractByteCodeMapValueSerializer valueSerializer;
    static MerkleDbDataSourceBuilder dataSourceBuilder;

    @BeforeAll
    static void beforeAll() {
        try {
            storeDir = Files.createTempDirectory("VirtualMerkleLeafHasherTest2");
            MerkleDb.setDefaultPath(storeDir);
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }

        keySerializer = new SmartContractByteCodeMapKeySerializer();
        valueSerializer = new SmartContractByteCodeMapValueSerializer();

        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig((short) 1, DigestType.SHA_384, 50_000_000, 0);
        dataSourceBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
    }

    @Test
    void checkSimpleHashing2() throws IOException, InterruptedException {
        VirtualMap<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> virtualMap =
                new VirtualMap<>("test2", keySerializer, valueSerializer, dataSourceBuilder, CONFIGURATION);

        final VirtualMerkleLeafHasher<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> hasher =
                new VirtualMerkleLeafHasher<>(virtualMap);

        Long keyInput = 1L;
        byte[] valueInput = "first".getBytes();

        SmartContractByteCodeMapKey key = new SmartContractByteCodeMapKey(keyInput);
        SmartContractByteCodeMapValue value = new SmartContractByteCodeMapValue(valueInput);

        virtualMap.put(key, value);

        Hash before = computeNextHash(null, keyInput, valueInput);

        assertEquals(before, hasher.validate(), "Should have been equal");

        keyInput = 2L;
        valueInput = "second".getBytes();

        key = new SmartContractByteCodeMapKey(keyInput);
        value = new SmartContractByteCodeMapValue(valueInput);

        virtualMap.put(key, value);

        // include previous hash first
        Hash after = computeNextHash(before, keyInput, valueInput);

        assertEquals(after, hasher.validate(), "Should have been equal");

        virtualMap.release();
    }

    @Test
    void checkSimpleHashing3() throws IOException, InterruptedException {
        VirtualMap<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> virtualMap =
                new VirtualMap<>("test3", keySerializer, valueSerializer, dataSourceBuilder, CONFIGURATION);

        final VirtualMerkleLeafHasher<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> hasher =
                new VirtualMerkleLeafHasher<>(virtualMap);

        final Long keyInput1 = 1L;
        final byte[] valueInput1 = "first".getBytes();
        SmartContractByteCodeMapKey key = new SmartContractByteCodeMapKey(keyInput1);
        SmartContractByteCodeMapValue value = new SmartContractByteCodeMapValue(valueInput1);

        virtualMap.put(key, value);

        final Long keyInput2 = 2L;
        final byte[] valueInput2 = "second".getBytes();
        key = new SmartContractByteCodeMapKey(keyInput2);
        value = new SmartContractByteCodeMapValue(valueInput2);

        virtualMap.put(key, value);

        final Long keyInput3 = 3L;
        final byte[] valueInput3 = "third".getBytes();
        key = new SmartContractByteCodeMapKey(keyInput3);
        value = new SmartContractByteCodeMapValue(valueInput3);

        virtualMap.put(key, value);

        // include previous hash first
        Hash hash = null;

        hash = computeNextHash(hash, keyInput2, valueInput2);
        hash = computeNextHash(hash, keyInput1, valueInput1);
        hash = computeNextHash(hash, keyInput3, valueInput3);

        assertEquals(hash, hasher.validate(), "Should have been equal");

        virtualMap.release();
    }

    @Test
    void checkSimpleHashing4() throws IOException, InterruptedException {
        VirtualMap<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> virtualMap =
                new VirtualMap<>("test4", keySerializer, valueSerializer, dataSourceBuilder, CONFIGURATION);

        final VirtualMerkleLeafHasher<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> hasher =
                new VirtualMerkleLeafHasher<>(virtualMap);

        final Long keyInput1 = 1L;
        final byte[] valueInput1 = "first".getBytes();
        SmartContractByteCodeMapKey key = new SmartContractByteCodeMapKey(keyInput1);
        SmartContractByteCodeMapValue value = new SmartContractByteCodeMapValue(valueInput1);

        virtualMap.put(key, value);

        final Long keyInput2 = 2L;
        final byte[] valueInput2 = "second".getBytes();
        key = new SmartContractByteCodeMapKey(keyInput2);
        value = new SmartContractByteCodeMapValue(valueInput2);

        virtualMap.put(key, value);

        final Long keyInput3 = 3L;
        final byte[] valueInput3 = "third".getBytes();
        key = new SmartContractByteCodeMapKey(keyInput3);
        value = new SmartContractByteCodeMapValue(valueInput3);

        virtualMap.put(key, value);

        final Long keyInput4 = 4L;
        final byte[] valueInput4 = "fourth".getBytes();
        key = new SmartContractByteCodeMapKey(keyInput4);
        value = new SmartContractByteCodeMapValue(valueInput4);

        virtualMap.put(key, value);

        // include previous hash first
        Hash hash = null;

        // this is the order for the leafs from first to last
        hash = computeNextHash(hash, keyInput1, valueInput1);
        hash = computeNextHash(hash, keyInput3, valueInput3);
        hash = computeNextHash(hash, keyInput2, valueInput2);
        hash = computeNextHash(hash, keyInput4, valueInput4);

        assertEquals(hash, hasher.validate(), "Should have been equal");

        virtualMap.release();
    }

    private Hash computeNextHash(final Hash hash, final Long keyInput, final byte[] valueInput) throws IOException {
        final ByteBuffer bb = ByteBuffer.allocate(10000);

        if (hash != null) {
            hash.getBytes().writeTo(bb);
        }

        // key serialization
        bb.putLong(keyInput);

        // value serialization
        bb.putInt(valueInput.length);
        bb.put(valueInput);

        return hashOf(Arrays.copyOf(bb.array(), bb.position()));
    }
}
