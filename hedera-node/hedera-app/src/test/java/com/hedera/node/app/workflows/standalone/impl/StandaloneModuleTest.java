// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class StandaloneModuleTest {
    @Test
    void standaloneExecutorIsNotALiveConsensusNode() {
        // The standalone transaction executor must report liveConsensusNode=false so DispatchValidator's NODE-payer
        // guard stays exempt for its sig-map-less, caller-payer dispatches (e.g. Mirror Node gas estimation /
        // eth_call).
        // A regression to true here would reject those and break the Mirror Node executor.
        assertFalse(StandaloneModule.provideIsLiveConsensusNode());
    }
}
