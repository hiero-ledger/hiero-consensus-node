// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

/** Controls the lifecycle of the node-local CLPR runtime. */
public interface ClprRuntime {

    /** Starts the CLPR runtime when the feature is enabled. */
    void start();

    /** Stops the CLPR runtime if it was instantiated. */
    void stop();
}
