// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.hello;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * This holds the current state of the swirld. For this simple "hello swirld" code, each transaction is just
 * a string, and the state is just a list of the strings in all the transactions handled so far, in the
 * order that they were handled.
 */
@ConstructableIgnored
public class HelloSwirldDemoState extends MerkleStateRoot<HelloSwirldDemoState> implements MerkleNodeState {

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        /**
         * In this version, serialization was performed by copyTo/copyToExtra and deserialization was performed by
         * copyFrom/copyFromExtra. This version is not supported by later deserialization methods and must be handled
         * specially by the platform.
         */
        public static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        public static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private static final long CLASS_ID = 0xe56b0f87f257d092L;

    private static final int DEFAULT_MAX_ARRAY_SIZE = 1024 * 8;
    private static final int DEFAULT_MAX_STRING_SIZE = 128;

    /**
     * The shared state is just a list of the strings in all transactions, listed in the order received
     * here, which will eventually be the consensus order of the community.
     */
    private List<String> strings = new ArrayList<>();

    /** @return all the strings received so far from the network */
    public synchronized List<String> getStrings() {
        return strings;
    }

    /** @return all the strings received so far from the network, concatenated into one */
    public synchronized String getReceived() {
        return strings.toString();
    }

    /** @return the same as getReceived, so it returns the entire shared state as a single string */
    public synchronized String toString() {
        return strings.toString();
    }

    // ///////////////////////////////////////////////////////////////////

    public HelloSwirldDemoState() {
        // no-op
    }

    private HelloSwirldDemoState(final HelloSwirldDemoState sourceState) {
        super(sourceState);
        this.strings = new ArrayList<>(sourceState.strings);
    }

    @NonNull
    @Override
    public synchronized HelloSwirldDemoState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new HelloSwirldDemoState(this);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    protected HelloSwirldDemoState copyingConstructor() {
        return new HelloSwirldDemoState(this);
    }
}
