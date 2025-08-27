// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * This merkle leaf stores a string randomly generated using seed. When serialized in abbreviated
 * form, only the seed is required, size, and standard deviation are reconstruct the leaf.
 */
public class DummyMerkleExternalLeaf extends PartialMerkleLeaf implements DummyMerkleNode, MerkleLeaf {

    private static final long CLASS_ID = 0x86753094L;

    private static final int CLASS_VERSION = 1;

    protected long seed;

    // We must store average size and standard deviation as integers
    // Currently the data serialization stream does not support double values
    protected int averageSize;
    protected int standardDeviation;

    private static final int MAX_VALUE_LENGTH = 1024;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * @param seed
     * 		The seed used to generate the value of this leaf.
     */
    public DummyMerkleExternalLeaf(long seed, int averageSize, int standardDeviation) {
        this.seed = seed;
        this.averageSize = averageSize;
        this.standardDeviation = standardDeviation;
    }

    public DummyMerkleExternalLeaf() {}

    private DummyMerkleExternalLeaf(final DummyMerkleExternalLeaf that) {
        super(that);
        this.seed = that.seed;
        this.averageSize = that.averageSize;
        this.standardDeviation = that.standardDeviation;
    }

    protected String generateValue() {
        String value = MerkleTestUtils.generateRandomString(seed, averageSize, standardDeviation);
        if (value.length() > MAX_VALUE_LENGTH) {
            value = value.substring(0, MAX_VALUE_LENGTH);
        }
        return value;
    }

    private Path getFile(final Path directory) {
        return directory.resolve("DummyMerkleExternalLeaf-" + seed + "-" + averageSize + "-" + standardDeviation);
    }

    @Override
    public void serialize(final SerializableDataOutputStream out, final Path outputDirectory) throws IOException {
        out.writeLong(seed);
        out.writeInt(averageSize);
        out.writeInt(standardDeviation);

        try (final SerializableDataOutputStream fileOut = new SerializableDataOutputStream(
                new FileOutputStream(getFile(outputDirectory).toFile()))) {
            fileOut.writeNormalisedString(generateValue());
        }
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final Path inputDirectory, final int version)
            throws IOException {
        seed = in.readLong();
        averageSize = in.readInt();
        standardDeviation = in.readInt();

        try (final SerializableDataInputStream fileIn = new SerializableDataInputStream(
                new FileInputStream(getFile(inputDirectory).toFile()))) {

            assertEquals(
                    generateValue(), fileIn.readNormalisedString(Integer.MAX_VALUE), "deserialized value should match");
        }
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeLong(seed);
        out.writeInt(averageSize);
        out.writeInt(standardDeviation);
        out.writeNormalisedString(generateValue());
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        seed = in.readLong();
        averageSize = in.readInt();
        standardDeviation = in.readInt();
        final String v = in.readNormalisedString(MAX_VALUE_LENGTH);

        assertEquals(generateValue(), v, "deserialized value should match");
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    @Override
    public String getValue() {
        return generateValue();
    }

    @Override
    public String toString() {
        return "<" + seed + ", " + averageSize + ", " + standardDeviation + ">";
    }

    @Override
    public void destroyNode() {
        if (!destroyed.compareAndSet(false, true)) {
            throw new IllegalStateException("This type of node should only be deleted once");
        }
    }

    @Override
    public DummyMerkleExternalLeaf copy() {
        return new DummyMerkleExternalLeaf(this);
    }
}
