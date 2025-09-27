// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli.utils;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.system.SwirldMain;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.hiero.base.crypto.Hash;

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
     * @param platformContext The platform context
     * @param platformStateFacade a platformStateFacade
     * @throws RuntimeException when there is an issue loading the class
     * @return an instance of hedera app
     */
    public static SwirldMain<? extends MerkleNodeState> createHederaAppMain(
            @NonNull final PlatformContext platformContext, @NonNull final PlatformStateFacade platformStateFacade) {
        try {
            final Class<?> mainClass = Class.forName(HEDERA_MAIN_CLASS);
            Method newHederaMethod = mainClass.getDeclaredMethod(
                    "newHedera", Metrics.class, PlatformStateFacade.class, Configuration.class);
            return (SwirldMain<? extends MerkleNodeState>) newHederaMethod.invoke(
                    null, new NoOpMetrics(), platformStateFacade, platformContext.getConfiguration());
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
            @NonNull final SwirldMain<? extends MerkleNodeState> hederaApp,
            @NonNull final DeserializedSignedState deserializedSignedState) {
        try {
            Method setInitialStateHash = hederaApp.getClass().getDeclaredMethod("setInitialStateHash", Hash.class);
            setInitialStateHash.invoke(hederaApp, deserializedSignedState.originalHash());
        } catch (final NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
