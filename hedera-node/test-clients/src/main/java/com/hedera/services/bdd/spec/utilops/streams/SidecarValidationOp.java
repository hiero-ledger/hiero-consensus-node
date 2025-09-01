// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.spec.verification.traceability.SidecarWatcher;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link UtilOp} that initializes a {@link SidecarWatcher} for the
 * given {@link HapiSpec} and registers it with
 * {@link HapiSpec#setSidecarWatcher(SidecarWatcher)}.
 */
public class SidecarValidationOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(SidecarValidationOp.class);

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var streamsLoc = spec.recordStreamsLoc(byNodeId(0));
        spec.setSidecarWatcher(new SidecarWatcher(streamsLoc));
        log.info("Watching for sidecar files at {}", streamsLoc);
        return false;
    }
}
