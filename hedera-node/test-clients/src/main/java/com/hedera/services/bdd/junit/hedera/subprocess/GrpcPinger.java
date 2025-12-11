// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GrpcPinger {
    private static final Logger log = LogManager.getLogger(GrpcPinger.class);
    private static final Set<Integer> loggedFailurePorts = ConcurrentHashMap.newKeySet();

    public boolean isLive(final int port) {
        try {
            final var url = new URL("http://localhost:" + port + "/");
            final var connection = url.openConnection();
            connection.connect();
            return true;
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException ignored) {
            // TODO: Remove temporary logging once multi-network startup flake is diagnosed
            if (loggedFailurePorts.add(port)) {
                log.info("TEMP-DEBUG Unable to ping gRPC port {}: {}", port, ignored.toString());
            }
        }
        return false;
    }
}
