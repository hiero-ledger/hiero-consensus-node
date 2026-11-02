// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_STATE_ERRATA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ERRATA_STATE_CHANGE;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.mapKeyFor;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.mapValueFor;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.queuePushFor;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.singletonPutFor;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles {@code StateErrata} transactions, which correct network state by applying a list of state changes.
 *
 * <p>The changes are applied verbatim, in order, to the transaction's writable state, and are committed and
 * externalized in the block stream exactly like the effects of any other transaction. No derived bookkeeping
 * (such as entity counts) is performed.
 *
 * <p>Every change is validated before any is applied: it must be a singleton update, map update, map delete,
 * queue push, or queue pop; it must target a state currently defined by some service; its operation must match
 * that state's kind; and its keys and values must be of that state's types. The type check matters because a
 * mismatched key or value would otherwise only fail when the state is serialized, after the transaction has
 * completed.
 */
@Singleton
public class StateErrataHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(StateErrataHandler.class);

    private final StateDefinitionLookup stateDefinitionLookup;

    /**
     * A validated state change, decoded into the objects to write.
     *
     * @param serviceName the service that owns the state
     * @param stateId the state id
     * @param kind the change operation
     * @param key the decoded key, for map operations
     * @param value the decoded value, for updates and pushes
     */
    private record ResolvedChange(
            @NonNull String serviceName,
            int stateId,
            @NonNull ChangeOperationOneOfType kind,
            @Nullable Object key,
            @Nullable Object value) {}

    @Inject
    public StateErrataHandler(@NonNull final StateDefinitionLookup stateDefinitionLookup) {
        this.stateDefinitionLookup = requireNonNull(stateDefinitionLookup);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var stateChanges = context.body().stateErrataOrThrow().stateChanges();
        if (stateChanges.isEmpty()) {
            throw new PreCheckException(EMPTY_STATE_ERRATA);
        }
        for (final var serialized : stateChanges) {
            final var stateChange = parse(serialized);
            if (stateChange == null || decode(stateChange) == null) {
                throw new PreCheckException(INVALID_ERRATA_STATE_CHANGE);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        requireNonNull(context);
        // Only the payer signature is required, and privileged authorization is enforced by the workflow
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().stateErrataOrThrow();
        final List<ResolvedChange> resolved = new ArrayList<>(op.stateChanges().size());
        for (final var serialized : op.stateChanges()) {
            resolved.add(resolve(serialized));
        }
        final var state = writableStateOf(context);
        for (final var change : resolved) {
            apply(state, change);
        }
        log.info(
                "Applied state errata with {} change(s) to state ids {}",
                resolved.size(),
                resolved.stream().map(ResolvedChange::stateId).distinct().toList());
    }

    private ResolvedChange resolve(@NonNull final Bytes serialized) {
        final var stateChange = parse(serialized);
        failIf(stateChange == null);
        final var decoded = decode(requireNonNull(stateChange));
        failIf(decoded == null);
        final var serviceState = stateDefinitionLookup.lookup(stateChange.stateId());
        failIf(serviceState == null);
        final var definition = requireNonNull(serviceState).definition();
        final var kind = stateChange.changeOperation().kind();
        final boolean kindMatches =
                switch (kind) {
                    case SINGLETON_UPDATE -> definition.singleton();
                    case MAP_UPDATE, MAP_DELETE -> definition.keyValue();
                    case QUEUE_PUSH, QUEUE_POP -> definition.queue();
                    default -> false;
                };
        failIf(!kindMatches);
        final var change = requireNonNull(decoded);
        if (change.key() != null) {
            failIf(!isOfCodecType(requireNonNull(definition.keyCodec()), change.key()));
        }
        if (change.value() != null) {
            failIf(!isOfCodecType(definition.valueCodec(), change.value()));
        }
        return new ResolvedChange(
                serviceState.serviceName(), stateChange.stateId(), kind, change.key(), change.value());
    }

    private static void apply(@NonNull final State state, @NonNull final ResolvedChange change) {
        final var writableStates = state.getWritableStates(change.serviceName());
        final int stateId = change.stateId();
        switch (change.kind()) {
            case SINGLETON_UPDATE ->
                writableStates.<Object>getSingleton(stateId).put(change.value());
            case MAP_UPDATE ->
                writableStates
                        .<Object, Object>get(stateId)
                        .put(requireNonNull(change.key()), requireNonNull(change.value()));
            case MAP_DELETE -> writableStates.<Object, Object>get(stateId).remove(requireNonNull(change.key()));
            case QUEUE_PUSH -> writableStates.<Object>getQueue(stateId).add(requireNonNull(change.value()));
            case QUEUE_POP -> writableStates.getQueue(stateId).poll();
            default -> throw new IllegalStateException("Unsupported errata change " + change.kind());
        }
    }

    /**
     * Returns the writable state of the transaction's dispatch. Writes made through it are part of the
     * transaction's savepoint, so they are rolled back on failure and externalized on commit.
     */
    private static State writableStateOf(@NonNull final HandleContext context) {
        if (context.savepointStack() instanceof State state) {
            return state;
        }
        throw new IllegalStateException("Savepoint stack does not expose writable state");
    }

    /**
     * Decodes the key and value of a supported state change, or returns {@code null} if the change is of an
     * unsupported kind or is missing required content.
     */
    private static @Nullable DecodedChange decode(@NonNull final StateChange stateChange) {
        try {
            return switch (stateChange.changeOperation().kind()) {
                case SINGLETON_UPDATE -> new DecodedChange(null, singletonPutFor(stateChange.singletonUpdateOrThrow()));
                case MAP_UPDATE -> {
                    final var update = stateChange.mapUpdateOrThrow();
                    yield new DecodedChange(mapKeyFor(update.keyOrThrow()), mapValueFor(update.valueOrThrow()));
                }
                case MAP_DELETE ->
                    new DecodedChange(mapKeyFor(stateChange.mapDeleteOrThrow().keyOrThrow()), null);
                case QUEUE_PUSH -> new DecodedChange(null, queuePushFor(stateChange.queuePushOrThrow()));
                case QUEUE_POP -> new DecodedChange(null, null);
                default -> null;
            };
        } catch (IllegalStateException | NullPointerException e) {
            return null;
        }
    }

    private record DecodedChange(
            @Nullable Object key, @Nullable Object value) {}

    private static @Nullable StateChange parse(@NonNull final Bytes serialized) {
        try {
            return StateChange.PROTOBUF.parseStrict(serialized);
        } catch (ParseException e) {
            return null;
        }
    }

    private static boolean isOfCodecType(@NonNull final Codec<?> codec, @NonNull final Object object) {
        final var defaultInstance = codec.getDefaultInstance();
        return defaultInstance != null && defaultInstance.getClass().isInstance(object);
    }

    private static void failIf(final boolean condition) {
        if (condition) {
            throw new HandleException(ResponseCodeEnum.INVALID_ERRATA_STATE_CHANGE);
        }
    }
}
