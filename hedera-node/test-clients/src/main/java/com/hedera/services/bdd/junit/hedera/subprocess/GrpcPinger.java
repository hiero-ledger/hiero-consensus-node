// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

public class GrpcPinger {
    public boolean isLive(final int port) {
        try {
            final var url = new URL("http://localhost:" + port + "/");
            final var connection = url.openConnection();
            connection.connect();
            return true;
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException ignored) {
            // This is expected, the node is not up yet
        }
        return false;
    }
}
