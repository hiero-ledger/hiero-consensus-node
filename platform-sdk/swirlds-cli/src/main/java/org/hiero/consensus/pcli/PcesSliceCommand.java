// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.EnhancedKeyStoreLoader;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Console;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.node.NodeUtilities;
import org.hiero.consensus.pcli.graph.PcesGraphSlicer;
import picocli.CommandLine;

/**
 * CLI command to extract a section of an event graph from PCES files and transform it so it can be
 * replayed as if it were from genesis.
 *
 * <h2>Purpose</h2>
 * <p>When debugging or testing consensus behavior, it's often useful to take a specific section of
 * an existing event graph (e.g., events from a certain birth round or ngen onwards) and replay it in isolation.
 * However, events in the middle of a graph have birth rounds and parent references that assume
 * the existence of earlier events. This tool extracts a slice of the graph and transforms it to
 * be self-contained and replayable from genesis.
 *
 * <h2>Transformations Applied</h2>
 * <ul>
 *   <li><b>Filtering:</b> Events are filtered by birth round or ngen (with the ability to determine individual values per creator) to extract only the portion of the
 *       graph that is of interest.</li>
 *   <li><b>Birth round adjustment:</b> Birth rounds are modified (offset to start from 1) so the
 *       sliced graph appears to start from genesis.</li>
 *   <li><b>Re-hashing:</b> Since event properties are modified, all event hashes must be recalculated.</li>
 *   <li><b>Re-signing:</b> New cryptographic keys are generated and all events are re-signed,
 *       producing a valid, self-consistent event graph with new identities.</li>
 *   <li><b>Parent re-linking:</b> Parent references are updated to point to the new hashes of
 *       transformed parent events.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <p>The tool outputs:
 * <ul>
 *   <li>New PCES files containing the transformed events</li>
 *   <li>A roster JSON file with the new node identities</li>
 *   <li>PEM files containing the private keys for each node</li>
 * </ul>
 *
 * <p>This allows the sliced graph to be loaded into a test network for replay and analysis.
 */
@CommandLine.Command(
        name = "slice",
        mixinStandardHelpOptions = true,
        description = "Extract a section of an event graph from PCES files and transform it to be "
                + "replayable from genesis. Events are filtered by birth round or ngen for each creator, birth rounds are "
                + "adjusted, and all events are re-hashed and re-signed with newly generated keys.")
@SubcommandOf(PcesCommand.class)
public class PcesSliceCommand extends AbstractCommand {

    private static final String ROSTER_JSON_FILENAME = "roster.json";
    private static final String KEYS_SUBDIRECTORY = "keys";

    private Path inputDirectory;
    private Path outputDirectory;
    private int nodeCount;
    private Long genesisBirthRound;
    private Path snapshotPath;
    private boolean forceOverwrite;

    // Per-node filter values (populated in call() from the raw lists)
    private final Map<Long, Long> nodeMinBirthRound = new HashMap<>();
    private final Map<Long, Long> nodeMinNgen = new HashMap<>();

    // Raw per-node filter strings from CLI
    @CommandLine.Option(
            names = {"--node-min-birth-round"},
            split = ",",
            description = "Per-node minimum birth round filter. Format: nodeId:value,nodeId:value (e.g., 0:100,1:150). "
                    + "Events from the specified node with lower birth round are excluded.")
    private List<String> nodeMinBirthRoundRaw = new ArrayList<>();

    @CommandLine.Option(
            names = {"--node-min-ngen"},
            split = ",",
            description = "Per-node minimum ngen filter. Format: nodeId:value,nodeId:value (e.g., 0:150,1:200). "
                    + "Events from the specified node with lower ngen are excluded.")
    private List<String> nodeMinNgenRaw = new ArrayList<>();

    @CommandLine.Parameters(index = "0", description = "The input directory containing PCES files to slice.")
    private void setInputDirectory(final Path inputDirectory) {
        this.inputDirectory = dirMustExist(inputDirectory);
    }

    @CommandLine.Parameters(index = "1", description = "The output directory where sliced PCES files will be written.")
    private void setOutputDirectory(final Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @CommandLine.Option(
            names = {"-n", "--nodes"},
            description = "Number of nodes that generated the input PCES stream. "
                    + "The output will use the same number of nodes with newly generated keys.",
            required = true)
    private void setNodeCount(final int nodeCount) {
        if (nodeCount < 1) {
            throw buildParameterException("Node count must be at least 1");
        }
        this.nodeCount = nodeCount;
    }

    @CommandLine.Option(
            names = {"--genesis-birth-round"},
            description =
                    "Value by which all events' birth rounds are adjusted. Events with br lower or equals to this value receive a new br of 1. The rest it's actual BR value minus this value.")
    private void setGenesisBirthRound(final Long genesisBirthRound) {
        this.genesisBirthRound = genesisBirthRound;
    }

    @CommandLine.Option(
            names = {"--snapshot"},
            description = "Path to a consensus snapshot JSON file to initialize the consensus engine.")
    private void setSnapshotPath(final Path snapshotPath) {
        this.snapshotPath = fileMustExist(snapshotPath).toPath();
    }

    @CommandLine.Option(
            names = {"-f", "--force"},
            description = "Force overwrite of output directory without prompting for confirmation.")
    private void setForceOverwrite(final boolean forceOverwrite) {
        this.forceOverwrite = forceOverwrite;
    }

    /**
     * Parses a node filter string in the format "nodeId:value".
     *
     * @param nodeFilter the filter string to parse
     * @param optionName the name of the option (for error messages)
     * @return a record containing the parsed nodeId and value
     */
    @NonNull
    private NodeFilterValue parseNodeFilter(final @NonNull String nodeFilter, final @NonNull String optionName) {
        final String[] parts = nodeFilter.split(":");
        if (parts.length != 2) {
            throw buildParameterException(
                    optionName + " must be in format nodeId:value (e.g., 0:100), got: " + nodeFilter);
        }
        try {
            final long nodeId = Long.parseLong(parts[0].trim());
            final long value = Long.parseLong(parts[1].trim());
            if (nodeId < 0) {
                throw buildParameterException(optionName + " nodeId must be non-negative, got: " + nodeId);
            }
            return new NodeFilterValue(nodeId, value);
        } catch (final NumberFormatException e) {
            throw buildParameterException(
                    optionName + " must contain valid numbers in format nodeId:value, got: " + nodeFilter);
        }
    }

    /**
     * Record to hold parsed node filter values.
     */
    private record NodeFilterValue(long nodeId, long value) {}

    @Override
    public Integer call() throws Exception {
        // Parse per-node filters from raw CLI input
        for (final String filter : nodeMinBirthRoundRaw) {
            final var parsed = parseNodeFilter(filter, "--node-min-birth-round");
            nodeMinBirthRound.put(parsed.nodeId, parsed.value);
        }
        for (final String filter : nodeMinNgenRaw) {
            final var parsed = parseNodeFilter(filter, "--node-min-ngen");
            nodeMinNgen.put(parsed.nodeId, parsed.value);
        }

        // Handle output directory: warn and prompt if it exists and is not empty
        if (!prepareOutputDirectory()) {
            System.out.println("Operation cancelled by user.");
            return 1;
        }

        System.out.println("Slicing PCES files from: " + inputDirectory);
        System.out.println("Output directory: " + outputDirectory);
        System.out.println("Node count: " + nodeCount);

        if (genesisBirthRound != null) {
            System.out.println("Genesis birth round: " + genesisBirthRound);
        }
        if (!nodeMinBirthRound.isEmpty()) {
            System.out.println("Per-node min birth round filters:");
            nodeMinBirthRound.forEach((nodeId, value) -> System.out.println("  Node " + nodeId + ": " + value));
        }
        if (!nodeMinNgen.isEmpty()) {
            System.out.println("Per-node min ngen filters:");
            nodeMinNgen.forEach((nodeId, value) -> System.out.println("  Node " + nodeId + ": " + value));
        }

        // Parse consensus snapshot if provided
        final ConsensusSnapshot consensusSnapshot = parseConsensusSnapshot();
        if (consensusSnapshot != null) {
            System.out.println("Using consensus snapshot from: " + snapshotPath);
            System.out.println("Snapshot round: " + consensusSnapshot.round());
        } else {

            System.out.println("Using a genesys consensus snapshot");
        }

        // Generate node IDs and keys
        final List<NodeId> nodeIds =
                IntStream.range(0, nodeCount).mapToObj(NodeId::of).toList();
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(nodeIds);

        System.out.println("Generated keys for " + nodeCount + " nodes.");

        // Generate roster and write to JSON
        final Roster roster = generateRoster(keysAndCertsMap);
        writeRosterJson(roster);
        System.out.println("Wrote roster to: " + outputDirectory.resolve(ROSTER_JSON_FILENAME));

        // Write private keys and certificates to PEM files
        final Path keysDirectory = outputDirectory.resolve(KEYS_SUBDIRECTORY);
        writeKeysPem(keysAndCertsMap, keysDirectory);
        System.out.println("Wrote keys to: " + keysDirectory);

        // Build the slicer
        final PcesGraphSlicer slicer = PcesGraphSlicer.builder()
                .context(createDefaultPlatformContext())
                .keysAndCertsMap(keysAndCertsMap)
                .existingPcesFilesLocation(inputDirectory)
                .exportPcesFileLocation(outputDirectory)
                .graphEventFilter(this::filterEvent)
                .graphEventCoreModifier(e -> e.copyBuilder()
                        .birthRound(Long.max(e.birthRound() - (genesisBirthRound == null ? 0 : genesisBirthRound), 1))
                        .build())
                .consensusSnapshot(consensusSnapshot)
                .build();

        System.out.println("Starting slice operation...");
        slicer.slice();
        System.out.println("Slice operation completed successfully.");

        return 0;
    }

    /**
     * Filters events based on the configured per-node birth round and ngen criteria.
     * An event must pass both filters (if configured) to be included.
     *
     * @param event the event to filter
     * @return true if the event should be included, false otherwise
     */
    private boolean filterEvent(final @NonNull PlatformEvent event) {
        final long birthRound = event.getBirthRound();
        final long ngen = event.getNGen();
        final long creatorNodeId = event.getCreatorId().id();

        // Check per-node birth round filter
        final Long nodeMinBR = nodeMinBirthRound.get(creatorNodeId);
        if (nodeMinBR != null && birthRound < nodeMinBR) {
            return false;
        }

        // Check per-node ngen filter
        final Long nodeMinNG = nodeMinNgen.get(creatorNodeId);
        return nodeMinNG == null || ngen >= nodeMinNG;
    }

    /**
     * Prepares the output directory for writing.
     * If the directory exists and is not empty, prompts the user for confirmation before deleting it.
     * If the user declines or no console is available, returns false.
     *
     * @return true if the output directory is ready for writing, false if the operation should be cancelled
     * @throws IOException if an error occurs while checking or deleting the directory
     */
    private boolean prepareOutputDirectory() throws IOException {
        // Check if directory exists and is not empty
        if (Files.exists(outputDirectory) && isDirectoryNotEmpty(outputDirectory)) {
            if (!forceOverwrite) {
                System.out.println();
                System.out.println("WARNING: Output directory already exists and is not empty:");
                System.out.println("  " + outputDirectory.toAbsolutePath());
                System.out.println();
                System.out.println("All existing contents will be DELETED if you proceed.");
                System.out.println();

                if (!promptForConfirmation()) {
                    return false;
                }
            }

            // Delete the directory and its contents
            System.out.println("Deleting existing output directory...");
            FileUtils.deleteDirectory(outputDirectory);
        }

        // Create the output directory
        Files.createDirectories(outputDirectory);
        return true;
    }

    /**
     * Checks if a directory is not empty (contains at least one file or subdirectory).
     *
     * @param directory the directory to check
     * @return true if the directory is not empty, false otherwise
     * @throws IOException if an error occurs while listing directory contents
     */
    private boolean isDirectoryNotEmpty(final Path directory) throws IOException {
        try (final Stream<Path> entries = Files.list(directory)) {
            return entries.findFirst().isPresent();
        }
    }

    /**
     * Prompts the user for confirmation via the console.
     *
     * @return true if the user confirms (enters 'y' or 'Y'), false otherwise
     */
    private boolean promptForConfirmation() {
        final Console console = System.console();
        if (console == null) {
            System.out.println("No console available for confirmation. Use --force to skip confirmation.");
            return false;
        }

        System.out.print("Do you want to delete the existing directory and continue? (y/N): ");
        final String response = console.readLine();
        return response != null && response.trim().equalsIgnoreCase("y");
    }

    /**
     * Parses the consensus snapshot from the JSON file if a snapshot path was provided.
     *
     * @return the parsed consensus snapshot, or null if no snapshot path was provided
     * @throws IOException if an error occurs while reading the file
     * @throws ParseException if an error occurs while parsing the JSON
     */
    @Nullable
    private ConsensusSnapshot parseConsensusSnapshot() throws IOException, ParseException {
        if (snapshotPath == null) {
            return null;
        }
        try (final FileInputStream fis = new FileInputStream(snapshotPath.toFile())) {
            return ConsensusSnapshot.JSON.parse(new ReadableStreamingData(fis));
        }
    }

    /**
     * Writes the roster to a JSON file in the output directory.
     *
     * @param roster the roster to write
     * @throws IOException if an error occurs while writing the file
     */
    private void writeRosterJson(final @NonNull Roster roster) throws IOException {
        final Path rosterPath = outputDirectory.resolve(ROSTER_JSON_FILENAME);
        try (final FileOutputStream fos = new FileOutputStream(rosterPath.toFile())) {
            Roster.JSON.write(roster, new WritableStreamingData(fos));
        }
    }

    /**
     * Writes the private keys and certificates to PEM files in the specified keys directory.
     * Creates files following the standard naming convention:
     * <ul>
     *   <li>{@code s-private-nodeX.pem} - private signing key</li>
     *   <li>{@code s-public-nodeX.pem} - public signing certificate</li>
     * </ul>
     *
     * @param keysAndCertsMap map of node IDs to their keys and certificates
     * @param keysDirectory the directory where PEM files will be written
     * @throws IOException if an error occurs while writing the files
     * @throws CertificateEncodingException if an error occurs while encoding a certificate
     */
    private void writeKeysPem(
            final @NonNull Map<NodeId, KeysAndCerts> keysAndCertsMap, final @NonNull Path keysDirectory)
            throws IOException, CertificateEncodingException {
        Files.createDirectories(keysDirectory);

        for (final Map.Entry<NodeId, KeysAndCerts> entry : keysAndCertsMap.entrySet()) {
            final NodeId nodeId = entry.getKey();
            final KeysAndCerts keysAndCerts = entry.getValue();
            final String nodeName = NodeUtilities.formatNodeName(nodeId);

            // Write private key PEM file
            final Path privateKeyPath = keysDirectory.resolve(String.format("s-private-%s.pem", nodeName));
            EnhancedKeyStoreLoader.writePemFile(
                    true, privateKeyPath, keysAndCerts.sigKeyPair().getPrivate().getEncoded());

            // Write public certificate PEM file
            final Path publicCertPath = keysDirectory.resolve(String.format("s-public-%s.pem", nodeName));
            EnhancedKeyStoreLoader.writePemFile(
                    false, publicCertPath, keysAndCerts.sigCert().getEncoded());
        }
    }

    /**
     * Creates a default platform context for CLI operations.
     *
     * @return a new platform context
     */
    @NonNull
    public static PlatformContext createDefaultPlatformContext() {
        try {
            final Configuration configuration =
                    DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());
            return PlatformContext.create(configuration);
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException("Failed to create platform context", e);
        }
    }

    /**
     * Generates signers from the given keys and certificates map.
     *
     * @param keysAndCertsMap map of node IDs to their keys and certificates
     * @param toSigner        function to create a signer from keys and certificates
     * @param <S>             the signer type
     * @return map of node IDs to their signers
     */
    @NonNull
    public static <S extends Signer> Map<NodeId, S> generateSigners(
            @NonNull final Map<NodeId, KeysAndCerts> keysAndCertsMap,
            @NonNull final Function<KeysAndCerts, S> toSigner) {
        final Map<NodeId, S> signers = new HashMap<>();
        keysAndCertsMap.forEach((nodeId, keysAndCerts) -> signers.put(nodeId, toSigner.apply(keysAndCerts)));
        return signers;
    }

    /**
     * Creates a Roster from the given keys and certificates map.
     *
     * @param keysAndCertsMap map of node IDs to their keys and certificates
     * @return a roster with entries for all nodes
     */
    @NonNull
    private static Roster generateRoster(@NonNull final Map<NodeId, KeysAndCerts> keysAndCertsMap) {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (final Map.Entry<NodeId, KeysAndCerts> entry : keysAndCertsMap.entrySet()) {
            rosterEntries.add(createRosterEntry(entry.getKey(), entry.getValue()));
        }
        rosterEntries.sort(Comparator.comparingLong(RosterEntry::nodeId));
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    /**
     * Creates a roster entry for a single node.
     */
    @NonNull
    private static RosterEntry createRosterEntry(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        try {
            final long id = nodeId.id();
            final byte[] certificate = keysAndCerts.sigCert().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(500)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .ipAddressV4(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                            .port(50000 + (int) id)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Failed to encode certificate for node " + nodeId, e);
        }
    }
}
