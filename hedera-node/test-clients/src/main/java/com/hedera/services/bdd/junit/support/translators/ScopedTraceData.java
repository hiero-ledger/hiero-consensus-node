// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HookId;
import edu.umd.cs.findbugs.annotations.Nullable;

public record ScopedTraceData(TraceData traceData, @Nullable HookId hookId) {
    public boolean hasEvmTraceData() {
        return traceData.hasEvmTraceData();
    }

    public EvmTraceData evmTraceDataOrThrow() {
        return traceData.evmTraceDataOrThrow();
    }
}
