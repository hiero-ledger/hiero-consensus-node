// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import picocli.CommandLine;

@CommandLine.Command(name = "hash", mixinStandardHelpOptions = true, description = "Hash unhashed nodes the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorHash extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorHash.run()")) {
            final Configuration configuration =
                    DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
            final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);
            merkleCryptography
                    .digestTreeAsync(reservedSignedState.get().getState().getRoot())
                    .get();
        } catch (final InterruptedException | ExecutionException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
