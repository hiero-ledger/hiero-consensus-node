// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

import java.util.Map;

public class ValidationConfig {
    public static final long DEFAULT_SLEEP_MS_BEFORE_NEXT_NODE = 5_000L;

    Long sleepMsBeforeNextNode = DEFAULT_SLEEP_MS_BEFORE_NEXT_NODE;
    Map<String, Network> networks;

    public Map<String, Network> getNetworks() {
        return networks;
    }

    public void setNetworks(Map<String, Network> networks) {
        this.networks = networks;
    }

    public Long getSleepMsBeforeNextNode() {
        return sleepMsBeforeNextNode;
    }

    public void setSleepMsBeforeNextNode(Long sleepMsBeforeNextNode) {
        this.sleepMsBeforeNextNode = sleepMsBeforeNextNode;
    }
}
