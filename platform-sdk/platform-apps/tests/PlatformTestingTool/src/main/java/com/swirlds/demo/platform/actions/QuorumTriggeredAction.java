// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.actions;

import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.io.SelfSerializable;

public class QuorumTriggeredAction<S extends SelfSerializable> extends TriggeredAction<Long, S> {
    private static final Logger logger = LogManager.getLogger(QuorumTriggeredAction.class);
    private static final Marker LOGM_DEMO_INFO = MarkerManager.getMarker("DEMO_INFO");

    private final long selfId;
    private final int members;
    private final int quorumThreshold;

    private Action<Long, S> lostQuorumAction;
    private QuorumResult<S> quorumResult;

    public QuorumTriggeredAction(
            final long selfId, final int members, final int quorumThreshold, final Action<Long, S> action) {
        this(() -> selfId, () -> members, () -> quorumThreshold, action);
    }

    public QuorumTriggeredAction(
            final LongSupplier selfIdSupplier,
            final IntSupplier membersSupplier,
            final IntSupplier quorumThresholdSupplier,
            final Action<Long, S> action) {
        super();

        if (selfIdSupplier == null) {
            throw new IllegalArgumentException("selfIdSupplier");
        }

        if (membersSupplier == null) {
            throw new IllegalArgumentException("membersSupplier");
        }

        if (quorumThresholdSupplier == null) {
            throw new IllegalArgumentException("quorumThresholdSupplier");
        }
        this.selfId = selfIdSupplier.getAsLong();
        this.members = membersSupplier.getAsInt();
        this.quorumThreshold = quorumThresholdSupplier.getAsInt();

        this.quorumResult = new QuorumResult<>(this.members);
        if ((this.quorumThreshold <= 0 && this.members > 0) || this.quorumThreshold > this.members) {
            throw new IndexOutOfBoundsException("quorumThreshold");
        }

        this.withTrigger(this::applyTransition).withAction(action);
    }

    public QuorumResult<S> getQuorumResult() {
        return quorumResult;
    }

    public void setQuorumResult(QuorumResult<S> quorumResult) {
        this.quorumResult = quorumResult;
    }

    public int getMembers() {
        return members;
    }

    public int getQuorumThreshold() {
        return quorumThreshold;
    }

    public boolean hasQuorum() {
        return quorumResult.hasQuorum();
    }

    public S getQuorumState() {
        return quorumResult.getQuorumState();
    }

    public boolean hasQuorum(final S state) {
        return hasQuorum() && Objects.equals(state, getQuorumState());
    }

    public QuorumTriggeredAction<S> withLostQuorumAction(final Action<Long, S> action) {
        this.lostQuorumAction = action;
        return this;
    }

    @Override
    public void reset() {
        quorumResult.reset();
    }

    private boolean applyTransition(final Long node, final S state) {
        logger.info(LOGM_DEMO_INFO, "Node {} apply state {}", node, state);
        return quorumResult.applyTransition(node, state, quorumThreshold, selfId, lostQuorumAction);
    }
}
