// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

/**
 * The category of an explicit proof vote, as judged by verifying its WRAPS proof.
 */
public enum ProofVoteCategory {
    /**
     * The vote's WRAPS proof establishes the expected metadata in the expected chain of trust.
     */
    VALID,
    /**
     * The vote has no WRAPS proof, or its proof does not establish the expected metadata in the
     * expected chain of trust.
     */
    INVALID
}
