// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

/**
 * Defines which of a simulated block node's APIs are served over TLS, and therefore which {@code streamingTls} /
 * {@code serviceTls} blocks are written into the consensus node's {@code block-nodes.json}.
 *
 * <p>TLS-enabled simulators present a self-signed certificate, which the consensus node trusts by pinning its SHA-384
 * fingerprint. This mirrors how an operator would configure a block node fronted by a TLS-terminating proxy.
 */
public enum BlockNodeTlsMode {
    /** Both APIs are plaintext. */
    NONE,

    /** Only the streaming (publish) API is served over TLS; the service API stays plaintext. */
    PUBLISH_ONLY,

    /** Only the service API is served over TLS; the streaming (publish) API stays plaintext. */
    SERVICE_ONLY,

    /** Every API is served over TLS, and the consensus node pins the simulator's certificate fingerprint. */
    ALL,

    /**
     * Every API is served over TLS, but the consensus node is configured with a fingerprint that does not match the
     * certificate the block node presents. Used to verify that a mismatched certificate is rejected.
     */
    ALL_BAD_FINGERPRINT,

    /**
     * Every API is served over TLS, but the consensus node is configured with no fingerprint at all, so it must
     * validate the simulator's self-signed certificate against the platform trust store. Used to verify that the
     * unpinned path really validates, and therefore rejects a certificate that chains to nothing it trusts.
     */
    ALL_NO_FINGERPRINT
}
