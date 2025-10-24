// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.systemtask;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.systemtasks.SystemTaskContext;
import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Dispatches a {@link SystemTaskContext} to a matching {@link SystemTaskHandler}.
 */
@Singleton
public class SystemTaskHandlers {
    private final List<Registration> registrations;

    public record Registration(@NonNull RpcService service, @NonNull SystemTaskHandler handler) {}

    @Inject
    public SystemTaskHandlers(@NonNull final ServicesRegistry servicesRegistry) {
        this.registrations = servicesRegistry.registrations().stream()
                .map(ServicesRegistry.Registration::service)
                .filter(v -> v instanceof RpcService)
                .map(RpcService.class::cast)
                .flatMap(s -> s.systemTaskHandlers().stream().map(h -> new Registration(s, h)))
                .toList();
    }

    /**
     * Finds a registration supporting the given task, and returns it; null if there is none.
     * @param task the task to find a handler registration for
     */
    public @Nullable Registration getRegistration(@NonNull final SystemTask task) {
        for (final var r : registrations) {
            if (r.handler().supports(task)) {
                return r;
            }
        }
        return null;
    }
}
