// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

/**
 * Defines the different modes for block node operation in tests.
 *
 * <p>The block node mode can be set using the system property "hapi.spec.blocknode.mode" with the following values:
 * <ul>
 *   <li>"REAL" - Use Docker containers for block nodes (maps to REAL)</li>
 *   <li>"SIMULATOR" - Use simulated block nodes (maps to SIMULATOR)</li>
 *   <li>"LOCAL_NODE" - Use a local block node (maps to LOCAL_NODE)</li>
 * </ul>
 * If not specified, the default is NONE.
 */
public enum BlockNodeMode {
    /** Use Docker containers for block nodes */
    REAL,

    /** Use a simulated block node */
    SIMULATOR,

    /** User is already running a local hiero block node. SubProcessNode 1 will connect to it. */
    LOCAL_NODE,

    /** Don't use any block nodes */
    NONE
}
