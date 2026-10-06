// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.config;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import com.hedera.services.yahcli.config.domain.NetConfig;
import com.hedera.services.yahcli.test.YahcliTestBase;
import java.io.File;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

    @Test
    void isValidReturnsTrueForUnencryptedEcPemWithEmptyPassphrase() throws Exception {
        final URI uri = getClass()
                .getClassLoader()
                .getResource("testFiles/unencrypted-ec.pem")
                .toURI();
        final File pemFile = new File(uri);

        assertTrue(ConfigManager.isValid(pemFile, Optional.of("")));
    }

    @Test
    void isValidReturnsTrueForUnencryptedEcPemWithArbitraryPassphrase() throws Exception {
        final URI uri = getClass()
                .getClassLoader()
                .getResource("testFiles/unencrypted-ec.pem")
                .toURI();
        final File pemFile = new File(uri);

        // An unencrypted key needs no passphrase; any non-null value in the Optional should work
        assertTrue(ConfigManager.isValid(pemFile, Optional.of("some-arbitrary-passphrase")));
    }

    @Test
    void isValidReturnsFalseWhenPassphraseAbsent() throws Exception {
        final URI uri = getClass()
                .getClassLoader()
                .getResource("testFiles/unencrypted-ec.pem")
                .toURI();
        final File pemFile = new File(uri);

        assertFalse(ConfigManager.isValid(pemFile, Optional.empty()));
    }
}
