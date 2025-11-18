package com.hedera.node.app.history.impl;

import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.time.Instant;
import java.util.Map;

/**
 * Manages deterministic transitions through WRAPS 2.0 phases {@link WrapsPhase#R1},
 * {@link WrapsPhase#R2}, and {@link WrapsPhase#R3} on the way to an aggregate signature.
 */
public interface WrapsController {
    enum State {
        WAITING_FOR_KEYS, RUNNING, FAILED, FINISHED
    }

    record Result(byte[] rotationMessage, byte[] aggregateSignature, boolean[] signedBy) {}

    @NonNull
    State stateAt(@NonNull Instant now);

    void startProtocol(@NonNull Map<Long, Bytes> publicKeys, @NonNull Instant now);

    void advanceProtocol(@NonNull Instant now);

    /**
     * Returns the result of the protocol, or throws if the protocol state is not finished.
     * @return the result, if the protocol state is finished
     * @throws IllegalStateException if the protocol state is not finished
     */
    Result resultOrThrow();
}
