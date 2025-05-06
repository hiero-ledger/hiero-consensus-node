// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.util;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.remote.RemoteNetworkFactory;
import com.hedera.services.yahcli.config.ConfigManager;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class HapiSpecUtils {

    private HapiSpecUtils() {
        // Utility class
    }

    /**
     * todo
     * @param spec
     * @param configManager
     * @return
     */
    public static Stream<DynamicTest> targeted(HapiSpec spec, ConfigManager configManager) {
        final var network = RemoteNetworkFactory.newWithTargetFrom(
                configManager.shard().getShardNum(), configManager.realm().getRealmNum(), configManager.asNodeInfos());
        spec.setTargetNetwork(network);
        return Stream.of(DynamicTest.dynamicTest("Yahcli-" + spec.getName(), spec));
    }
}
