// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.client;

import com.hedera.hapi.node.base.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.UnknownHostException;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.interledger.clpr.client.ClprClient;

/**
 * Manages connections to remote CLPR Endpoints.
 * <p>
 * This class is responsible for creating and managing CLPR clients that connect to remote CLPR Endpoints.
 */
@Singleton
public class ClprConnectionManager {

    @Inject
    public ClprConnectionManager() {
        // Default constructor for dependency injection.
    }

    /**
     * Creates a new CLPR client for the specified service endpoint. The client connection remains open until closed
     *
     * @param serviceEndpoint The service endpoint to connect to.
     * @return A new instance of {@link ClprClient} connected to the specified service endpoint.
     */
    public ClprClient createClient(@NonNull final ServiceEndpoint serviceEndpoint) throws UnknownHostException {
        return new ClprClientImpl(serviceEndpoint);
    }
}
