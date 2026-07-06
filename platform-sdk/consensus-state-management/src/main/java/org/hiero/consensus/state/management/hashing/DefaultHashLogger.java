// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management.hashing;

import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.message.ParameterizedMessageFactory;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * A default implementation of a {@link HashLogger}.
 */
public class DefaultHashLogger implements HashLogger {

    private static final Logger logger = LogManager.getLogger(DefaultHashLogger.class);

    private static final MessageFactory MESSAGE_FACTORY = ParameterizedMessageFactory.INSTANCE;
    private final AtomicLong lastRoundLogged = new AtomicLong(-1);
    private final int depth;
    private final Logger logOutput; // NOSONAR: selected logger to output to.
    private final boolean isEnabled;

    /**
     * Construct a HashLogger.
     *
     * @param configuration the configuration to read from
     */
    public DefaultHashLogger(@NonNull final Configuration configuration) {
        this(configuration, logger);
    }

    /**
     * Internal constructor visible for testing.
     *
     * @param configuration the configuration to read from
     * @param logOutput the logger to write to
     */
    DefaultHashLogger(@NonNull final Configuration configuration, @NonNull final Logger logOutput) {
        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        depth = stateConfig.debugHashDepth();
        isEnabled = stateConfig.enableHashStreamLogging();
        this.logOutput = Objects.requireNonNull(logOutput);
    }

    /**
     * Delegates extraction and logging of the signed state's hashes
     *
     * @param reservedState the signed state to retrieve hash information from and log.
     */
    public void logHashes(@NonNull final ReservedSignedState reservedState) {
        try (reservedState) {
            if (!isEnabled) {
                return;
            }

            final SignedState signedState = reservedState.get();

            final long currentRound = signedState.getRound();
            final long prevRound = lastRoundLogged.getAndUpdate(value -> Math.max(value, currentRound));

            if (prevRound >= 0 && currentRound - prevRound > 1) {
                // One or more rounds skipped.
                logOutput.info(
                        STATE_HASH.getMarker(),
                        () -> MESSAGE_FACTORY.newMessage(
                                "*** Several rounds skipped. Round received {}. Previously received {}.",
                                currentRound,
                                prevRound));
            }

            if (currentRound > prevRound) {
                logOutput.info(STATE_HASH.getMarker(), () -> generateLogMessage(signedState));
            }
        }
    }

    /**
     * Generate the actual log message. Packaged in a lambda in case the logging framework decides not to log it.
     *
     * @param signedState the signed state to log
     * @return the log message
     */
    @NonNull
    private Message generateLogMessage(@NonNull final SignedState signedState) {
        final String platformInfo = getInfoString(signedState.getState());

        return MESSAGE_FACTORY.newMessage("""
                        State Info, round = {}:
                        {}""", signedState.getRound(), platformInfo);
    }
}
