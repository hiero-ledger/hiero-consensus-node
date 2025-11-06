// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import static com.swirlds.platform.state.editor.StateEditorUtils.formatNodeType;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatRoute;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.swirlds.cli.utility.CommandBuilder;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.interfaces.MerkleTraversable;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import com.swirlds.common.merkle.route.MerkleRouteUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateReference;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import picocli.CommandLine;

/**
 * An interactive SignedState editor.
 */
public class StateEditor {

    private final SignedStateReference signedState = new SignedStateReference();
    private MerkleRoute currentWorkingRoute = MerkleRouteFactory.getEmptyRoute();
    private boolean alive = true;
    private final PlatformContext platformContext;

    /**
     * Create a new state editor.
     *
     * @param statePath the path where the signed state can be found
     */
    @SuppressWarnings("java:S106")
    public StateEditor(final Path statePath) throws IOException {

        final Configuration configuration = DefaultConfiguration.buildBasicConfiguration(ConfigurationBuilder.create());

        platformContext = PlatformContext.create(configuration);

        final DeserializedSignedState deserializedSignedState = SignedStateFileReader.readStateFile(
                statePath,
                (virtualMap) -> {
                    // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19003
                    throw new UnsupportedOperationException();
                },
                DEFAULT_PLATFORM_STATE_FACADE,
                platformContext);

        try (final ReservedSignedState reservedSignedState = deserializedSignedState.reservedSignedState()) {
            System.out.println("\nLoading state from " + statePath);
            signedState.set(reservedSignedState.get(), "StateEditor constructor");
            System.out.println("Hashing state");
            try {
                platformContext
                        .getMerkleCryptography()
                        .digestTreeAsync(reservedSignedState.get().getState().getRoot())
                        .get();
            } catch (final InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("problem encountered while hashing state", e);
            }
            System.out.println("Hash = " + reservedSignedState.get().getState().getHash());
        }
    }

    /**
     * Set the execution strategy. Required to inject this object into the command object instantiated by piciocli.
     */
    private int executionStrategy(CommandLine.ParseResult parseResult) {
        final Object command =
                parseResult.subcommand().commandSpec().commandLine().getCommand();
        if (command instanceof final StateEditorOperation operation) {
            operation.setStateEditor(this);
        }

        return new CommandLine.RunLast().execute(parseResult);
    }

    /**
     * Build the command line that will parse the user input.
     */
    private CommandLine buildCommandLine() {
        final CommandLine commandLine = CommandBuilder.buildCommandLine(StateEditorRoot.class);
        commandLine.setExecutionStrategy(this::executionStrategy);
        return commandLine;
    }

    /**
     * Start the editor.
     */
    @SuppressWarnings("java:S106")
    public void start() {
        Scanner reader = new Scanner(System.in);

        CommandLine commandLine = buildCommandLine();
        System.out.println();
        commandLine.usage(System.out, commandLine.getColorScheme());

        while (alive) {

            MerkleNode target = null;
            while (true) {
                try (final ReservedSignedState reservedSignedState = getState("StateEditor.start()")) {
                    final MerkleTraversable merkleTraversable =
                            reservedSignedState.get().getState().getRoot();
                    target = merkleTraversable.getNodeAtRoute(currentWorkingRoute);
                    break;
                } catch (final NoSuchElementException e) {
                    // This is possible of the current location gets deleted
                    currentWorkingRoute = currentWorkingRoute.getParent();
                }
            }

            System.out.print("\n" + formatRoute(currentWorkingRoute) + " " + formatNodeType(target) + " $ ");

            final String command = reader.nextLine();
            final String[] args = command.split(" ");

            try {
                final int result = commandLine.execute(args);

                if (result != 0 && commandLine.getParseResult().subcommand() == null) {
                    System.out.println();
                    commandLine.usage(System.out, CommandBuilder.getColorScheme());
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }

            // Rebuild to clear out old arguments.
            commandLine = buildCommandLine();
        }

        reader.close();
    }

    /**
     * Cause the editor to stop at the end of the current operation.
     */
    public void exit() {
        alive = false;
    }

    /**
     * Get the current working route, equivalent to the "current working directory" but relative to the root of the
     * state.
     */
    public MerkleRoute getCurrentWorkingRoute() {
        return currentWorkingRoute;
    }

    /**
     * Set the current working route, equivalent to the "current working directory" but relative to the root of the
     * state.
     */
    public void setCurrentWorkingRoute(final MerkleRoute currentWorkingRoute) {
        try (final ReservedSignedState reservedSignedState = getState("StateEditor.setCurrentWorkingRoute()")) {
            final MerkleTraversable merkleTraversable =
                    reservedSignedState.get().getState().getRoot();
            merkleTraversable.getNodeAtRoute(currentWorkingRoute); // throws if invalid
            this.currentWorkingRoute = currentWorkingRoute;
        }
    }

    /**
     * Get the state.
     */
    public ReservedSignedState getState(final String reason) {
        return signedState.getAndReserve(reason);
    }

    /**
     * Get the signed state.
     */
    public ReservedSignedState getSignedState() {
        return signedState.getAndReserve("StateEditor.getSignedState()");
    }

    /**
     * Copy the state and return the immutable copy.
     *
     * @return the immutable copy
     */
    public ReservedSignedState getSignedStateCopy() {
        try (final ReservedSignedState reservedSignedState =
                signedState.getAndReserve("StateEditor.getSignedStateCopy() 1")) {
            final SignedState newSignedState = new SignedState(
                    platformContext.getConfiguration(),
                    CryptoStatic::verifySignature,
                    reservedSignedState.get().getState().copy(),
                    "StateEditor.getSignedStateCopy()",
                    reservedSignedState.get().isFreezeState(),
                    false,
                    false,
                    DEFAULT_PLATFORM_STATE_FACADE);

            signedState.set(newSignedState, "StateEditor.getSignedStateCopy() 2");

            return signedState.getAndReserve("StateEditor.getSignedStateCopy() return value");
        }
    }

    /**
     * Parse a relative merkle route string and return the merkle node at that position.
     */
    public MerkleNode getRelativeNode(final String path) {
        try (final ReservedSignedState reservedSignedState = getState("StateEditor.getRelativeNode()")) {
            final MerkleTraversable merkleTraversable =
                    reservedSignedState.get().getState().getRoot();
            return merkleTraversable.getNodeAtRoute(getRelativeRoute(path));
        }
    }

    /**
     * Parse a route string relative to the current working route.
     *
     * @param path a path string
     * @return the route described by that path string
     */
    public MerkleRoute getRelativeRoute(final String path) {
        return MerkleRouteUtils.pathFormatToMerkleRoute(getCurrentWorkingRoute(), path);
    }

    public record ParentInfo(MerkleRoute target, MerkleInternal parent, int indexInParent) {}

    /**
     * Convert a relative merkle route string to information that can be used to add/remove the node at that position.
     * Throws if the parent node is not valid.
     */
    public ParentInfo getParentInfo(final String destinationPath) {
        final MerkleRoute route = getRelativeRoute(destinationPath);
        final MerkleRoute parentPath = route.getParent();
        final int indexInParent = route.getStep(-1);

        try (final ReservedSignedState reservedSignedState = signedState.getAndReserve("StateEditor.getParentInfo()")) {
            final MerkleTraversable merkleTraversable =
                    reservedSignedState.get().getState().getRoot();
            final MerkleNode parent = merkleTraversable.getNodeAtRoute(parentPath);
            if (parent == null) {
                throw new IllegalArgumentException("The node at " + formatRoute(parentPath) + " is null.");
            }
            if (!(parent instanceof MerkleInternal)) {
                throw new IllegalArgumentException("The node at " + formatRoute(parentPath) + " is of type "
                        + parent.getClass().getSimpleName() + " and is not an internal node.");
            }

            return new ParentInfo(route, parent.asInternal(), indexInParent);
        }
    }
}
