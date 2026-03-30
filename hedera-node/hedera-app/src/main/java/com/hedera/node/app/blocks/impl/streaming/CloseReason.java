package com.hedera.node.app.blocks.impl.streaming;

public enum CloseReason {
    BLOCK_NODE_BEHIND(true),
    BLOCK_NODE_HIGH_LATENCY(true),
    BUFFER_SATURATION(true),
    CONFIG_UPDATE(false),
    CONNECTION_ERROR(true),
    CONNECTION_STALLED(true),
    END_STREAM_RECEIVED(true),
    HIGHER_PRIORITY_FOUND(false),
    INTERNAL_ERROR(true),
    PERIODIC_RESET(false),
    SHUTDOWN(false),
    UNKNOWN(false);

    private final boolean isCoolDownRequired;

    CloseReason(final boolean isCoolDownRequired) {
        this.isCoolDownRequired = isCoolDownRequired;
    }

    public boolean isCoolDownRequired() {
        return isCoolDownRequired;
    }
}
