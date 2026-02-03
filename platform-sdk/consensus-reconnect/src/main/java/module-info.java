// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.reconnect.config.ReconnectConfigurationExtension;

module org.hiero.consensus.reconnect {
    exports org.hiero.consensus.reconnect.config;

    requires transitive com.swirlds.config.api;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            ReconnectConfigurationExtension;
}
