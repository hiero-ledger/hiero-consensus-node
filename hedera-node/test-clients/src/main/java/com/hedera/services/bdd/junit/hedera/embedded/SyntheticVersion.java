// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded;

/**
 * Enumerates the possible categories of software version we may request for the version of the event used to
 * submit a transaction to an embedded network.
 */
public enum SyntheticVersion {
    PAST,
    PRESENT,
    FUTURE
}
