// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded;

/**
 * The modes in which an embedded network can be run.
 */
public enum EmbeddedMode {
    /**
     * Multiple specs can be run concurrently against the embedded network, and inherently nondeterministic
     * actions like thread scheduling and signing with ECDSA keys are supported.
     */
    CONCURRENT,
    /**
     * Only one spec can be run at a time against the embedded network, and all actions must be deterministic.
     */
    REPEATABLE,
}
