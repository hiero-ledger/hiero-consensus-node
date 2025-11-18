package com.hedera.node.app.history.impl;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public class WrapsControllerImpl implements  WrapsController {
    @NonNull
    @Override
    public State advanceProtocol(@NonNull final Instant now) {
        requireNonNull(now);
        throw new AssertionError("Not implemented");
    }

    @Override
    public Result resultOrThrow() {
        throw new AssertionError("Not implemented");
    }
}
