// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.common.merkle.utility.MerkleUtils.rehashTree;
import static com.swirlds.platform.cli.utils.HederaUtils.SWIRLD_NAME;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadLatestState;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistry;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistryWithConfiguration;
import static com.swirlds.virtualmap.constructable.ConstructableUtils.registerVirtualMapConstructables;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.base.time.Time;
import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.platform.cli.utils.HederaUtils;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.state.SavedStateUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.stream.Collectors;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterDiff;
import org.hiero.consensus.roster.RosterUtils;
import picocli.CommandLine;

@CommandLine.Command(
        name = "crystal-transplant",
        mixinStandardHelpOptions = true,
        description = "Cast a crystallization spell preparing a node to load a transplanted state")
@SubcommandOf(PlatformCli.class)
public class CrystalTransplantCommand extends AbstractCommand {
    /** The return code for a successful operation. */
    private static final int RETURN_CODE_SUCCESS = 0;
    /** The return code when the user does not confirm the prompt */
    private static final int RETURN_CODE_PROMPT_NO = 1;
    /** The return code when for an error */
    private static final int RETURN_CODE_ERROR = -1;

    /** Config Key for version */
    private static final String CONFIG_KEY = "hedera.config.version";
    /** Application properties file name */
    private static final String CONFIG_LOCATION = "data/config";

    private static final String APPLICATION_PROPERTIES_FILE_NAME = "application.properties";
    /** Target location for network-override file */
    public static final String OVERRIDE_NETWORK_FILE_NAME = "override-network.json";

    /** The path to the state to prepare for transplant. */
    private Path sourceStatePath;
    /** Target node id. */
    private NodeId selfId;
    /** The path to the Override network file. */
    private Path networkOverrideFile;

    @CommandLine.Option(
            names = {"-ac", "--auto-confirm"},
            description = "Automatically confirm the operation without prompting")
    @SuppressWarnings("unused") // used by picocli
    private boolean autoConfirm;

    @CommandLine.Option(
            names = {"-bv", "--bump-version"},
            description = "Bump application.properties version up")
    @SuppressWarnings("unused") // used by picocli
    private boolean bumpVersion = true;

    private PlatformContext platformContext;
    private Roster overrideRoster;
    private Path targetNodePath = Paths.get("");

    private SavedStateMetadata stateMetadata;
    private Path targetStateDir;

    /**
     * Set the path to state to prepare for transplant.
     */
    @CommandLine.Option(
            names = {"-s", "--sourceStatePath"},
            required = true,
            description = "The path to the state to load")
    @SuppressWarnings("unused") // used by picocli
    private void setSourceStatePath(final Path sourceStatePath) {
        this.sourceStatePath = pathMustExist(sourceStatePath.toAbsolutePath());
    }

    /**
     * Set the path to state to prepare for transplant.
     */
    @CommandLine.Option(
            names = {"-t", "--targetNodePath"},
            description = "The path to target node directory")
    @SuppressWarnings("unused") // used by picocli
    private void setTargetNodePath(final Path targetNodePath) {
        this.targetNodePath = pathMustExist(targetNodePath.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-o", "--networkOverride"},
            description = "The path to the network override file")
    @SuppressWarnings("unused") // used by picocli
    private void setNetworkOverrideFile(final Path networkOverrideFile) {
        this.networkOverrideFile = pathMustExist(networkOverrideFile.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-i", "--selfId"},
            required = true,
            description = "The ID of the target node")
    @SuppressWarnings("unused") // used by picocli
    private void setSelfId(final long selfId) {
        this.selfId = NodeId.of(selfId);
    }

    /**
     * This method is called after command line input is parsed.
     *
     * @return return code of the program
     */
    @Override
    public Integer call() throws IOException {
        requestConfirmation(String.format(
                "Start migration process for selfId:%s "
                        + "using networkOverride:%s"
                        + " sourceStatePath:%s "
                        + "and targetNodePath:%s"
                        + " bumpVersion:%s",
                selfId,
                networkOverrideFile != null ? networkOverrideFile.toAbsolutePath() : "<NONE>",
                sourceStatePath.toAbsolutePath(),
                targetNodePath.toAbsolutePath(),
                bumpVersion));
        final Path configtxtFile = targetNodePath.resolve("config.txt");
        if (!Files.exists(configtxtFile)) {
            System.err.println("Could not find config file: " + configtxtFile);
        }
        final Path settingsTxtFile = targetNodePath.resolve(CONFIG_LOCATION).resolve("settings.txt");
        if (!Files.exists(settingsTxtFile)) {
            System.err.println("Could not find config file: " + settingsTxtFile);
            System.exit(RETURN_CODE_ERROR);
        }

        final Configuration configuration = ConfigurationBuilder.create()
                .withSource(new LegacyFileConfigSource(configtxtFile))
                .withSource(new LegacyFileConfigSource(settingsTxtFile))
                .autoDiscoverExtensions()
                .build();

        this.platformContext = PlatformContext.create(
                configuration,
                Time.getCurrent(),
                new NoOpMetrics(),
                FileSystemManager.create(configuration),
                new SimpleRecycleBin(),
                MerkleCryptographyFactory.create(configuration));

        final PcesConfig pcesConfig = configuration.getConfigData(PcesConfig.class);

        final StateInformation sourceStateInfo = loadSourceState(configuration);

        if (networkOverrideFile != null) {
            validateOverrideNetworkJson();

            printRosterDiffAndGetConfirmation(sourceStateInfo);

            copyNetworkOverrideFileToCorrectDirectory();
        }

        copyStateFilesToCorrectDirectory(sourceStateInfo.fileInfo().getDirectory());

        truncatePCESFilesIfNotAFreezeState();

        final Path sourcePcesDir = this.targetStateDir.resolve(pcesConfig.databaseDirectory());
        final Path targetPcesDir = targetNodePath
                .resolve(configuration.getConfigData(StateCommonConfig.class).savedStateDirectory())
                .resolve(pcesConfig.databaseDirectory())
                .resolve(Long.toString(selfId.id()));
        copyPCESFilesToCorrectDirectory(sourcePcesDir, targetPcesDir);

        performConfigBump();

        return RETURN_CODE_SUCCESS;
    }

    private void validateOverrideNetworkJson() {
        final Roster roster = loadRosterFrom(networkOverrideFile);
        if (roster == null) {
            System.out.printf("Unable to load network override file %s%n", networkOverrideFile);
        }
        this.overrideRoster = roster;
    }

    private StateInformation loadSourceState(final Configuration configuration) {
        setupConstructableRegistry();
        try {
            setupConstructableRegistryWithConfiguration(platformContext.getConfiguration());
            registerVirtualMapConstructables(platformContext.getConfiguration());
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();

        final SwirldMain<? extends MerkleNodeState> appMain =
                HederaUtils.createHederaAppMain(platformContext, platformStateFacade);
        final List<SavedStateInfo> savedStateFiles = SignedStateFilePath.getSavedStateFiles(sourceStatePath);

        if (savedStateFiles.isEmpty()) {
            System.out.printf("No state files found on %s %n", sourceStatePath);
            System.exit(RETURN_CODE_ERROR);
        }

        try (final var state = loadLatestState(
                new SimpleRecycleBin(),
                appMain.getSemanticVersion(),
                savedStateFiles,
                v -> {
                    try {
                        return appMain.stateRootFromVirtualMap().apply(v);
                    } catch (UnsupportedOperationException e) {
                        // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19003
                        return appMain.newStateRoot();
                    }
                },
                platformStateFacade,
                platformContext)) {
            final Hash newHash = rehashTree(
                    platformContext.getMerkleCryptography(),
                    state.get().getState().getRoot());

            final StateCommonConfig stateConfig = configuration.getConfigData(StateCommonConfig.class);
            this.targetStateDir = new SignedStateFilePath(
                            new StateCommonConfig(targetNodePath.resolve(stateConfig.savedStateDirectory())))
                    .getSignedStateDirectory(
                            configuration.getValue("state.mainClassNameOverride"),
                            selfId,
                            SWIRLD_NAME,
                            state.get().getRound());

            return new StateInformation(
                    state.get().getRound(), state.get().getRoster(), newHash, savedStateFiles.getFirst());
        }
    }

    private void printRosterDiffAndGetConfirmation(final StateInformation sourceStateInfo) {
        System.out.println(
                RosterDiff.report(sourceStateInfo.roster, this.overrideRoster).print());
        requestConfirmation(String.format(
                "The state trying to migrate is on round: %d and has hash: %s",
                sourceStateInfo.round, sourceStateInfo.hash));
    }

    private void copyNetworkOverrideFileToCorrectDirectory() {
        // The configurations to assemble the DATA_CONFIG_OVERRIDE_NETWORK_JSON live in services, so can't be used here.
        // Hardcoded for now.
        final Path networkOverrideFile = targetNodePath.resolve(CONFIG_LOCATION).resolve(OVERRIDE_NETWORK_FILE_NAME);
        requestConfirmation("Copy network override file to location: " + networkOverrideFile);
        try {
            if (!networkOverrideFile.toFile().exists()) {
                Files.createDirectories(networkOverrideFile);
            }
            Files.copy(this.networkOverrideFile, networkOverrideFile, REPLACE_EXISTING);
        } catch (final IOException e) {
            System.err.printf("Unable to copy network override file for self-id %s. %s %n", selfId, e);
            System.exit(RETURN_CODE_ERROR);
        }
    }

    private void copyStateFilesToCorrectDirectory(final Path sourceDir) {
        requestConfirmation(
                String.format("Copy state files from source dir: %s to target: %s", sourceDir, targetStateDir));
        final PcesConfig pcesConfig = platformContext.getConfiguration().getConfigData(PcesConfig.class);
        try {
            FileUtils.deleteDirectory(targetStateDir.getParent());
            FileUtils.copyDirectory(sourceDir, targetStateDir);
            try (final var stream = Files.list(targetStateDir.resolve(pcesConfig.databaseDirectory()))) {
                final var list = stream.filter(Files::isDirectory).collect(Collectors.toCollection(ArrayList::new));

                // Since we don't know if the source state is going to belong to the actual node
                // and the folder is organized with the node id in its structure, we need to determine if the id of the
                // source folder
                // is the same as the target node, or if not, rename that folder to match the target node's id.
                // From all subfolders (most possibly one but it can be more than one if we run more than 1 node)
                // take the one that belongs to the target node, or any other if there is none matching the target
                // node's id.
                final Path any = list.stream()
                        .filter(file -> file.getFileName().toFile().getName().equals(selfId.toString()))
                        .findAny()
                        .orElse(list.getFirst());
                list.remove(any);
                // delete all other siblings in the folder that is not the selected one
                list.forEach(f -> {
                    try {
                        FileUtils.deleteDirectory(f);
                    } catch (final IOException e) {
                        System.exit(RETURN_CODE_ERROR);
                    }
                });
                // rename the folder to match the node id's name if it is not already matching.
                if (!any.getFileName().toFile().getName().equals(selfId.toString())) {
                    FileUtils.rename(any, selfId.toString());
                }
            }

        } catch (final IOException e) {
            System.err.printf("Unable to move state files from:%s to:%s. %s %n", sourceDir, targetStateDir, e);
            System.exit(RETURN_CODE_ERROR);
        }
    }

    /**
     * If the state is not a freeze state, this method truncates the PCES files by removing events with future birth
     * rounds to make the state look like a freeze state
     */
    private void truncatePCESFilesIfNotAFreezeState() throws IOException {
        stateMetadata = SavedStateMetadata.parse(targetStateDir.resolve(SavedStateMetadata.FILE_NAME));
        if (stateMetadata.freezeState() == null || !stateMetadata.freezeState()) {
            requestConfirmation("Truncate PCES files");
            final int discardedEventCount = SavedStateUtils.prepareStateForTransplant(targetStateDir, platformContext);
            System.out.printf(
                    "PCES file truncation complete. %d events were discarded due to being from a future round.%n",
                    discardedEventCount);
        } else {
            System.out.printf("The state is a freeze state, no truncation is needed.%n");
        }
        // given that the tool is using the file writer it changes the pces snapshot directory structure, it doesn't
        // seem ideal
        // but also it doesn't create any problems as the pces files in the snapshot-dir are read from another folder.
        // In any case it makes it easier to move the pces files to the correct location given that the structure
        // resembles
        // what the reader needs
    }

    private void copyPCESFilesToCorrectDirectory(final Path sourcePcesDir, final Path targetPcesDir) {
        requestConfirmation("Copy PCES files to correct directory");
        try {
            FileUtils.deleteDirectory(targetPcesDir);
            FileUtils.copyDirectory(sourcePcesDir, targetPcesDir);
        } catch (final IOException e) {
            System.err.printf("Unable to move PCES files from:%s to:%s. %s %n", sourcePcesDir, targetPcesDir, e);
            System.exit(RETURN_CODE_PROMPT_NO);
        }
    }

    /**
     * Updates the application properties file by increasing the patch version of the configuration key's semantic
     * version, if it exists. If the key is not found, it adds a new configuration entry with a default version. <br/>
     * The method performs the following steps:
     * <ol>
     * <li>Locates and reads the properties file</li>
     * <li>Loops through each line to find the version configuration key. If the key exists and is not commented out, it identifies the version, increments the patch number, and updates that line</li>
     * <li>If the configuration key is missing from the file, it adds a new entry with a predefined version</li>
     * <li>Saves the updated properties file</li>
     * </ol>
     * @throws IOException If the application properties file is missing or cannot be read or written.
     */
    private void performConfigBump() throws IOException {
        if (!bumpVersion) return;

        requestConfirmation("Perform config bumping");
        final Path propertiesPath = targetNodePath.resolve(CONFIG_LOCATION).resolve(APPLICATION_PROPERTIES_FILE_NAME);
        if (Files.notExists(propertiesPath)) {
            Files.createFile(propertiesPath);
        }

        final List<String> lines = Files.readAllLines(propertiesPath, StandardCharsets.UTF_8);

        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (isComment(line)) {
                continue;
            }

            if (line.startsWith(CONFIG_KEY)) {
                final String originalValue =
                        line.replace(CONFIG_KEY, "").replace("=", "").trim();
                final long parsed = Long.getLong(originalValue);
                final long bumped = parsed + 1;
                final String newLine = CONFIG_KEY + "=" + bumped;
                lines.set(i, newLine);
                updated = true;
                break; // Update only the first occurrence
            }
        }

        if (!updated) {
            lines.add(String.format("%s=%s", CONFIG_KEY, 1));
        }

        Files.write(propertiesPath, lines, StandardCharsets.UTF_8);
    }

    private void requestConfirmation(final String message) {
        System.out.println(message);
        if (!this.autoConfirm) {
            Console console = System.console();
            String keepGoing = "";
            while (Objects.equals(keepGoing, "")) {
                if (console != null) {
                    keepGoing = console.readLine("Continue? [Y/N]:");
                } else {
                    // fallback for IDEs where System.console() is null
                    System.out.println("Continue? [Y/N]:");
                    System.out.flush();
                    keepGoing = new Scanner(System.in).nextLine();
                }
            }
            if (!keepGoing.equalsIgnoreCase("Y")) {
                System.exit(RETURN_CODE_PROMPT_NO);
            }
            System.out.println();
        }
    }

    /**
     * Checks whether a given line of text is a comment. A line is considered a comment if it starts with either '#' or
     * '!', ignoring leading whitespace during this check.
     *
     * @param line the line of text to check, must not be null
     * @return {@code true} if the line is a comment, {@code false} otherwise
     */
    private static boolean isComment(@NonNull final String line) {
        final String trimmed = line.stripLeading();
        return trimmed.startsWith("#") || trimmed.startsWith("!");
    }

    /**
     * Attempts to load a {@link Roster} from a given network override valid file.
     *
     * @param path the path to the file to load the roster from
     * @return the loaded roster, if it was found and successfully loaded
     */
    public static Roster loadRosterFrom(@NonNull final Path path) {
        if (Files.exists(path)) {
            try (final var fin = Files.newInputStream(path)) {
                final var network = Network.JSON.parse(new ReadableStreamingData(fin));
                return RosterUtils.rosterFrom(network);
            } catch (final Exception e) {
                System.err.printf("Failed to load %s network info from %s%n", path.toAbsolutePath(), e);
            }
        }
        return null;
    }

    record StateInformation(Long round, Roster roster, Hash hash, SavedStateInfo fileInfo) {}
}
