package com.hedera.node.app.grpc.impl.obs;

public enum UserAgentVersion {
    LATEST("Latest"),
    OLD("Old"),
    PRE_RELEASE("PreRelease"),
    UNKNOWN("Unknown");

    private final String key;

    UserAgentVersion(final String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
