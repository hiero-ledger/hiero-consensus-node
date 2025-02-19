/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app;

import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class sole purpose is to extend the {@link MerkleStateRoot} class and implement the {@link MerkleNodeState}.
 * Technically, {@link MerkleStateRoot} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 */
@ConstructableIgnored
public class HederaStateRoot extends MerkleStateRoot<HederaStateRoot> implements MerkleNodeState {

    private static final long CLASS_ID = 0x8e300b0dfdafbb1aL;

    public HederaStateRoot() {
        // empty
    }

    protected HederaStateRoot(@NonNull HederaStateRoot from) {
        super(from);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaStateRoot copyingConstructor() {
        return new HederaStateRoot(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
