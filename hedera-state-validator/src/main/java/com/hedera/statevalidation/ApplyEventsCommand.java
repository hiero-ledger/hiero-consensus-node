// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.eventstream.EventStreamRecoveryWorkflow.applyEvents;

import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.utility.ParameterizedClass;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Advances a given state to a later round by replaying transactions from an event stream.
 *
 * <p>This command is the event-stream counterpart of {@link ApplyBlocksCommand}. While
 * {@code apply-blocks} advances state by applying pre-computed state changes from block files,
 * this command re-executes transactions from event files through the Hedera application layer.
 * The end result is the same: the state is advanced to a later round.
 */
@Command(name = "apply-events", description = "Update the state by replaying events from an event stream.")
public class ApplyEventsCommand extends ParameterizedClass implements Runnable {

    @ParentCommand
    private StateOperatorCommand parent;

    private Path eventStreamDirectory;
    private Path outputPath = Path.of("./out");
    private NodeId selfId;
    public static final long DEFAULT_TARGET_ROUND = -1L;
    private long targetRound = DEFAULT_TARGET_ROUND;
    private String expectedHash = "";
    private boolean ignorePartialRounds = false;
    private boolean loadSigningKeys = false;

    private ApplyEventsCommand() {}

    @Option(
            names = {"-d", "--event-stream-dir"},
            required = true,
            description = "The path to a directory tree containing event stream files.")
    private void setEventStreamDirectory(final Path eventStreamDirectory) {
        this.eventStreamDirectory = pathMustExist(eventStreamDirectory.toAbsolutePath());
    }

    @Option(
            names = {"-o", "--out"},
            description =
                    "The location where output is written. Default = './out'. "
                            + "Must not exist prior to invocation.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @Option(
            names = {"-id", "--node-id"},
            required = true,
            description = "The ID of the node that is being used to recover the state. "
                    + "This node's keys should be available locally.")
    private void setSelfId(final long selfId) {
        this.selfId = NodeId.of(selfId);
    }

    @Option(
            names = {"-t", "--target-round"},
            defaultValue = "-1",
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds.")
    private void setTargetRound(final long targetRound) {
        this.targetRound = targetRound;
    }

    @Option(
            names = {"-h", "--expected-hash"},
            defaultValue = "",
            description = "Expected hash of the resulting state.")
    private void setExpectedHash(final String expectedHash) {
        this.expectedHash = expectedHash;
    }

    @Option(
            names = {"-p", "--ignore-partial"},
            defaultValue = "false",
            description = "If set then any partial rounds at the end of the event stream are ignored. Default = false.")
    private void setIgnorePartialRounds(final boolean ignorePartialRounds) {
        this.ignorePartialRounds = ignorePartialRounds;
    }

    @Option(
            names = {"-s", "--load-signing-keys"},
            defaultValue = "false",
            description = "If present then load the signing keys. If not present, calling platform.sign() will throw.")
    private void setLoadSigningKeys(final boolean loadSigningKeys) {
        this.loadSigningKeys = loadSigningKeys;
    }

    @Override
    public void run() {
        parent.initializeStateDir();
        try {
            applyEvents(
                    eventStreamDirectory,
                    selfId,
                    targetRound,
                    outputPath,
                    expectedHash,
                    !ignorePartialRounds,
                    loadSigningKeys);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}