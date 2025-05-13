// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class HederaOpsDurationData {
    private Map<Integer, Long> opsDuration;
    private Map<Integer, Long> precompileDuration;
    private Map<Integer, Long> systemContractDuration;

    // Public no‑arg constructor for Jackson
    public HederaOpsDurationData() {}

    @JsonCreator
    public HederaOpsDurationData(
            @JsonProperty("opsDuration") Map<Integer, Long> opsDuration,
            @JsonProperty("precompileDuration") Map<Integer, Long> precompileDuration,
            @JsonProperty("systemContractDuration") Map<Integer, Long> systemContractDuration) {
        this.opsDuration = opsDuration;
        this.precompileDuration = precompileDuration;
        this.systemContractDuration = systemContractDuration;
    }

    public Map<Integer, Long> getOpsDuration() {
        return opsDuration;
    }

    public void setOpsDuration(Map<Integer, Long> opsDuration) {
        this.opsDuration = opsDuration;
    }

    public Map<Integer, Long> getPrecompileDuration() {
        return precompileDuration;
    }

    public void setPrecompileDuration(Map<Integer, Long> precompileDuration) {
        this.precompileDuration = precompileDuration;
    }

    public Map<Integer, Long> getSystemContractDuration() {
        return systemContractDuration;
    }

    public void setSystemContractDuration(Map<Integer, Long> systemContractDuration) {
        this.systemContractDuration = systemContractDuration;
    }
}
