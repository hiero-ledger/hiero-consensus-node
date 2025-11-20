// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Container for two isolated subprocess networks provisioned for a dual-network HAPI test.
 *
 * @param primary the primary network
 * @param peer the peer network
 */
public record DualNetwork(@NonNull SubProcessNetwork primary, @NonNull SubProcessNetwork peer)
        implements AutoCloseable {
    @Override
    public void close() {
        terminateQuietly(primary);
        terminateQuietly(peer);
    }

    private static void terminateQuietly(@NonNull final SubProcessNetwork network) {
        try {
            network.terminate();
        } catch (Throwable ignore) {
            // best-effort cleanup
        }
    }
}
