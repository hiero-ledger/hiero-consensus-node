// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.signed.SignedState;
import java.util.Random;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;

public class SignedStateUtils {

    public static SignedState randomSignedState(long seed) {
        return randomSignedState(new Random(seed));
    }

    public static SignedState randomSignedState(Random random) {
        PlatformContext platformContext = TestPlatformContextBuilder.create().build();
        MerkleNodeState root = new TestVirtualMapState(platformContext, state -> PlatformStateAccessor.GENESIS_ROUND);
        TestingAppStateInitializer.DEFAULT.initPlatformState(root);
        TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        randomPlatformState(random, root, platformStateFacade);
        boolean shouldSaveToDisk = random.nextBoolean();
        SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
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
