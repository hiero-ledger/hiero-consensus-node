// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test;

import static com.swirlds.platform.test.PlatformStateUtils.randomPlatformState;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.FAKE_MERKLE_STATE_LIFECYCLES;

import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import java.util.Random;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        TestPlatformStateFacade platformStateFacade =
                new TestPlatformStateFacade(version -> new BasicSoftwareVersion(version.major()));
        MerkleNodeState root = new TestMerkleStateRoot();
        FAKE_MERKLE_STATE_LIFECYCLES.initPlatformState(root);
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
