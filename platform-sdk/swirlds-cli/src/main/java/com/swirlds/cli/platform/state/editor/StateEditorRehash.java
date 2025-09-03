// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.merkle.utility.MerkleUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.io.IOException;
import picocli.CommandLine;

@CommandLine.Command(
        name = "rehash",
        mixinStandardHelpOptions = true,
        description = "Recompute the hash for the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorRehash extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorRehash.run()")) {
            final Configuration configuration =
                    DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
            final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);
            MerkleUtils.rehashTree(
                    merkleCryptography, reservedSignedState.get().getState().getRoot());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
