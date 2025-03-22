// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class DummyMerkleLeaf extends PartialMerkleLeaf implements DummyMerkleNode, MerkleLeaf {

    protected String value;

    private static final long CLASS_ID = 0x8675309L;

    public static final int CLASS_VERSION = 1;

    private Integer overridingClassVersion = null;

    private boolean throwWhenHashed = false;

    /**
     * If true then throw an exception if the hash is set twice for a node.
     */
    private boolean allowDuplicateHashing;

    private AtomicBoolean released = new AtomicBoolean(false);

    private static BiFunction<DummyMerkleLeaf, Integer, MerkleNode> migrationMapper = (node, version) -> node;

    public DummyMerkleLeaf() {
        this("?");
    }

    public DummyMerkleLeaf(String value) {
        this(value, false);
    }

    public DummyMerkleLeaf(String value, boolean allowDuplicateHashing) {
        if (value.length() > 1024) {
            throw new RuntimeException("Value must not exceed 1024 characters");
        }
        this.value = value;
        this.allowDuplicateHashing = allowDuplicateHashing;
    }

    /**
     * Enable duplicate hashing on this node.
     *
     * @return this object
     */
    public DummyMerkleLeaf enableDuplicateHashing() {
        allowDuplicateHashing = true;
        return this;
    }

    /**
     * Set the method that is used when migrating all DummyMerkleLeaf objects.
     *
     * @param migrationMapper
     * 		a method that is used to migrate a dummy merkle leaf
     */
    public static void setMigrationMapper(final BiFunction<DummyMerkleLeaf, Integer, MerkleNode> migrationMapper) {
        DummyMerkleLeaf.migrationMapper = migrationMapper;
    }

    /**
     * Reset the migration mapper, causing DummyMerkleLeaf objects to not do any migration.
     */
    public static void resetMigrationMapper() {
        migrationMapper = (node, version) -> node;
    }

    private DummyMerkleLeaf(final DummyMerkleLeaf that) {
        super(that);
        this.value = that.value;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeNormalisedString(value);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        value = in.readNormalisedString(1024);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        if (overridingClassVersion != null) {
            return overridingClassVersion;
        } else {
            return CLASS_VERSION;
        }
    }

    /**
     * Set the version. For tests that intentionally want to break things with invalid versions.
     */
    public void setVersion(int version) {
        overridingClassVersion = version;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public void destroyNode() {
        if (!released.compareAndSet(false, true)) {
            throw new IllegalStateException("This type of node should only be deleted once");
        }
    }

    @Override
    public void setHash(Hash hash) {
        if (!allowDuplicateHashing && getHash() != null && hash != null) {
            fail("Hash should not be set if value is already known");
        }
        if (throwWhenHashed) {
            throw new RuntimeException("this node intentionally fails when it is hashed");
        }
        super.setHash(hash);
    }

    /**
     * Specify if this node should throw an exception when it is hashed.
     */
    public void setThrowWhenHashed(final boolean throwWhenHashed) {
        this.throwWhenHashed = throwWhenHashed;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public DummyMerkleLeaf copy() {
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
        return new DummyMerkleLeaf(this);
    }

    @Override
    public int hashCode() {
        if (value == null) {
            return 0;
        } else {
            return value.hashCode();
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DummyMerkleLeaf)) {
            return false;
        }
        DummyMerkleLeaf that = (DummyMerkleLeaf) obj;
        return Objects.equals(this.value, that.value);
    }

    /**
     * When this node migrates, it will always replace itself with a DummyMerkleInternal containing a single leaf
     * in position 0, and that leaf will equal this node.
     */
    @Override
    public MerkleNode migrate(final int version) {
        return migrationMapper.apply(this, version);
    }
}
