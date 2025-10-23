// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

/**
 * Enumerates files and directories created and used by a Hedera node.
 */
public enum ExternalPath {
    APPLICATION_LOG,
    SWIRLDS_LOG,
    BLOCK_NODE_COMMS_LOG,
    ADDRESS_BOOK,
    GENESIS_PROPERTIES,
    APPLICATION_PROPERTIES,
    NODE_ADMIN_KEYS_JSON,
    LOG4J2_XML,
    RECORD_STREAMS_DIR,
    BLOCK_STREAMS_DIR,
    DATA_CONFIG_DIR,
    UPGRADE_ARTIFACTS_DIR,
    SAVED_STATES_DIR,
    WORKING_DIR,
}
