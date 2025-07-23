// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialNaryMerkleInternal;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

public class BenchmarkMerkleInternal extends PartialNaryMerkleInternal implements BenchmarkMerkleNode, MerkleInternal {

    private final String value;

    private static final long CLASS_ID = 0xe864d84ef7c521b5L;

    public static final int CLASS_VERSION = 1;

    private boolean initialized;

    private final AtomicBoolean released = new AtomicBoolean(false);

    private static final BiFunction<BenchmarkMerkleInternal, Integer, MerkleNode> migrationMapper =
            (node, version) -> node;

    public BenchmarkMerkleInternal(String value) {
        this.value = value;
        this.initialized = false;
    }

    public BenchmarkMerkleInternal() {
        this("?");
        initialized = false;
    }

    private BenchmarkMerkleInternal(final BenchmarkMerkleInternal that) {
        super(that);
        initialized = that.initialized;
        value = that.value;
    }

    @Override
    public void rebuild() {
        if (initialized) {
            throw new RuntimeException("Node should only be initialized once.");
        }
        int numberOfChildren = getNumberOfChildren();
        for (int childIndex = 0; childIndex < numberOfChildren; childIndex++) {
            MerkleNode child = getChild(childIndex);
            if (child instanceof BenchmarkMerkleNode && child.isInternal()) {
                BenchmarkMerkleInternal internalChild = (BenchmarkMerkleInternal) child;
                if (!internalChild.isInitialized()) {
                    throw new RuntimeException("Children must be initialized before their parents.");
                }
            }
        }

        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
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
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(value);
        sb.append("](");
        for (int childIndex = 0; childIndex < getNumberOfChildren(); childIndex++) {
            MerkleNode next = getChild(childIndex);
            sb.append(" ");
            String str = next == null ? "null" : next.toString();
            sb.append(str);
            sb.append(" ");
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroyNode() {
        if (!released.compareAndSet(false, true)) {
            throw new IllegalStateException("This type of node should only be deleted once");
        }
    }

    @Override
    public BenchmarkMerkleInternal copy() {
        return new BenchmarkMerkleInternal(this);
    }

    /**
     * When this node migrates, it will always replace itself with a DummyMerkleInternal containing a single leaf
     * in position 0, and that leaf will equal this node.
     */
    @Override
    public MerkleNode migrate(@NonNull final Configuration configuration, final int version) {
        return migrationMapper.apply(this, version);
    }
}
