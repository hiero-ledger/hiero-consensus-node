// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import picocli.CommandLine;

/**
 * The root pcli command for the state editor.
 */
@CommandLine.Command(name = "state editor", description = "An interactive SignedState.swh editor.")
public class StateEditorRoot implements Runnable {
    @Override
    public void run() {
        // It should be impossible to reach this
        throw new IllegalStateException("No subcommand provided");
    }
}
