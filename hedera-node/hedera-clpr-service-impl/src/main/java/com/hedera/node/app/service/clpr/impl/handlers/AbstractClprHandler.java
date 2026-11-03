// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Protocol-level constants and abstract base for all CLPR transaction handlers.
 *
 * <p>Implements the template method pattern for {@link #handle}: always performs a null check
 * and the CLPR enabled guard before delegating to {@link #doHandle}. Subclasses must override
 * {@code doHandle} — they cannot override {@code handle} directly, which guarantees these
 * preconditions can never be accidentally omitted.
 *
 * <p>Also provides shared channel-lookup helpers.
 */
public abstract class AbstractClprHandler implements TransactionHandler {

    /** Length in bytes of a CLPR channel ID (SHA-256 hash). */
    protected static final int CHANNEL_ID_LENGTH = 32;

    /** Length in bytes of an ownership commitment (Keccak-256 hash). */
    protected static final int COMMITMENT_LENGTH = 32;

    /** Length in bytes of an Ed25519 public key. */
    protected static final int ED25519_KEY_LENGTH = 32;

    /** Length in bytes of an uncompressed ECDSA secp256k1 public key (without prefix). */
    protected static final int ECDSA_UNCOMPRESSED_KEY_LENGTH = 64;

    /** Length in bytes of an Ed25519 or ECDSA signature. */
    protected static final int SIGNATURE_LENGTH = 64;

    /**
     * Final template method: null-checks context, enforces the CLPR enabled guard,
     * then delegates to {@link #doHandle}.
     */
    @Override
    public final void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        if (!context.configuration().getConfigData(ClprConfig.class).enabled()) {
            throw new HandleException(CLPR_NOT_ENABLED);
        }
        doHandle(context);
    }

    /**
     * Subclass-specific handle logic. Called only after null and enabled checks pass.
     */
    protected abstract void doHandle(@NonNull HandleContext context) throws HandleException;

    /**
     * Looks up a channel by ID and returns it, or throws {@code CLPR_CHANNEL_NOT_FOUND}.
     * <p>Only call from {@link #doHandle} — the enabled guard has already been enforced by then.
     */
    @NonNull
    protected static ClprChannel requireChannel(
            @NonNull final ReadableChannelStore channelStore, @NonNull final Bytes channelId) {
        requireNonNull(channelStore);
        requireNonNull(channelId);
        final var channel = channelStore.getChannel(channelId);
        if (channel == null) {
            throw new HandleException(CLPR_CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    /**
     * Looks up a channel and requires it to not be {@code CLOSED} or {@code PENDING}.
     * Accepts ACTIVE, PAUSED, CLOSING, and DRAINED so that inbound bundles can flow
     * while the channel is draining.
     * Throws {@code CLPR_CHANNEL_NOT_FOUND} or {@code CLPR_INVALID_CHANNEL_STATUS}.
     */
    @NonNull
    protected static ClprChannel requireNonClosedChannel(
            @NonNull final ReadableChannelStore channelStore, @NonNull final Bytes channelId) {
        final var channel = requireChannel(channelStore, channelId);
        final var status = channel.status();
        if (status == ClprChannelStatus.CLOSED || status == ClprChannelStatus.PENDING) {
            throw new HandleException(CLPR_INVALID_CHANNEL_STATUS);
        }
        return channel;
    }

    /** Converts a consensus {@link Instant} to a protobuf {@link Timestamp}. */
    @NonNull
    protected static Timestamp toTimestamp(@NonNull final Instant instant) {
        return Timestamp.newBuilder()
                .seconds(instant.getEpochSecond())
                .nanos(instant.getNano())
                .build();
    }

    protected static void validateTimestamp(@Nullable final Timestamp ts, @NonNull final ResponseCodeEnum code) {
        if (ts == null) return;
        if (ts.seconds() < 0 || ts.nanos() < 0 || ts.nanos() > 999_999_999) {
            throw new HandleException(code);
        }
    }
}
