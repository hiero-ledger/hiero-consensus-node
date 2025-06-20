// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

public class ConsensusScenario {
    public static final String PERSISTENT_TOPIC_NAME = "persistentTopic";
    public static final String NOVEL_TOPIC_NAME = "novelTopic";

    Long persistent;

    public Long getPersistent() {
        return persistent;
    }

    public void setPersistent(Long persistent) {
        this.persistent = persistent;
    }
}
