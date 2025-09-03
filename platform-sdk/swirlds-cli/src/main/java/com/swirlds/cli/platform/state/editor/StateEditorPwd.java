// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.route.MerkleRouteUtils;
import picocli.CommandLine;

@CommandLine.Command(name = "pwd", mixinStandardHelpOptions = true, description = "Print the current working route.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorPwd extends StateEditorOperation {

    private String path = "";

    @CommandLine.Parameters(arity = "0..1", description = "The route to show.")
    private void setPath(final String path) {
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("java:S106")
    @Override
    public void run() {
        System.out.println(
                MerkleRouteUtils.merkleRouteToPathFormat(getStateEditor().getRelativeRoute(path)));
    }
}
