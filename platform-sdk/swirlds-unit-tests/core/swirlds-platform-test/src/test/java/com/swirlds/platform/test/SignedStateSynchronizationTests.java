/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.test;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.FakeStateLifecycles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Signed State Synchronization Tests")
public class SignedStateSynchronizationTests {
    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
        FakeStateLifecycles.registerMerkleStateRootClassIds();
    }

    @Test
    @Tag(TestComponentTags.MERKLE)
    @Tag(TestComponentTags.PLATFORM)
    @DisplayName("Signed State Synchronization")
    public void SignedStateSynchronization() throws Exception {
        SignedState state = SignedStateUtils.randomSignedState(1234);
        state.getState().setHash(null); // FUTURE WORK root has a hash but other parts do not...
        MerkleTestUtils.hashAndTestSynchronization(null, state.getState(), reconnectConfig);
    }
}
