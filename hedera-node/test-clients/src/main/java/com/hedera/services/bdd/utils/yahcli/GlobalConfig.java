// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils.yahcli;

import com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener;
import java.util.Map;

/**
 * Since it is very obscure how one might get {@code :checkModuleInfo} to pass with a <i>module-info.java</i>
 * in a {@code test} source set in the {@code yahcli} module, instead we add some yahcli-specific behavior
 * to the {@link SharedNetworkLauncherSessionListener}, which is conveniently on the {@code main} source set
 * here in {@code test-clients}. This needs a copy of the POJOs used to serialize yahcli's YAML config.
 */
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
