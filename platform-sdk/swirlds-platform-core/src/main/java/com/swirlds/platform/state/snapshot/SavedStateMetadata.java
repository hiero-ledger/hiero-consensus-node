// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.CONSENSUS_TIMESTAMP;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.HASH;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.HASH_MNEMONIC;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.LEGACY_RUNNING_EVENT_HASH;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.LEGACY_RUNNING_EVENT_HASH_MNEMONIC;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.MINIMUM_GENERATION_NON_ANCIENT;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.NODE_ID;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.NUMBER_OF_CONSENSUS_EVENTS;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.ROUND;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SIGNING_NODES;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SIGNING_WEIGHT_SUM;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.SOFTWARE_VERSION;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.TOTAL_WEIGHT;
import static com.swirlds.platform.state.snapshot.SavedStateMetadataField.WALL_CLOCK_TIME;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * Metadata about a saved state. Fields in this record may be null if they are not present in the metadata file. All
 * fields in this record will be null if the metadata file is missing.
 *
 * @param round                          the round of the signed state, corresponds to
 *                                       {@link SavedStateMetadataField#ROUND}
 * @param hash                           the root hash of the state
 * @param hashMnemonic                   the root hash of the state in mnemonic form
 * @param numberOfConsensusEvents        the number of consensus events, starting from genesis, that have been handled
 *                                       to create this state, corresponds to
 *                                       {@link SavedStateMetadataField#NUMBER_OF_CONSENSUS_EVENTS}
 * @param consensusTimestamp             the consensus timestamp of this state, corresponds to
 *                                       {@link SavedStateMetadataField#CONSENSUS_TIMESTAMP}
 * @param legacyRunningEventHash         the legacy running event hash used by the consensus event stream, corresponds
 *                                       to {@link SavedStateMetadataField#LEGACY_RUNNING_EVENT_HASH}.
 * @param legacyRunningEventHashMnemonic the mnemonic for the {@link #legacyRunningEventHash}, corresponds to
 *                                       {@link SavedStateMetadataField#LEGACY_RUNNING_EVENT_HASH_MNEMONIC}.
 * @param minimumGenerationNonAncient    the minimum generation of non-ancient events after this state reached
 *                                       consensus, corresponds to
 *                                       {@link SavedStateMetadataField#MINIMUM_GENERATION_NON_ANCIENT}
 * @param softwareVersion                the application software version that created this state, corresponds to
 *                                       {@link SavedStateMetadataField#SOFTWARE_VERSION}
 * @param wallClockTime                  the wall clock time when this state was written to disk, corresponds to
 *                                       {@link SavedStateMetadataField#WALL_CLOCK_TIME}
 * @param nodeId                         the ID of the node that wrote this state to disk, corresponds to
 *                                       {@link SavedStateMetadataField#NODE_ID}
 * @param signingNodes                   a comma separated list of node IDs that signed this state, corresponds to
 *                                       {@link SavedStateMetadataField#SIGNING_NODES}
 * @param signingWeightSum               the sum of all signing nodes' weights, corresponds to
 *                                       {@link SavedStateMetadataField#SIGNING_WEIGHT_SUM}
 * @param totalWeight                    the total weight of all nodes in the network, corresponds to
 *                                       {@link SavedStateMetadataField#TOTAL_WEIGHT}
 */
public record SavedStateMetadata(
        long round,
        @NonNull Hash hash,
        @NonNull String hashMnemonic,
        long numberOfConsensusEvents,
        @NonNull Instant consensusTimestamp,
        @Nullable Hash legacyRunningEventHash,
        @Nullable String legacyRunningEventHashMnemonic,
        long minimumGenerationNonAncient,
        @NonNull String softwareVersion,
        @NonNull Instant wallClockTime,
        @NonNull NodeId nodeId,
        @NonNull List<NodeId> signingNodes,
        long signingWeightSum,
        long totalWeight) {

    // A note to engineers maintaining this code:
    //
    // It is safe to add new fields to this class, but all new
    // fields must be @Nullable and optional. After states
    // in production environments have been migrated and the
    // state files on disk have the new fields, then it is ok
    // to change the fields @NonNull/primitive and required.

    /**
     * The standard file name for the saved state metadata file.
     */
    public static final String FILE_NAME = "stateMetadata.txt";

    /**
     * Use this constant for the node ID if the thing writing the state is not a node.
     */
    public static final NodeId NO_NODE_ID = NodeId.of(Long.MAX_VALUE);

    private static final Logger logger = LogManager.getLogger(SavedStateMetadata.class);

    /**
     * Parse the saved state metadata from the given file.
     *
     * @param metadataFile the file to parse
     * @return the signed state metadata
     */
    public static SavedStateMetadata parse(final Path metadataFile) throws IOException {
        final Map<SavedStateMetadataField, String> data = parseStringMap(metadataFile);
        return new SavedStateMetadata(
                parsePrimitiveLong(data, ROUND),
                parseNonNullHash(data, HASH),
                parseNonNullString(data, HASH_MNEMONIC),
                parsePrimitiveLong(data, NUMBER_OF_CONSENSUS_EVENTS),
                parseNonNullInstant(data, CONSENSUS_TIMESTAMP),
                parseHash(data, LEGACY_RUNNING_EVENT_HASH),
                parseString(data, LEGACY_RUNNING_EVENT_HASH_MNEMONIC),
                parsePrimitiveLong(data, MINIMUM_GENERATION_NON_ANCIENT),
                parseNonNullString(data, SOFTWARE_VERSION),
                parseNonNullInstant(data, WALL_CLOCK_TIME),
                NodeId.of(parsePrimitiveLong(data, NODE_ID)),
                parseNodeIdList(data, SIGNING_NODES),
                parsePrimitiveLong(data, SIGNING_WEIGHT_SUM),
                parsePrimitiveLong(data, TOTAL_WEIGHT));
    }

    /**
     * Create a new saved state metadata object from the given signed state.
     *
     * @param signedState the signed state
     * @param selfId      the ID of the node that created the signed state
     * @param now         the current time
     * @param platformStateFacade  the facade to access the platform state
     * @return the signed state metadata
     */
    public static SavedStateMetadata create(
            @NonNull final SignedState signedState,
            @NonNull final NodeId selfId,
            @NonNull final Instant now,
            @NonNull final PlatformStateFacade platformStateFacade) {
        Objects.requireNonNull(signedState, "signedState must not be null");
        final State state = signedState.getState();
        Objects.requireNonNull(state.getHash(), "state must be hashed");
        Objects.requireNonNull(now, "now must not be null");

        final Roster roster = RosterRetriever.retrieveActiveOrGenesisRoster(state, platformStateFacade);

        final List<NodeId> signingNodes = signedState.getSigSet().getSigningNodes();
        Collections.sort(signingNodes);

        return new SavedStateMetadata(
                signedState.getRound(),
                state.getHash(),
                Mnemonics.generateMnemonic(state.getHash()),
                platformStateFacade.consensusSnapshotOf(state).nextConsensusNumber(),
                signedState.getConsensusTimestamp(),
                platformStateFacade.legacyRunningEventHashOf(state),
                Mnemonics.generateMnemonic(platformStateFacade.legacyRunningEventHashOf(state)),
                platformStateFacade.ancientThresholdOf(state),
                convertToString(platformStateFacade.creationSoftwareVersionOf(state)),
                now,
                selfId,
                signingNodes,
                signedState.getSigningWeight(),
                roster == null ? 0 : RosterUtils.computeTotalWeight(roster));
    }

    /**
     * Convert an object to a string, throw if the string has newlines.
     *
     * @param value the object to convert
     * @return the string representation of the object
     */
    @NonNull
    private static String convertToString(@Nullable final Object value) {
        final String string = value == null ? "null" : value.toString();

        if (string.contains("\n")) {
            throw new IllegalArgumentException("Value cannot contain newlines: " + value);
        }
        return string;
    }

    /**
     * Parse the key/value pairs written to disk. The inverse of {@link #buildStringMap()}.
     */
    @NonNull
    private static Map<SavedStateMetadataField, String> parseStringMap(@NonNull final Path metadataFile) {

        if (!Files.exists(metadataFile)) {
            // We must elegantly handle the case where the metadata file does not exist
            // until we have fully migrated all state snapshots in production environments.
            logger.warn(STARTUP.getMarker(), "Signed state does not have a metadata file at {}", metadataFile);
            return new EnumMap<>(SavedStateMetadataField.class);
        }

        try {
            final Map<SavedStateMetadataField, String> map = new EnumMap<>(SavedStateMetadataField.class);

            try (final BufferedReader reader = new BufferedReader(new FileReader(metadataFile.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {

                    final int colonIndex = line.indexOf(":");
                    if (colonIndex == -1) {
                        logger.warn(STARTUP.getMarker(), "Invalid line in metadata file: {}", line);
                        continue;
                    }

                    final String keyString = line.substring(0, colonIndex).strip();
                    final String valueString = line.substring(colonIndex + 1).strip();

                    try {
                        final SavedStateMetadataField key = SavedStateMetadataField.valueOf(keyString);
                        map.put(key, valueString);
                    } catch (final IllegalArgumentException e) {
                        logger.warn(STARTUP.getMarker(), "Unrecognized key in metadata file: {}", keyString);
                    }
                }
            }

            return map;
        } catch (final IOException e) {
            logger.warn(STARTUP.getMarker(), "Failed to parse signed state metadata file: {}", metadataFile, e);
            return new EnumMap<>(SavedStateMetadataField.class);
        }
    }

    /**
     * Write a log message for a missing field.
     *
     * @param field the missing field
     */
    private static void logMissingField(@NonNull final SavedStateMetadataField field) {
        logger.warn(STARTUP.getMarker(), "Signed state metadata file is missing field: {}", field);
    }

    /**
     * Write a log message for an invalid field.
     *
     * @param field the invalid field
     * @param value the invalid value
     * @param e     the exception
     */
    private static void logInvalidField(
            @NonNull final SavedStateMetadataField field, @NonNull final String value, @NonNull final Exception e) {
        logger.warn(
                STARTUP.getMarker(), "Signed state metadata file has invalid value for field {}: {}", field, value, e);
    }

    /**
     * Throw an exception for a missing required field.
     *
     * @param field the missing field
     */
    private static void throwMissingRequiredField(@NonNull final SavedStateMetadataField field) throws IOException {
        Objects.requireNonNull(field);
        throw new IOException("Signed state metadata file is missing required field: " + field);
    }

    /**
     * Throw an exception for an invalid required field.
     *
     * @param field the invalid field
     * @param value the invalid value
     * @param e     the exception
     */
    private static void throwInvalidRequiredField(
            @NonNull final SavedStateMetadataField field, @NonNull final String value, @NonNull final Exception e)
            throws IOException {

        Objects.requireNonNull(field);
        Objects.requireNonNull(value);
        Objects.requireNonNull(e);

        throw new IOException(
                "Signed state metadata file has an invalid value for required field %s: %s ".formatted(field, value),
                e);
    }

    // This unused method is intentionally not deleted, in case we ever decide to add a new long to this file.

    /**
     * Attempt to parse a long from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed long, or null if the field is not present or the value is not a valid long
     */
    @Nullable
    private static Long parseLong(
            final Map<SavedStateMetadataField, String> data, final SavedStateMetadataField field) {

        if (!data.containsKey(field)) {
            logMissingField(field);
            return null;
        }

        final String value = data.get(field);
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            logInvalidField(field, value, e);
            return null;
        }
    }

    /**
     * Attempt to parse a primitive long from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed long, or null if the field is not present or the value is not a valid long
     */
    private static long parsePrimitiveLong(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field)
            throws IOException {

        Objects.requireNonNull(field);

        if (!data.containsKey(field)) {
            throwMissingRequiredField(field);
            return Long.MIN_VALUE;
        }

        final String value = data.get(field);
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throwInvalidRequiredField(field, value, e);
            return Long.MIN_VALUE;
        }
    }

    /**
     * Attempt to parse a string from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed string, or null if the field is not present or the value is not a valid hash
     */
    @Nullable
    private static String parseString(
            final Map<SavedStateMetadataField, String> data, final SavedStateMetadataField field) {

        if (!data.containsKey(field)) {
            logMissingField(field);
            return null;
        }

        return data.get(field);
    }

    /**
     * Attempt to parse a string from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed string, or null if the field is not present or the value is not a valid hash
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    private static String parseNonNullString(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field)
            throws IOException {

        Objects.requireNonNull(field);

        if (!data.containsKey(field)) {
            throwMissingRequiredField(field);
            return "we will never reach this point";
        }

        return data.get(field);
    }

    // This unused method is intentionally not deleted, in case we ever decide to add a new instant to this file.

    /**
     * Attempt to parse an instant from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed instant, or null if the field is not present or the value is not a valid instant
     */
    @Nullable
    private static Instant parseInstant(
            final Map<SavedStateMetadataField, String> data, final SavedStateMetadataField field) {

        if (!data.containsKey(field)) {
            logMissingField(field);
            return null;
        }

        final String value = data.get(field);
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            logInvalidField(field, value, e);
            return null;
        }
    }

    /**
     * Attempt to parse a primitive instant from the data map. Throws if field can't be found or if the value is not a
     * valid instant.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed instant
     */
    @NonNull
    private static Instant parseNonNullInstant(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field)
            throws IOException {

        Objects.requireNonNull(field);

        if (!data.containsKey(field)) {
            throwMissingRequiredField(field);
            return Instant.MIN;
        }

        final String value = data.get(field);
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throwInvalidRequiredField(field, value, e);
            return Instant.MIN;
        }
    }

    /**
     * Attempt to parse a list of NodeIds from the data map.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed list of longs, or null if the field is not present or the value is not a valid list of longs
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    private static List<NodeId> parseNodeIdList(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field)
            throws IOException {

        if (!data.containsKey(field)) {
            throwMissingRequiredField(field);
            return null;
        }

        final String value = data.get(field);
        final String[] parts = value.split(",");
        final List<NodeId> list = new ArrayList<>();

        if (parts.length == 1 && parts[0].isBlank()) {
            // List is empty.
            return list;
        }

        for (final String part : parts) {
            try {
                list.add(NodeId.of(Long.parseLong(part.strip())));
            } catch (final NumberFormatException e) {
                throwInvalidRequiredField(field, value, e);
                return null;
            }
        }
        return list;
    }

    /**
     * Attempt to parse a hash from the data map. Supports null.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed hash, or null if the field is not present or the value is not a valid hash
     */
    @Nullable
    private static Hash parseHash(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field) {

        if (!data.containsKey(field)) {
            logMissingField(field);
            return null;
        }

        final String value = data.get(field);

        if (value.equalsIgnoreCase("null")) {
            return null;
        }

        try {
            return new Hash(unhex(value));
        } catch (final IllegalArgumentException e) {
            logInvalidField(field, value, e);
            return null;
        }
    }

    /**
     * Attempt to parse a hash from the data map. Throws if field can't be found or if the value is not a valid hash.
     *
     * @param data  the data map
     * @param field the field to parse
     * @return the parsed hash, or null if the field is not present or the value is not a valid hash
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    private static Hash parseNonNullHash(
            @NonNull final Map<SavedStateMetadataField, String> data, @NonNull final SavedStateMetadataField field)
            throws IOException {

        if (!data.containsKey(field)) {
            throwMissingRequiredField(field);
            return null;
        }

        final String value = data.get(field);

        try {
            return new Hash(unhex(value));
        } catch (final IllegalArgumentException e) {
            throwInvalidRequiredField(field, value, e);
            return null;
        }
    }

    /**
     * Convert an object to a string, replacing newlines with "//". If the object is null, return "null".
     *
     * @param value the object to convert
     * @return the string representation of the object
     */
    @NonNull
    private static String toStringWithoutNewlines(@Nullable final Object value) {
        return value == null ? "null" : value.toString().replace("\n", "//");
    }

    /**
     * Put a value into the data map, throwing if the value is null.
     */
    private static void putRequireNonNull(
            @NonNull final Map<SavedStateMetadataField, String> map,
            @NonNull final SavedStateMetadataField field,
            @NonNull final Object value) {
        Objects.requireNonNull(field);
        Objects.requireNonNull(value);
        map.put(field, toStringWithoutNewlines(value));
    }

    /**
     * Put a value into the data map, using the string "null" to represent a null value. This should only be used for
     * fields where the parser is capable of interpreting "null" as a value.
     *
     * @param map   the map to put the value into
     * @param field the field to put the value into
     * @param value the value to put into the map
     */
    @SuppressWarnings("SameParameterValue")
    private static void putPossiblyNullObject(
            @NonNull final Map<SavedStateMetadataField, String> map,
            @NonNull final SavedStateMetadataField field,
            @Nullable final Object value) {

        Objects.requireNonNull(field);

        map.put(field, toStringWithoutNewlines(value));
    }

    /**
     * Build a map of key/value pairs to be written to disk.
     */
    private Map<SavedStateMetadataField, String> buildStringMap() {
        final Map<SavedStateMetadataField, String> map = new EnumMap<>(SavedStateMetadataField.class);

        putRequireNonNull(map, ROUND, round);
        putRequireNonNull(map, HASH, hash);
        putRequireNonNull(map, HASH_MNEMONIC, hashMnemonic);
        putRequireNonNull(map, NUMBER_OF_CONSENSUS_EVENTS, numberOfConsensusEvents);
        putRequireNonNull(map, CONSENSUS_TIMESTAMP, consensusTimestamp);
        putRequireNonNull(map, LEGACY_RUNNING_EVENT_HASH, legacyRunningEventHash);
        putRequireNonNull(map, LEGACY_RUNNING_EVENT_HASH_MNEMONIC, legacyRunningEventHashMnemonic);
        putRequireNonNull(map, MINIMUM_GENERATION_NON_ANCIENT, minimumGenerationNonAncient);
        putRequireNonNull(map, SOFTWARE_VERSION, softwareVersion);
        putRequireNonNull(map, WALL_CLOCK_TIME, wallClockTime);
        putRequireNonNull(map, NODE_ID, nodeId);
        putRequireNonNull(map, SIGNING_NODES, formattedList(signingNodes.iterator()));
        putRequireNonNull(map, SIGNING_WEIGHT_SUM, signingWeightSum);
        putRequireNonNull(map, TOTAL_WEIGHT, totalWeight);

        return map;
    }

    /**
     * Write the saved state metadata to the given file.
     *
     * @param metadataFile the file to write to
     * @throws IOException if an error occurs while writing
     */
    public void write(final Path metadataFile) throws IOException {

        final Map<SavedStateMetadataField, String> map = buildStringMap();
        final List<SavedStateMetadataField> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);

        final TextTable table = new TextTable().setBordersEnabled(false);

        for (final SavedStateMetadataField key : keys) {
            final String keyString = key.toString() + ": ";
            final String valueString = map.get(key);
            table.addRow(keyString, valueString);
        }

        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(metadataFile.toFile()))) {
            writer.write(table.render());
        }
    }
}
