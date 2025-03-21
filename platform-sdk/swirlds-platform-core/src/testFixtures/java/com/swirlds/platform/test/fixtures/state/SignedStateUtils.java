// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;

import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import java.util.Random;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        MerkleNodeState root = new TestMerkleStateRoot();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initPlatformState(root);
        randomPlatformState(random, root, platformStateFacade);
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                TestPlatformContextBuilder.create().build().getConfiguration(),
                CryptoStatic::verifySignature,
                root,
                "test",
                shouldSaveToDisk,
                false,
                false,
                platformStateFacade);
        signedState.getState().setHash(RandomUtils.randomHash(random));
        return signedState;
    }
}
