// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example;

import com.swirlds.platform.base.example.executorsample.BaseExecutorHandlerFactory;
import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.ext.BaseContextFactory;
import com.swirlds.platform.base.example.jdkmetrics.JVMInternalMetrics;
import com.swirlds.platform.base.example.metricsample.MetricsSampleHandlerRegistry;
import com.swirlds.platform.base.example.server.Server;
import com.swirlds.platform.base.example.store.StoreExampleHandlerRegistry;
import java.io.IOException;

/**
 * This application serves as a testing environment for platform-base module frameworks.
 */
public class Application {

    public static void main(String[] args) throws IOException {
        final BaseContext baseContext = BaseContextFactory.create();
        // Add JDK metrics to track memory, cpu, etc
        JVMInternalMetrics.registerMetrics(baseContext.metrics());
        Server.start(
                baseContext,
                new StoreExampleHandlerRegistry(),
                new MetricsSampleHandlerRegistry(),
                new BaseExecutorHandlerFactory());
    }
}
