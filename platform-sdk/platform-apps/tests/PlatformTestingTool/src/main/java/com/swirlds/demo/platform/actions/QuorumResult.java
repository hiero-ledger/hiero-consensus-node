// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.actions;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * QuorumResult is a child of PTT State to track how many control messages have been collected so far
 */
public class QuorumResult<S extends SelfSerializable> extends PartialMerkleLeaf implements MerkleLeaf {

    public static final long CLASS_ID = 0x58d67f9062814bc6L;

    private final AtomicBoolean quorum;
    private final AtomicReference<S> quorumState;
    private AtomicReferenceArray<S> lastResultValues;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public QuorumResult() {
        this.quorum = new AtomicBoolean();
        this.quorumState = new AtomicReference<>();
    }

    public QuorumResult(final int size) {
        this.quorum = new AtomicBoolean();
        this.quorumState = new AtomicReference<>();
        this.lastResultValues = new AtomicReferenceArray<>(size);
    }

    public QuorumResult(final boolean quorum, final S quorumState, final AtomicReferenceArray<S> newValues) {

        this.quorum = new AtomicBoolean(quorum);
        this.quorumState = new AtomicReference<>(quorumState);
        this.lastResultValues = new AtomicReferenceArray<>(newValues.length());
        for (int i = 0; i < newValues.length(); i++) {
            lastResultValues.getAndSet(i, newValues.get(i));
        }
    }

    public boolean hasQuorum() {
        return quorum.get();
    }

    public S getQuorumState() {
        return quorumState.get();
    }

    public void reset() {
        quorum.set(false);
        quorumState.set(null);

        for (int i = 0; i < lastResultValues.length(); i++) {
            lastResultValues.set(i, null);
        }
    }

    public boolean checkForQuorum(
            final S value, final int quorumThreshold, final long selfId, final Action<Long, S> lostQuorumAction) {
        int membersInAgreement = 0;

        for (int i = 0; i < lastResultValues.length(); i++) {
            final S nodeValue = lastResultValues.get(i);

            if (Objects.equals(value, nodeValue)) {
                membersInAgreement++;
            }
        }
        final boolean newQuorum = membersInAgreement >= quorumThreshold;
        final boolean oldQuorum = quorum.getAndSet(newQuorum);

        if (oldQuorum && !newQuorum) {
            quorumState.set(null);

            if (lostQuorumAction != null) {
                lostQuorumAction.execute(selfId, value);
            }
        } else if (newQuorum) {
            quorumState.set(value);
        }

        return quorum.get();
    }

    public boolean applyTransition(
            final Long node,
            final S state,
            final int quorumThreshold,
            final long selfId,
            final Action<Long, S> lostQuorumAction) {
        final S oldValue = lastResultValues.getAndSet(node.intValue(), state);

        if (Objects.equals(oldValue, state)) {
            return false;
        }

        return checkForQuorum(state, quorumThreshold, selfId, lostQuorumAction);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeBoolean(quorum.get());
        out.writeSerializable(quorumState.get(), true);
        out.writeInt(lastResultValues.length());
        for (int i = 0; i < lastResultValues.length(); i++) {
            out.writeSerializable(lastResultValues.get(i), true);
        }
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, int version) throws IOException {
        this.quorum.getAndSet(in.readBoolean());
        this.quorumState.getAndSet(in.readSerializable());
        this.lastResultValues = new AtomicReferenceArray<>(in.readInt());
        for (int i = 0; i < lastResultValues.length(); i++) {
            lastResultValues.set(i, in.readSerializable());
        }
    }

    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    @Override
    public QuorumResult<S> copy() {
        return new QuorumResult<>(this.quorum.get(), quorumState.get(), lastResultValues);
    }

    @Override
    public String toString() {
        return "QuorumResult{" + "quorum="
                + quorum + ", quorumState="
                + quorumState + ", lastResultValues="
                + lastResultValues + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        QuorumResult<?> that = (QuorumResult<?>) o;

        if (!Objects.equals(quorum.get(), that.quorum.get())) {
            return false;
        }
        if (quorumState != null && that.quorumState != null) {
            if (quorumState.get().equals(that.quorumState.get())) {
                return true;
            }
        }
        if (lastResultValues != null && that.lastResultValues != null) {
            if (lastResultValues.length() == that.lastResultValues.length()) {
                for (int i = 0; i < lastResultValues.length(); i++) {
                    if (!lastResultValues.get(i).equals(that.lastResultValues.get(i))) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(quorum, quorumState, lastResultValues);
    }
}
