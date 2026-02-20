// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.test.fixtures.resource.ResourceLoader;
import com.swirlds.platform.util.BootstrapUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.config.CryptoConfig_;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RandomRosterEntryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CryptoStaticTest {

    private static final NodeId SELF = NodeId.of(0);

    @TempDir
    Path testDataDirectory;

    private Path settingsTxt;

    @BeforeEach
    void setUp() throws IOException {
        final ResourceLoader<CryptoStaticTest> loader = new ResourceLoader<>(CryptoStaticTest.class);
        final Path tempDir = loader.loadDirectory("com/swirlds/platform/crypto/EnhancedKeyStoreLoader");
        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);
        settingsTxt = testDataDirectory.resolve("settings.txt");
        assertThat(settingsTxt).exists().isNotEmptyFile();
    }

    private Configuration configure(final Path keysDir) throws IOException {
        final ConfigurationBuilder builder = ConfigurationBuilder.create();
        BootstrapUtils.setupConfigBuilder(builder, settingsTxt);
        builder.withValue("paths.keysDirPath", keysDir.toAbsolutePath().toString());
        builder.withValue(CryptoConfig_.KEYSTORE_PASSWORD, "password");
        return builder.build();
    }

    private static RosterEntry rosterEntryWithCert(
            final long nodeId, final java.security.cert.X509Certificate sigCert) {
        return RandomRosterEntryBuilder.create(new Random(1234))
                .withNodeId(nodeId)
                .withSigCert(sigCert)
                .build();
    }

    @Test
    @DisplayName("initNodeSecurity covers missing-roster logging path")
    void initNodeSecurityCoversMissingRosterPath() throws Exception {
        final var config = configure(testDataDirectory.resolve("unused-keys-dir"));
        final var keysAndCerts = CryptoStatic.initNodeSecurity(config, SELF, List.of());
        assertThat(keysAndCerts).isNotNull();
    }

    @Test
    @DisplayName("initNodeSecurity covers roster-cert match logging path")
    void initNodeSecurityCoversRosterMatchPath() throws Exception {
        final var config = configure(testDataDirectory.resolve("unused-keys-dir"));
        final var expected = KeysAndCertsGenerator.generate(SELF).sigCert();
        final List<RosterEntry> rosterEntries = List.of(rosterEntryWithCert(SELF.id(), expected));

        final var keysAndCerts = CryptoStatic.initNodeSecurity(config, SELF, rosterEntries);
        assertThat(keysAndCerts).isNotNull();
        assertThat(keysAndCerts.sigCert().getEncoded()).isEqualTo(expected.getEncoded());
        assertThat(HexFormat.of().formatHex(keysAndCerts.sigCert().getEncoded()))
                .isNotBlank();
    }

    @Test
    @DisplayName("initNodeSecurity covers roster-cert mismatch logging path")
    void initNodeSecurityCoversRosterMismatchPath() throws Exception {
        final var config = configure(testDataDirectory.resolve("unused-keys-dir"));
        final var differentCert = KeysAndCertsGenerator.generate(NodeId.of(1)).sigCert();
        final List<RosterEntry> rosterEntries = List.of(rosterEntryWithCert(SELF.id(), differentCert));

        final var keysAndCerts = CryptoStatic.initNodeSecurity(config, SELF, rosterEntries);
        assertThat(keysAndCerts).isNotNull();
        assertThat(keysAndCerts.sigCert().getEncoded()).isNotEqualTo(differentCert.getEncoded());
    }
}
