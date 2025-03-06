// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

/**
 * Defines the different modes for block node operation in tests.
 */
public enum BlockNodeMode {
    /** Use Docker containers for block nodes */
    CONTAINERS,

    /** Use a simulated block node */
    SIMULATOR,

    /** User is already running a local hedera block node. SubProcessNode 1 will connect to it. */
    LOCAL_NODE,

    /** Don't use any block nodes */
    NONE
}
