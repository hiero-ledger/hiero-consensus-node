// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
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
        final var network = spec.targetNetworkOrThrow();
        // Pick exactly one sidecar source based on the current stream mode so we never
        // see the same logical sidecar from both legacy files and block-translation:
        //   BLOCKS  -> block stream only (no V6 sidecar files are written)
        //   RECORDS / BOTH -> legacy V6 sidecar files only (the canonical source there)
        final var streamMode = spec.startupProperties().getStreamMode("blockStream.streamMode");
        if (streamMode == BLOCKS) {
            final var blockStreamLoc = network.getRequiredNode(byNodeId(0)).getExternalPath(BLOCK_STREAMS_DIR);
            spec.setSidecarWatcher(new SidecarWatcher(streamsLoc, blockStreamLoc, network.shard(), network.realm()));
            log.info("Watching for block-derived sidecars at {} (streamMode=BLOCKS)", blockStreamLoc);
        } else {
            spec.setSidecarWatcher(new SidecarWatcher(streamsLoc));
            log.info("Watching for V6 sidecar files at {} (streamMode={})", streamsLoc, streamMode);
        }
        return false;
    }
}
