package com.hedera.node.app.quiescence;

// NOTE: This is temporary until the PR with the Quiescence interface gets merged
public enum QuiescenceStatus {
    NOT_QUIESCENT,
    BREAKING_QUIESCENCE,
    QUIESCENT
}
