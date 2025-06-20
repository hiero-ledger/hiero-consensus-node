// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import com.hederahashgraph.api.proto.java.HederaFunctionality;

/**
 * Enumerates the possible targets for system functionality (i.e. the
 * {@link HederaFunctionality#SystemDelete} and {@link HederaFunctionality#SystemUndelete}
 * functionalities).
 */
public enum SystemFunctionalityTarget {
    /**
     * There is no applicable target.
     */
    NA,
    /**
     * The target is a file.
     */
    FILE,
    /**
     * The target is a contract.
     */
    CONTRACT
}
