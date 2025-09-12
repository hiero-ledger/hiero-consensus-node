// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.singleton;

import static com.swirlds.state.test.fixtures.merkle.logging.TestStateLogger.logSingletonRead;
import static com.swirlds.state.test.fixtures.merkle.logging.TestStateLogger.logSingletonWrite;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.utility.DebugIterationEndpoint;
import com.swirlds.common.utility.Labeled;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A merkle node with a string (the label) as the left child, and the merkle node value as the right
 * child. We actually support a raw type (any type!) as the value, and we serialize it and put it
 * into a simple merkle node.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <T> The value type
 */
@Deprecated
@DebugIterationEndpoint
public class SingletonNode<T> extends PartialBinaryMerkleInternal implements Labeled, MerkleInternal {
    public static final long CLASS_ID = 0x3832CC837AB77BFL;
    public static final int CLASS_VERSION = 1;

    /**
     * @deprecated Only exists for constructable registry as it works today. Remove ASAP!
     */
    @Deprecated(forRemoval = true)
    public SingletonNode() {
        setLeft(new StringLeaf());
        setRight(null);
    }

    public SingletonNode(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            long classId,
            @NonNull final Codec<T> codec,
            @Nullable final T value) {
        setLeft(new StringLeaf(StateMetadata.computeLabel(serviceName, stateKey)));
        setRight(new ValueLeaf<>(classId, codec, value));
    }

    private SingletonNode(@NonNull final SingletonNode<T> other) {
        this.setLeft(other.getLeft().copy());
        this.setRight(other.getRight().copy());
    }

    @Override
    public SingletonNode<T> copy() {
        return new SingletonNode<>(this);
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
    public String getLabel() {
        final StringLeaf left = getLeft();
        return left.getLabel();
    }

    public T getValue() {
        final ValueLeaf<T> right = getRight();
        // Log to transaction state log, what was read
        logSingletonRead(getLabel(), right);
        return right.getValue();
    }

    public void setValue(T value) {
        ValueLeaf<T> right = getRight();
        right.setValue(value);
        right.invalidateHash();
        invalidateHash();
        // Log to transaction state log, what was written
        logSingletonWrite(getLabel(), value);
    }
}
