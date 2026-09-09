package org.hiero.consensus.hashgraph.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class ConsensusEngineOutputBuffer {

    private final Queue<ConsensusEngineOutput> buffer;

    public ConsensusEngineOutputBuffer() {
        buffer = new ArrayDeque<>();
    }

    @NonNull
    public ConsensusEngineOutput add(@NonNull final List<ConsensusEngineOutput> output) {
        buffer.addAll(output.subList(1, output.size()));
        return output.getFirst();
    }

    @Nullable
    public ConsensusEngineOutput getNextRound() {
        return buffer.poll();
    }
}
