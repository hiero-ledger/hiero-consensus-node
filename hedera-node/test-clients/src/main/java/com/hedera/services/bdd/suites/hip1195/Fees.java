// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

public record Fees(int makerBps, int takerBps) {
    public int forRole(Role role) {
        return switch (role) {
            case MAKER -> makerBps;
            case TAKER -> takerBps;
        };
    }
}
