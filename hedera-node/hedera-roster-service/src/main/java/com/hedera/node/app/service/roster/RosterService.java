// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster;

import com.hedera.node.app.spi.ServiceFactory;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;

/**
 * Service methods for the roster service API
 */
public interface RosterService extends Service {

    /**
     * The name of the service.
     */
    String NAME = "RosterService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static RosterService getInstance() {
        return ServiceFactory.loadService(RosterService.class, ServiceLoader.load(RosterService.class));
    }
}
