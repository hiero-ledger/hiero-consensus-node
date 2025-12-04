package com.hedera.node.app.blocks.impl.streaming;

public record BlockNodeStatus(boolean wasReachable, long latencyMillis, long latestBlockAvailable) {

    public static BlockNodeStatus notReachable() {
        return new BlockNodeStatus(false, -1L, -1L);
    }

    public static BlockNodeStatus reachable(final long latencyMillis, final long latestBlockAvailable) {
        return new BlockNodeStatus(true, latencyMillis, latestBlockAvailable);
    }
}
