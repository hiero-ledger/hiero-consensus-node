// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import java.io.IOException;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * When the teacher submits a query of "do you have this node", the learner replies with a QueryResponse.
 */
public class QueryResponse implements SelfSerializable {

    private static final long CLASS_ID = 0x7CBF61E166C6E5F7L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    protected boolean learnerHasTheNode;

    public QueryResponse() {}

    /**
     * Construct a query response.
     *
     * @param learnerHasTheNode
     * 		true if this node (the learner) has the given response
     */
    public QueryResponse(boolean learnerHasTheNode) {
        this.learnerHasTheNode = learnerHasTheNode;
    }

    /**
     * Does the learner have the node in question?
     *
     * @return true if the learner has the node
     */
    public boolean doesLearnerHaveTheNode() {
        return learnerHasTheNode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeBoolean(learnerHasTheNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        learnerHasTheNode = in.readBoolean();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
