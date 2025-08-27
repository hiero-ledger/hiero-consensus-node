// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v030;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * The initial implementation of {@link FeatureFlags} from v0.30; lazy creation never enabled
 * but {@code CREATE2} still with a feature flag.
 */
@Singleton
public class Version030FeatureFlags implements FeatureFlags {
    @Inject
    public Version030FeatureFlags() {
        // Dagger2
    }

    @Override
    public boolean isCreate2Enabled(@NonNull final MessageFrame frame) {
        return configOf(frame).getConfigData(ContractsConfig.class).allowCreate2();
    }

    @Override
    public boolean isImplicitCreationEnabled() {
        return false;
    }
}
