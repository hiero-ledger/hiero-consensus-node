// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.utility;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.state.saved.DeserializedSignedState;

/**
 * A set of utility methods to work with Hedera application specifics dependencies
 */
public class HederaUtils {
    /**
     * The application name from the platform's perspective. This is currently locked in at the old main class name and
     * requires data migration to change.
     */
    public static final String HEDERA_MAIN_CLASS = "com.hedera.node.app.ServicesMain";
    /**
     * The swirld name. Currently, there is only one swirld.
     */
    public static final String SWIRLD_NAME = "123";
    /**
     * Hedera main class has a particular way of building using a static method.
     * This is to avoid the circular dependency app-->platform-->app
     *
     * @param configuration The configuration of the consensus node
     * @param time the source of time
     * @throws RuntimeException when there is an issue loading the class
     * @return an instance of hedera app
     */
    public static SwirldMain createHederaAppMain(@NonNull final Configuration configuration, @NonNull final Time time) {
        try {
            final Class<?> mainClass = Class.forName(HEDERA_MAIN_CLASS);
            Method newHederaMethod =
                    mainClass.getDeclaredMethod("newHedera", Configuration.class, Metrics.class, Time.class);
            return (SwirldMain) newHederaMethod.invoke(null, configuration, new NoOpMetrics(), time);
        } catch (final ClassNotFoundException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Calls the method that sets the state hash.
     * See: {@code  Hedera#setInitialStateHash}
     */
    public static void updateStateHash(
            @NonNull final SwirldMain hederaApp, @NonNull final DeserializedSignedState deserializedSignedState) {
        try {
            Method setInitialStateHash = hederaApp.getClass().getDeclaredMethod("setInitialStateHash", Hash.class);
            setInitialStateHash.invoke(hederaApp, deserializedSignedState.originalHash());
        } catch (final NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
