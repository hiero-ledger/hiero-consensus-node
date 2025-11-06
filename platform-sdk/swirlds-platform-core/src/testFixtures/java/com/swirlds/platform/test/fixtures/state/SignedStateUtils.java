// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;

import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        MerkleNodeState root = new TestVirtualMapState();
        TestingAppStateInitializer.initPlatformState(root);
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
        signedState.getState().setHash(CryptoRandomUtils.randomHash(random));
        return signedState;
    }
}
