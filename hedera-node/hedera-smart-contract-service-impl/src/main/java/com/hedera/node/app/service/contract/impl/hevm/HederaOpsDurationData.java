// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class HederaOpsDurationData {
    private Map<Integer, Long> opsDuration;
    private Map<String, Long> gasBasedDurationMultiplier;

    // Public no‑arg constructor for Jackson
    public HederaOpsDurationData() {}

    @JsonCreator
    public HederaOpsDurationData(
            @JsonProperty("opsDuration") Map<Integer, Long> opsDuration,
            @JsonProperty("gasBasedDurationMultiplier") Map<String, Long> gasBasedDurationMultiplier) {
        this.opsDuration = opsDuration;
        this.gasBasedDurationMultiplier = gasBasedDurationMultiplier;
    }

    public Map<Integer, Long> getOpsDuration() {
        return opsDuration;
    }

    public void setOpsDuration(Map<Integer, Long> opsDuration) {
        this.opsDuration = opsDuration;
    }

    public Map<String, Long> getGasBasedDurationMultiplier() {
        return gasBasedDurationMultiplier;
    }

    public void setGasBasedDurationMultiplier(Map<String, Long> gasBasedDurationMultiplier) {
        this.gasBasedDurationMultiplier = gasBasedDurationMultiplier;
    }
}
