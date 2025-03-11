// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.executorsample;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.common.metrics.extensions.BaseExecutorFactoryMetrics;
import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.server.HttpHandlerDefinition;
import com.swirlds.platform.base.example.server.HttpHandlerRegistry;
import com.swirlds.platform.base.example.server.PostTriggerHandler;
import java.util.Set;
import java.util.function.Consumer;

public class BaseExecutorHandlerFactory implements HttpHandlerRegistry {

    record TaskDefinition(int count, long durationInMs, boolean fail) {}

    @Override
    public Set<HttpHandlerDefinition> handlers(BaseContext context) {
        final BaseExecutorFactoryMetrics metricsInstaller = new BaseExecutorFactoryMetrics(context.metrics());

        final Consumer<TaskDefinition> addTasksHandler = createTaskExecutionHandler();

        final Consumer<Void> resetHandler = v -> {
            metricsInstaller.reset();
        };

        return Set.of(
                new PostTriggerHandler<>("/executor/call", context, TaskDefinition.class, addTasksHandler),
                new PostTriggerHandler<>("/executor/reset", context, Void.class, resetHandler));
    }

    private static Consumer<TaskDefinition> createTaskExecutionHandler() {
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();

        final Consumer<TaskDefinition> addTasksHandler = d -> {
            for (int i = 0; i < d.count; i++) {
                baseExecutorFactory.submit(() -> {
                    try {
                        Thread.sleep(d.durationInMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (d.fail) {
                        throw new RuntimeException("Task failed");
                    }
                });
            }
        };
        return addTasksHandler;
    }
}
