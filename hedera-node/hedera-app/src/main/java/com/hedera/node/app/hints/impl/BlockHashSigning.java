// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The mechanism-independent orchestration of an in-progress block hash signing.
 */
public sealed interface BlockHashSigning permits HintsContext.Signing, RsaContext.Signing {
    /**
     * Incorporates a node's pre-validated signature contribution into this signing attempt.
     *
     * @param crs the final CRS used by the network
     * @param nodeId the node ID
     * @param signature the pre-validated signature contribution
     */
    void incorporateValid(@NonNull Bytes crs, long nodeId, @NonNull Bytes signature);
}
