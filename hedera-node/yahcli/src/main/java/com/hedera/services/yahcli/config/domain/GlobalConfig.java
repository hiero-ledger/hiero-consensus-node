// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config.domain;

import java.util.Map;

public class GlobalConfig {
    private String defaultNetwork;
    private Map<String, NetConfig> networks;

    public Map<String, NetConfig> getNetworks() {
        return networks;
    }

    public void setNetworks(Map<String, NetConfig> networks) {
        this.networks = networks;
    }

    public String getDefaultNetwork() {
        return defaultNetwork;
    }

    public void setDefaultNetwork(String defaultNetwork) {
        this.defaultNetwork = defaultNetwork;
    }
}
