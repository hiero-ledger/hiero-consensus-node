// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.config;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import com.hedera.services.yahcli.config.domain.NetConfig;
import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class ConfigManagerTest extends YahcliTestBase {
    @ParameterizedTest
    @ValueSource(strings = {"", " \t \r \n "})
    void unspecifiedDefaultPayerFailsGracefully(String input) {
        // Construct a network configuration with an unspecified default payer
        final var defaultNetConfig = new NetConfig();
        defaultNetConfig.setDefaultPayer(input);
        final var globalConfig = new GlobalConfig();
        globalConfig.setNetworks(Map.of("localhost", defaultNetConfig));

        // Initialize the Yahcli instance with the necessary args
        parseArgs("-n localhost -a 3 -i 3");

        final var subject = new ConfigManager(testSubjectCli(), globalConfig);

        final var result = Assertions.assertThrows(CommandLine.ParameterException.class, subject::asSpecConfig);
        assertThat(result.getMessage()).contains("No payer was specified, and no default is available in ");
    }
}
