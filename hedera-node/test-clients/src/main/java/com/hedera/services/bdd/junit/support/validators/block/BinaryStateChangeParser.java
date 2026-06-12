// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

/**
 * Parses binary protobuf {@link StateChanges} and applies mutations through the {@link BinaryState} API.
 *
 * <p>The parser manually reads the protobuf wire format to extract state change operations
 * (singleton updates, map updates/deletes, queue pushes/pops) and delegates them to the corresponding
 * {@link BinaryState} methods, which handle key composition, value wrapping, and queue state management
 * internally.
 */
final class BinaryStateChangeParser {
    private BinaryStateChangeParser() {
        throw new UnsupportedOperationException("Utility class");
    }

    static void applyStateChanges(@NonNull final BinaryState binaryState, @NonNull final Bytes stateChangesBytes) {
        applyStateChanges(binaryState, stateChangesBytes, null);
    }

    static void applyStateChanges(
            @NonNull final BinaryState binaryState,
            @NonNull final Bytes stateChangesBytes,
            @Nullable final BinaryStateChangeSummary stateChangesSummary) {
        final ReadableSequentialData input = stateChangesBytes.toReadableSequentialData();
        while (input.hasRemaining()) {
            final int tag = input.readVarInt(false);
            switch (tag) {
                // consensus_timestamp: field 1, message => (1 << 3) | 2 = 10
                case 10 -> skipMessage(input);
                // state_changes:       field 2, message => (2 << 3) | 2 = 18
                case 18 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        final long endPosition = input.position() + messageLength;
                        processStateChange(binaryState, input, endPosition, stateChangesSummary);
                    }
                }
                default -> skipField(input, tag);
            }
        }
    }

    private static void processStateChange(
            @NonNull final BinaryState binaryState,
            @NonNull final ReadableSequentialData input,
            final long endPosition,
            @Nullable final BinaryStateChangeSummary stateChangesSummary) {
        int stateId = -1;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            switch (tag) {
                // state_id:         field 1, uint32 varint => (1 << 3) | 0 = 8
                case 8 -> stateId = ProtoParserTools.readUint32(input);
                // state_add:        field 2, message       => (2 << 3) | 2 = 18
                // state_remove:     field 3, message       => (3 << 3) | 2 = 26
                case 18, 26 -> skipMessage(input);
                // singleton_update: field 4, message       => (4 << 3) | 2 = 34
                case 34 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        processSingletonUpdateChange(
                                binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        if (stateChangesSummary != null) {
                            stateChangesSummary.countSingletonPut(stateId);
                        }
                    }
                }
                // map_update:       field 5, message       => (5 << 3) | 2 = 42
                case 42 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        processMapUpdateChange(
                                binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        if (stateChangesSummary != null) {
                            stateChangesSummary.countMapUpdate(stateId);
                        }
                    }
                }
                // map_delete:       field 6, message       => (6 << 3) | 2 = 50
                case 50 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        processMapDeleteChange(
                                binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        if (stateChangesSummary != null) {
                            stateChangesSummary.countMapDelete(stateId);
                        }
                    }
                }
                // queue_push:       field 7, message       => (7 << 3) | 2 = 58
                case 58 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        processQueuePushChange(
                                binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        if (stateChangesSummary != null) {
                            stateChangesSummary.countQueuePush(stateId);
                        }
                    }
                }
                // queue_pop:        field 8, message       => (8 << 3) | 2 = 66
                case 66 -> {
                    skipMessage(input);
                    processQueuePopChange(binaryState, requireStateId(stateId));
                    if (stateChangesSummary != null) {
                        stateChangesSummary.countQueuePop(stateId);
                    }
                }
                default -> skipField(input, tag);
            }
        }
    }

    private static void processSingletonUpdateChange(
            @NonNull final BinaryState binaryState,
            final int stateId,
            @NonNull final ReadableSequentialData input,
            final long endPosition) {
        final Bytes rawValue = readOneOfPayload(input, endPosition, "SingletonUpdateChange");
        binaryState.updateSingleton(stateId, rawValue);
    }

    private static void processMapUpdateChange(
            @NonNull final BinaryState binaryState,
            final int stateId,
            @NonNull final ReadableSequentialData input,
            final long endPosition) {
        Bytes rawKey = null;
        Bytes rawValue = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            switch (tag) {
                // key:   field 1, message => (1 << 3) | 2 = 10
                case 10 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        rawKey = readMapKeyPayload(input, input.position() + messageLength);
                    }
                }
                // value: field 2, message => (2 << 3) | 2 = 18
                case 18 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        rawValue = readOneOfPayload(input, input.position() + messageLength, "MapChangeValue");
                    }
                }
                default -> skipField(input, tag);
            }
        }
        if (rawKey == null || rawValue == null) {
            throw new IllegalStateException("MapChangeKey or MapChangeValue missing");
        }
        binaryState.updateKv(stateId, rawKey, rawValue);
    }

    private static void processMapDeleteChange(
            @NonNull final BinaryState binaryState,
            final int stateId,
            @NonNull final ReadableSequentialData input,
            final long endPosition) {
        Bytes rawKey = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            switch (tag) {
                // key: field 1, message => (1 << 3) | 2 = 10
                case 10 -> {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        rawKey = readMapKeyPayload(input, input.position() + messageLength);
                    }
                }
                default -> skipField(input, tag);
            }
        }
        if (rawKey == null) {
            throw new IllegalStateException("MapChangeKey missing in MapDeleteChange");
        }
        binaryState.removeKv(stateId, rawKey);
    }

    private static void processQueuePushChange(
            @NonNull final BinaryState binaryState,
            final int stateId,
            @NonNull final ReadableSequentialData input,
            final long endPosition) {
        final Bytes rawElement = readOneOfPayload(input, endPosition, "QueuePushChange");
        binaryState.pushQueue(stateId, rawElement);
    }

    private static void processQueuePopChange(@NonNull final BinaryState binaryState, final int stateId) {
        binaryState.popQueue(stateId);
    }

    private static Bytes readOneOfPayload(
            @NonNull final ReadableSequentialData input, final long endPosition, @NonNull final String description) {
        Bytes payload = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
            if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                final int length = input.readVarInt(false);
                payload = input.readBytes(length);
            } else {
                skipField(input, wireType);
            }
        }
        if (payload == null) {
            throw new IllegalStateException(description + " payload missing");
        }
        return payload;
    }

    /**
     * Most block-stream key payloads are already byte-compatible with the state key bytes stored in the VirtualMap.
     * Token relationship keys are the important exception: block stream uses TokenAssociation while state stores
     * EntityIDPair, whose field ordering is different.
     */
    private static Bytes readMapKeyPayload(@NonNull final ReadableSequentialData input, final long endPosition) {
        Bytes payload = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
            if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                final int length = input.readVarInt(false);
                payload = input.readBytes(length);
            } else {
                skipField(input, wireType);
            }
        }
        if (payload == null) {
            throw new IllegalStateException("MapChangeKey payload missing");
        }
        return payload;
    }

    private static int requireStateId(final int stateId) {
        if (stateId < 0) {
            throw new IllegalStateException("StateChange missing state_id");
        }
        return stateId;
    }

    private static void skipField(@NonNull final ReadableSequentialData input, final int tag) {
        skipField(input, ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK));
    }

    private static void skipField(@NonNull final ReadableSequentialData input, @NonNull final ProtoConstants wireType) {
        try {
            ProtoParserTools.skipField(input, wireType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to skip protobuf field with wire type " + wireType, e);
        }
    }

    private static void skipMessage(@NonNull final ReadableSequentialData input) {
        final int messageLength = input.readVarInt(false);
        input.skip(messageLength);
    }
}
