// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the status of a node in the network.
 *
 * @param lastAttempt the last attempt to look up the status of the node
 * @param grpcStatus the last known gRPC status of the node
 * @param bindExceptionSeen whether a bind exception has been seen in the logs
 * @param retryCount the number of times the status lookup has been retried
 */
public record NodeStatus(
        @NonNull StatusLookupAttempt lastAttempt,
        @NonNull GrpcStatus grpcStatus,
        @NonNull BindExceptionSeen bindExceptionSeen,
        int retryCount) {
    public enum GrpcStatus {
        NA,
        UP,
        DOWN
    }

    public enum BindExceptionSeen {
        NA,
        YES,
        NO
    }
}
