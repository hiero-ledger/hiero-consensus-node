// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal.impl;

import com.swirlds.base.internal.BaseExecutorFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ScheduledExecutorService;

/**
 * This factory creates / provides executors for the base modules. The factory should only be used by code in the base
 * modules that highly needs an asynchronous executor. All executors that are created by this factory are daemon threads
 * and have a low priority.
 */
public class BaseExecutorFactoryImpl implements BaseExecutorFactory {

    private static final class InstanceHolder {
        private static final BaseExecutorFactory INSTANCE = new BaseExecutorFactoryImpl();
    }

    /**
     * Constructs a new factory.
     */
    private BaseExecutorFactoryImpl() {}

    @NonNull
    @Override
    public ScheduledExecutorService getScheduledExecutor() {
        return BaseScheduledExecutorService.getInstance();
    }

    /**
     * Returns the singleton instance of this factory.
     *
     * @return the instance
     */
    @NonNull
    public static BaseExecutorFactory getInstance() {
        return InstanceHolder.INSTANCE;
    }
}
