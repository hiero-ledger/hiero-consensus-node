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

package com.swirlds.platform.test.fixtures.state;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;

public class TestMerkleStateRoot extends MerkleStateRoot<TestMerkleStateRoot> implements MerkleNodeState {

    private static final long CLASS_ID = 0x8e300b0dfdafbb1cL;

    public TestMerkleStateRoot() {
        // empty constructor
    }

    protected TestMerkleStateRoot(TestMerkleStateRoot that) {
        super(that);
    }

    @Override
    protected TestMerkleStateRoot copyingConstructor() {
        return new TestMerkleStateRoot(this);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
