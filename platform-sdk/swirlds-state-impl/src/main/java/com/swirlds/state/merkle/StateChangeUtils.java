// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoParserTools.readUint32;
import static com.swirlds.state.merkle.StateKeyUtils.kvKey;
import static com.swirlds.state.merkle.StateKeyUtils.queueKey;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.QueueState;
import com.swirlds.state.QueueState.QueueStateCodec;
import edu.umd.cs.findbugs.annotations.NonNull;

public class StateChangeUtils {

    /**
     * Apply state changes from the given serialized {@code StateChanges} protobuf message to the given
     * {@link MerkleNodeState}.
     *
     * @param state the MerkleNodeState to apply changes to
     * @param stateChangesBytes the serialized StateChanges protobuf message
     */
    public static void applyStateChanges(@NonNull MerkleNodeState state, @NonNull Bytes stateChangesBytes) {
        ReadableSequentialData input = stateChangesBytes.toReadableSequentialData();
        // -- PARSE LOOP StateChanges ---------------------------------------------
        while (input.hasRemaining()) {
            // Given the wire type and the field type, parse the field
            switch (input.readVarInt(false)) {
                case 10 /* type=2 [MESSAGE] field=1 [consensus_timestamp] */ -> {
                    final var messageLength = input.readVarInt(false);
                    // we do not care about the timestamp field, so skip
                    input.skip(messageLength);
                }
                case 18 /* type=2 [MESSAGE] field=2 [state_changes] */ -> {
                    // this is "repeated" field so we will get more than one call
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        final long endPosition = input.position() + messageLength;
                        processStateChange(state, input, endPosition);
                    }
                }
            }
        }
    }

    private static void processStateChange(
            @NonNull MerkleNodeState state, @NonNull ReadableSequentialData input, long endPosition) {
        int stateId = -1;
        while (input.position() < endPosition) {
            switch (input.readVarInt(false)) {
                case 8 /* type=0 [UINT32] field=1 [state_id] */ -> {
                    stateId = readUint32(input);
                }
                case 18 /* type=2 [MESSAGE] field=2 [state_add] */ -> {
                    // we do not care about the state_add field, so skip
                    final var messageLength = input.readVarInt(false);
                    input.skip(messageLength);
                }
                case 26 /* type=2 [MESSAGE] field=3 [state_remove] */ -> {
                    // we do not care about the state_remove field, so skip
                    final var messageLength = input.readVarInt(false);
                    input.skip(messageLength);
                }
                case 34 /* type=2 [MESSAGE] field=4 [singleton_update] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        // read a field number
                        input.readVarInt(false);
                        // we do not care which field it is as they are all wire type length encoded.
                        final var innerMessageLength = input.readVarInt(false);
                        // get the singleton state key
                        Bytes key = getStateKeyForSingleton(stateId);
                        // create value bytes
                        Bytes value = stateValueWrap(stateId, innerMessageLength, input);
                        // put into the virtual merkle map
                        state.putBytes(key, value);
                    }
                }
                case 42 /* type=2 [MESSAGE] field=5 [map_update] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        final long msgEndPosition = input.position() + messageLength;
                        Pair<Bytes, Bytes> bytesBytesPair = extractStateKeyAndValue(stateId, input, msgEndPosition);
                        state.putBytes(bytesBytesPair.left(), bytesBytesPair.right());
                    }
                }
                case 50 /* type=2 [MESSAGE] field=6 [map_delete] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        final long msgEndPosition = input.position() + messageLength;
                        state.remove(extractStateKey(stateId, input, msgEndPosition));
                    }
                }
                case 58 /* type=2 [MESSAGE] field=7 [queue_push] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        final long msgEndPosition = input.position() + messageLength;
                        // Read the current queue state
                        final Bytes queueStateKey = getStateKeyForSingleton(stateId);
                        final Bytes existingQueueStateBytes = state.getBytes(queueStateKey);
                        final QueueState queueState;
                        try {
                            queueState = existingQueueStateBytes != null
                                    ? QueueStateCodec.INSTANCE.parse(existingQueueStateBytes)
                                    : new QueueState(1, 1);
                        } catch (ParseException e) {
                            throw new RuntimeException("Failed to parse QueueState.", e);
                        }

                        final Bytes elementValue = extractQueueElement(stateId, input, msgEndPosition);
                        // Create a queue element key at the current tail position
                        final Bytes queueElementKey = queueKey(stateId, queueState.tail());
                        // Put element into virtual map
                        state.putBytes(queueElementKey, elementValue);
                        // Update queue state (increment tail)
                        Bytes newQueueStateBytes = QueueStateCodec.INSTANCE.toBytes(
                                new QueueState(queueState.head(), queueState.tail() + 1));
                        state.putBytes(queueStateKey, newQueueStateBytes);
                    }
                }
                case 66 /* type=2 [MESSAGE] field=8 [queue_pop] */ -> {
                    input.readVarInt(false);
                    // QueuePopChange has no fields, so messageLength will be 0, but we still need to process it
                    try {
                        // Read the current queue state
                        Bytes queueStateKey = getStateKeyForSingleton(stateId);
                        Bytes existingQueueStateBytes = state.getBytes(queueStateKey);
                        if (existingQueueStateBytes == null) {
                            throw new RuntimeException(
                                    "Cannot pop from queue - queue state not found for stateId: " + stateId);
                        }
                        QueueState queueState = QueueStateCodec.INSTANCE.parse(existingQueueStateBytes);
                        // Check if the queue is empty
                        if (queueState.head() >= queueState.tail()) {
                            throw new RuntimeException("Cannot pop from empty queue for stateId: " + stateId);
                        }
                        // Remove element at head position
                        Bytes queueElementKey = queueKey(stateId, queueState.head());
                        state.remove(queueElementKey);
                        // Update queue state (increment head)
                        Bytes newQueueStateBytes = QueueStateCodec.INSTANCE.toBytes(
                                new QueueState(queueState.head() + 1, queueState.tail()));
                        state.putBytes(queueStateKey, newQueueStateBytes);
                    } catch (ParseException e) {
                        throw new RuntimeException("Failed to parse QueueState.", e);
                    }
                }
            }
        }
    }

    /**
     * Take an input positioned at the beginning of a state value. Read the value and wrap it to be binary
     * compatible with the protobuf object com.hedera.hapi.platform.state.StateValue.
     *
     * @param stateId the state ID of the value
     * @param valueLength the length of the value in protobuf binary format
     * @param input the input positioned at the beginning of the value, ready to read
     * @return Bytes representing StateValue in protobuf binary format, containing the given value for state id
     */
    private static Bytes stateValueWrap(int stateId, int valueLength, ReadableSequentialData input) {
        // compute field tag
        final int tag = (stateId << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal();
        final int tagSize = ProtoWriterTools.sizeOfVarInt32(tag);
        // compute value length encoded size
        final int valueSize = ProtoWriterTools.sizeOfVarInt32(valueLength);
        // compute total size and allocate a buffer
        final int totalSize = tagSize + valueSize + valueLength;
        final byte[] buffer = new byte[totalSize];
        final BufferedData out = BufferedData.wrap(buffer);
        // write
        out.writeVarInt(tag, false);
        out.writeVarInt(valueLength, false);
        input.readBytes(out); // limit is set to the correct size already
        // return buffer as Bytes
        return Bytes.wrap(buffer);
    }

    /**
     * Extracts the state key and value from a serialized {@code MapUpdateChange} protobuf message.
     *
     * @param stateId the state ID
     * @param input the input positioned at the beginning of the MapUpdateChange message
     * @param endPosition the position in the input where the MapUpdateChange message ends
     * @return a Pair containing the state key and state value as Bytes objects
     */
    private static Pair<Bytes, Bytes> extractStateKeyAndValue(
            int stateId, ReadableSequentialData input, long endPosition) {
        return extractStateKeyAndValue(stateId, input, endPosition, true);
    }

    /**
     * Extracts the state key  from a serialized {@code MapUpdateChange} protobuf message.
     *
     * @param stateId the state ID
     * @param input the input positioned at the beginning of the MapUpdateChange message
     * @param endPosition the position in the input where the MapUpdateChange message ends
     * @return a Pair containing the state key and state value as Bytes objects
     */
    private static Bytes extractStateKey(int stateId, ReadableSequentialData input, long endPosition) {
        return extractStateKeyAndValue(stateId, input, endPosition, false).left();
    }

    private static Pair<Bytes, Bytes> extractStateKeyAndValue(
            int stateId, ReadableSequentialData input, long endPosition, boolean valueRequired) {
        // read map key and value contents as Bytes
        Bytes mapKeyAsStateKey = null;
        Bytes mapValueAsStateValue = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            switch (tag) {
                case 10 /* type=2 [MESSAGE] field=1 [key] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        // MapChangeKey has a single oneof field; extract the field number to determine type
                        final int mapChangeKeyFieldTag = input.readVarInt(false);
                        final var mapChangeKeyFieldMessageLength = input.readVarInt(false);
                        // Field number from tag (lower 3 bits are wire type)
                        int fieldNumber = mapChangeKeyFieldTag >>> 3;

                        // proto_bytes_key (field 6) and proto_string_key (field 7) are wrapper types
                        // that contain an inner message with the actual bytes/string at field 1
                        if (fieldNumber == 6 || fieldNumber == 7) {
                            // Wrapper type - read through inner tag and length to get actual bytes
                            input.readVarInt(false); // inner field tag
                            final int innerLength = input.readVarInt(false);
                            mapKeyAsStateKey = kvKey(stateId, input.readBytes(innerLength));
                        } else {
                            // Other key types (account_id_key at field 2, etc.) - read as-is
                            mapKeyAsStateKey = kvKey(stateId, input.readBytes(mapChangeKeyFieldMessageLength));
                        }
                    }
                }
                case 18 /* type=2 [MESSAGE] field=2 [value] */ -> {
                    final var messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        // MapChangeValue has a single oneof field; we do not care what field id it is
                        input.readVarInt(false);
                        final var mapChangeValueFieldMessageLength = input.readVarInt(false);
                        mapValueAsStateValue = stateValueWrap(stateId, mapChangeValueFieldMessageLength, input);
                    }
                }
                case 24 /* type=0 [BOOL] field=3 [identical] */ -> input.readVarInt(false); // not needed
            }
        }
        if (mapKeyAsStateKey == null || (mapValueAsStateValue == null && valueRequired)) {
            throw new RuntimeException("MapChangeKey or MapChangeValue missing");
        }

        return new Pair<>(mapKeyAsStateKey, mapValueAsStateValue);
    }

    /**
     * Extracts the queue element value from a serialized {@code QueuePushChange} protobuf message.
     *
     * @param stateId the state ID of the queue
     * @param input the input positioned at the beginning of the queue push change message
     * @param endPosition the position in input at which this message ends
     */
    private static Bytes extractQueueElement(int stateId, @NonNull ReadableSequentialData input, long endPosition) {
        // Parse the QueuePushChange to get the element value
        // QueuePushChange has a value oneof with options:
        //   field 1: proto_bytes_element (wrapper message with bytes field 1)
        //   field 2: proto_string_element (wrapper message with string field 1)
        //   field 3: transaction_receipt_entries_element (direct message)
        Bytes elementValue = null;
        while (input.position() < endPosition) {
            final int tag = input.readVarInt(false);
            final var messageLength = input.readVarInt(false);
            if (messageLength > 0) {
                switch (tag) {
                    case 10 /* proto_bytes_element */, 18 /* proto_string_element */ -> {
                        // These are wrapper messages with field 1 containing the actual value
                        final int innerTag = input.readVarInt(false);
                        final int innerLength = input.readVarInt(false);
                        elementValue = stateValueWrap(stateId, innerLength, input);
                    }
                    case 26 /* transaction_receipt_entries_element */ -> {
                        // This is a direct message - the whole message is the value
                        elementValue = stateValueWrap(stateId, messageLength, input);
                    }
                    default -> input.skip(messageLength);
                }
            }
        }
        if (elementValue == null) {
            throw new RuntimeException("No value found in QueuePushChange");
        }

        return elementValue;
    }
}
