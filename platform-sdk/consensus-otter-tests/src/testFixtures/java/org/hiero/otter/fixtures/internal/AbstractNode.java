// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.logging.legacy.payload.IssPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

/**
 * Base implementation of the {@link Node} interface that provides common functionality.
 */
public abstract class AbstractNode extends AbstractMiniNode implements Node {

    private static final Logger log = LogManager.getLogger();

    /** Current software version of the platform */
    protected SemanticVersion version = MiniNode.DEFAULT_VERSION;

    /** Saved state directory */
    protected Path savedStateDirectory;

    /**
     * The current state of the platform. Volatile because it is set by the container callback thread and read by the
     * test thread.
     */
    @Nullable
    protected volatile PlatformStatus platformStatus = null;

    /**
     * Constructor for the AbstractNode class.
     *
     * @param selfId the unique identifier for this node
     * @param keysAndCerts the cryptographic keys and certificates for this node
     */
    protected AbstractNode(
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final NetworkConfiguration networkConfiguration) {
        super(selfId, keysAndCerts, networkConfiguration);
        this.version = networkConfiguration.version();
        final Path savedStateDirectory = networkConfiguration.savedStateDirectory();
        if (savedStateDirectory != null) {
            startFromSavedState(savedStateDirectory);
        }
    }

    /**
     * Gets the time manager associated with this node.
     *
     * @return the time manager
     */
    @NonNull
    protected abstract TimeManager timeManager();

    /**
     * Gets a random number generator associated with this node.
     *
     * @return the random number generator
     */
    @NonNull
    protected abstract Random random();

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformStatus platformStatus() {
        return platformStatus;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SemanticVersion version() {
        return version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throwIfInLifecycle(RUNNING, "Cannot set version while the node is running");
        throwIfInLifecycle(DESTROYED, "Cannot set version after the node has been destroyed");

        this.version = requireNonNull(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startFromSavedState(@NonNull final Path savedStateDirectory) {
        throwIfInLifecycle(RUNNING, "Cannot set saved state directory while the node is running");
        throwIfInLifecycle(DESTROYED, "Cannot set saved state directory after the node has been destroyed");

        this.savedStateDirectory = OtterSavedStateUtils.findSaveState(requireNonNull(savedStateDirectory));
    }

    /**
     * Gets the saved state directory for this node.
     *
     * <p>The saved state directory is set when the node is configured to start from a previously saved state.
     * If the node is starting from genesis, this will be {@code null}.
     *
     * @return the path to the saved state directory, or {@code null} if starting from genesis
     */
    @Nullable
    public Path savedStateDirectory() {
        return savedStateDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throwIfInLifecycle(RUNNING, "Cannot bump version while the node is running");
        throwIfInLifecycle(DESTROYED, "Cannot bump version after the node has been destroyed");

        int newBuildNumber;
        try {
            newBuildNumber = Integer.parseInt(version.build()) + 1;
        } catch (final NumberFormatException e) {
            newBuildNumber = 1;
        }
        this.version = this.version.copyBuilder().build("" + newBuildNumber).build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSyntheticBottleneck(@NonNull final Duration delayPerRound) {
        doStartSyntheticBottleneck(delayPerRound, DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of the synthetic bottleneck logic, to be provided by subclasses.
     *
     * @param delayPerRound the artificial delay to introduce per consensus round
     * @param timeout the maximum duration to wait for the bottleneck to start
     */
    protected abstract void doStartSyntheticBottleneck(@NonNull Duration delayPerRound, @NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSyntheticBottleneck() {
        doStopSyntheticBottleneck(DEFAULT_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerSelfIss() {
        doTriggerSelfIss(DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of the self ISS logic. Subclasses of environments that do not support ISS override this
     * method to throw, which covers the asynchronous variant as well.
     *
     * @param timeout the maximum duration to wait for the ISS to be triggered
     */
    protected void doTriggerSelfIss(@NonNull final Duration timeout) {
        throwIsNotInLifecycle(RUNNING, "Node must be running to trigger a self ISS.");

        log.info("Sending Self ISS triggering transaction...");
        final Instant start = timeManager().now();
        final OtterTransaction issTransaction =
                TransactionFactory.createSelfIssTransaction(random().nextLong(), selfId);

        submitTransaction(issTransaction);
        final Duration elapsed = Duration.between(start, timeManager().now());

        log.debug("Waiting for Self ISS to trigger...");

        final AtomicBoolean found =
                newLogResult().onNextMatch(logEntry -> logEntry.message().contains(IssPayload.class.getName()));

        timeManager()
                .waitForCondition(found::get, timeout.minus(elapsed), "Did not find the IssPayload log before timeout");

        log.info("Self ISS triggered");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNodeActionsImpl(timeout);
    }

    /**
     * The actual implementation of the stop synthetic bottleneck logic, to be provided by subclasses.
     *
     * @param timeout the maximum duration to wait for the bottleneck to stop
     */
    protected abstract void doStopSyntheticBottleneck(@NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        throwIsNotInLifecycle(RUNNING, "Can send quiescence commands only while the node is running");
        doSendQuiescenceCommand(command, DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of sending the quiescence command, to be provided by subclasses.
     *
     * @param command the quiescence command to send
     * @param timeout the maximum duration to wait for the command to be processed
     */
    protected abstract void doSendQuiescenceCommand(@NonNull QuiescenceCommand command, @NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Node{id=" + selfId.id() + '}';
    }

    private class AsyncNodeActionsImpl extends AbstractMiniNode.AsyncMiniNodeActionsImpl implements AsyncNodeActions {

        private AsyncNodeActionsImpl(@NonNull final Duration timeout) {
            super(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startSyntheticBottleneck(@NonNull final Duration delayPerRound) {
            doStartSyntheticBottleneck(delayPerRound, timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stopSyntheticBottleneck() {
            doStopSyntheticBottleneck(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void triggerSelfIss() {
            doTriggerSelfIss(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
            throwIsNotInLifecycle(RUNNING, "Can send quiescence commands only while the node is running");
            doSendQuiescenceCommand(command, timeout);
        }
    }
}
