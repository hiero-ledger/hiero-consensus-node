// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.CONFIGURATION;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.CONFIGURATION;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        MerkleNodeState root = new TestVirtualMapState(CONFIGURATION,
                new NoOpMetrics(),
                Time.getCurrent(),
                MerkleCryptographyFactory.create(CONFIGURATION));
        TestingAppStateInitializer.DEFAULT.initPlatformState(root);
        randomPlatformState(random, root, platformStateFacade);
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                CONFIGURATION,
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
