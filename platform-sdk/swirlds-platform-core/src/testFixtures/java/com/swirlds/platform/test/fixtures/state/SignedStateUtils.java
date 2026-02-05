// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;

import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.merkle.VirtualMapState;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        VirtualMapState root = createTestState();
        TestingAppStateInitializer.initPlatformState(root);
        randomPlatformState(random, root);
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                TestPlatformContextBuilder.create().build().getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                root,
                "test",
                shouldSaveToDisk,
                false,
                false);
        signedState.getState().setHash(CryptoRandomUtils.randomHash(random));
        return signedState;
    }
}
