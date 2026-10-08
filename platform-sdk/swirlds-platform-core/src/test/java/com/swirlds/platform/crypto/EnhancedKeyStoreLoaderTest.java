// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.config.ConfigurationSetupUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.base.crypto.config.CryptoConfig_;
import org.hiero.base.utility.test.fixtures.io.ResourceExtractor;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.test.fixtures.RandomRosterEntryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A suite of unit tests to verify the functionality of the {@link EnhancedKeyStoreLoader} class.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class EnhancedKeyStoreLoaderTest {
    private static final Set<NodeId> NODE_IDS = Set.of(NodeId.of(0), NodeId.of(1), NodeId.of(2));
    private static final NodeId NON_EXISTENT_NODE_ID = NodeId.of(3);

    @TempDir
    Path testDataDirectory;

    @Mock
    private ReadableRosterStore rosterStore;

    @BeforeEach
    void testSetup() throws IOException {
        final ResourceExtractor<EnhancedKeyStoreLoaderTest> loader =
                new ResourceExtractor<>(EnhancedKeyStoreLoaderTest.class);
        final Path tempDir = loader.loadDirectory("com/swirlds/platform/crypto/EnhancedKeyStoreLoader");

        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);

        lenient().when(rosterStore.getActiveRoster()).thenReturn(createRoster());
    }

    /**
     * The purpose of this test is to validate the test data directory structure and the correctness of the
     * {@link BeforeEach} temporary directory setup. This test is not designed to test the key store loader.
     */
    @Test
    @DisplayName("Validate Test Data")
    void validateTestDataDirectory() {
        assertThat(testDataDirectory).exists().isDirectory().isReadable();
        assertThat(testDataDirectory.resolve("legacy-valid")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("legacy-invalid-case")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("hybrid-valid")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("hybrid-invalid-case")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-valid")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-valid-no-agreement-key"))
                .exists()
                .isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-invalid-case")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("config.txt")).exists().isNotEmptyFile();
        assertThat(testDataDirectory.resolve("settings.txt")).exists().isNotEmptyFile();
    }

    /**
     * The Positive tests are designed to test the case where the key store loader is able to scan the key directory,
     * load all public and private keys, pass the verification process, and inject the keys into the address book.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException         if an I/O error occurs during test setup.
     * @throws KeyLoadingException if an error occurs while loading the keys; this should never happen.
     * @throws KeyStoreException   if an error occurs while loading the keys; this should never happen.
     */
    @ParameterizedTest
    @DisplayName("KeyStore Loader Positive Test")
    @ValueSource(
            strings = {
                "legacy-valid",
                "hybrid-valid",
                "enhanced-valid",
                "enhanced-valid-no-agreement-key",
            })
    void keyStoreLoaderPositiveTest(final String directoryName)
            throws IOException, KeyLoadingException, KeyStoreException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);

        final List<RosterEntry> rosterEntries =
                requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(configure(keyDirectory), NODE_IDS, rosterEntries);

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).doesNotThrowAnyException();

        final Map<NodeId, KeysAndCerts> kc = loader.keysAndCerts();
        assertThat(kc).doesNotContainKey(NON_EXISTENT_NODE_ID);
        for (final NodeId nodeId : NODE_IDS) {
            assertThat(kc).containsKey(nodeId);
            assertThat(kc.get(nodeId)).isNotNull();

            final KeysAndCerts keysAndCerts = kc.get(nodeId);
            assertThat(keysAndCerts.agrCert()).isNotNull();
            assertThat(keysAndCerts.sigCert()).isNotNull();
            assertThat(keysAndCerts.agrKeyPair()).isNotNull();
            assertThat(keysAndCerts.sigKeyPair()).isNotNull();
        }
    }

    /**
     * Covers the Ed25519 branch introduced by the ed-keys change at
     * {@code EnhancedKeyStoreLoader.generate()}, where the agreement certificate's signature algorithm is
     * derived from the signing key via {@code SigningSchema.fromKeyType(privateSigningKey).getSigningAlgorithm()}
     * instead of the hard-coded {@code CryptoConstants.SIG_TYPE2} (SHA384withRSA). With an Ed25519 signing key,
     * the previous code would throw inside the JCA signer; the new code must dispatch to {@code "Ed25519"}
     * so the agreement cert is self-consistent.
     *
     * <p>Build-your-own fixture: unlike the static PEM directories the parameterized positive test relies on,
     * this test generates Ed25519 signing keys in-memory, writes only the signing private keys to a temp
     * directory (mimicking the {@code enhanced-valid-no-agreement-key} shape), and seeds the roster entries'
     * {@code gossipCaCertificate} with the matching Ed25519 signing certs. That forces
     * {@code EnhancedKeyStoreLoader#generate()} down the "no agreement key on disk" branch — the one that
     * reads the schema off the signing key — using Ed25519 end-to-end.
     */
    @Test
    @DisplayName("KeyStore Loader Positive Test — Ed25519 signing keys")
    void keyStoreLoaderEd25519PositiveTest() throws Exception {
        final Path keyDirectory = Files.createDirectory(testDataDirectory.resolve("enhanced-valid-ed25519"));

        // Generate Ed25519 signing + agreement material in-memory; keep only the signing key PEMs on disk,
        // so `generate()` has to re-create the agreement cert using the Ed25519 signing key.
        final Map<NodeId, KeysAndCerts> ed25519Material = new HashMap<>();
        for (final NodeId nodeId : NODE_IDS) {
            final KeysAndCerts kac = KeysAndCertsGenerator.generate(nodeId, SigningSchema.ED25519);
            ed25519Material.put(nodeId, kac);
            final String fileName = String.format("s-private-node%d.pem", nodeId.id() + 1);
            EnhancedKeyStoreLoader.writePemFile(
                    true,
                    keyDirectory.resolve(fileName),
                    kac.sigKeyPair().getPrivate().getEncoded());
        }

        // Build a roster whose gossipCaCertificate for each node is the Ed25519 signing cert generated above.
        // This is how `EnhancedKeyStoreLoader#resolveNodeCertificate` finds the public signing cert.
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (final NodeId nodeId : NODE_IDS) {
            rosterEntries.add(RandomRosterEntryBuilder.create(new Random())
                    .withNodeId(nodeId.id())
                    .withSigCert(ed25519Material.get(nodeId).sigCert())
                    .build());
        }

        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(configure(keyDirectory), NODE_IDS, rosterEntries);

        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        // The branch under test: this would have thrown with the old hard-coded "SHA384withRSA" when signing
        // the agreement cert with an Ed25519 key.
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).doesNotThrowAnyException();

        final Map<NodeId, KeysAndCerts> kc = loader.keysAndCerts();
        assertThat(kc).hasSize(NODE_IDS.size());
        for (final NodeId nodeId : NODE_IDS) {
            final KeysAndCerts keysAndCerts = kc.get(nodeId);
            assertThat(keysAndCerts).isNotNull();

            // Signing side is Ed25519 end-to-end. The JCE provider may surface the algorithm as either
            // "Ed25519" or the generic "EdDSA"; SigningSchema.fromKeyType accepts both, and so do we.
            assertThat(keysAndCerts.sigKeyPair().getPrivate().getAlgorithm()).isIn("Ed25519", "EdDSA");
            assertThat(keysAndCerts.sigCert().getPublicKey().getAlgorithm()).isIn("Ed25519", "EdDSA");

            // The agreement cert was signed by the Ed25519 signing key — this is what the branch
            // enables and what the old SHA384withRSA constant would have blocked.
            assertThat(keysAndCerts.agrCert().getSigAlgName()).isIn("Ed25519", "EdDSA");
        }
    }

    /**
     * The Negative Type tests are designed to test the case where the key store loader is able to scan the key
     * directory, but one or more private keys are either corrupt or missing.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException if an I/O error occurs during test setup.
     */
    @ParameterizedTest
    @DisplayName("KeyStore Loader Negative Type Test")
    @ValueSource(strings = {"legacy-invalid-case", "hybrid-invalid-case", "enhanced-invalid-case"})
    void keyStoreLoaderNegativeCase2Test(final String directoryName) throws IOException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);
        final List<RosterEntry> rosterEntries =
                requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(configure(keyDirectory), NODE_IDS, rosterEntries);

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).isInstanceOf(KeyLoadingException.class);
        assertThatCode(loader::keysAndCerts).isInstanceOf(KeyLoadingException.class);
    }

    /**
     * A helper method used to load the {@code settings.txt} configuration file and override the default key directory
     * path with the provided key directory path.
     *
     * @param keyDirectory the key directory path to use.
     * @return a fully initialized configuration object with the key path overridden.
     * @throws IOException if an I/O error occurs while loading the configuration file.
     */
    private Configuration configure(final Path keyDirectory) throws IOException {
        final ConfigurationBuilder builder = ConfigurationBuilder.create();
        ConfigurationSetupUtils.setupConfigBuilder(builder, testDataDirectory.resolve("settings.txt"));

        builder.withValue("paths.keysDirPath", keyDirectory.toAbsolutePath().toString());
        builder.withValue(CryptoConfig_.KEYSTORE_PASSWORD, "password");

        return builder.build();
    }

    // --------------------------------------------------------------------------
    //                       MIGRATION SPECIFIC UNIT TESTS
    // --------------------------------------------------------------------------

    /**
     * The Negative Type 2 tests are designed to test the case where the key store loader is able to scan the key
     * directory, but one or more private keys are either corrupt or missing.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException if an I/O error occurs during test setup.
     */
    @ParameterizedTest
    @DisplayName("Migration Negative Cases Test")
    @ValueSource(strings = {"migration-invalid-missing-private-key"})
    void migrationNegativeCaseTest(final String directoryName) throws IOException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);
        final List<RosterEntry> rosterEntries =
                requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(configure(keyDirectory), NODE_IDS, rosterEntries);

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        // read all files into memory for later comparison.
        final Map<String, byte[]> fileContents = new HashMap<>();
        try (final Stream<Path> paths = Files.list(keyDirectory)) {
            paths.forEach(path -> {
                try {
                    fileContents.put(path.getFileName().toString(), Files.readAllBytes(path));
                } catch (final IOException e) {
                    assert (false);
                }
            });
        }

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();

        // check that the migration rolled back the changes and that the files are identical.
        try (final Stream<Path> paths = Files.list(keyDirectory)) {
            paths.forEach(path -> {
                try {
                    assertThat(Files.readAllBytes(path))
                            .isEqualTo(fileContents.get(path.getFileName().toString()));
                } catch (final IOException e) {
                    assert (false);
                }
            });
        }
    }

    private static Roster createRoster() {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        rosterEntries.add(
                RandomRosterEntryBuilder.create(new Random()).withNodeId(0L).build());
        rosterEntries.add(
                RandomRosterEntryBuilder.create(new Random()).withNodeId(1L).build());
        rosterEntries.add(
                RandomRosterEntryBuilder.create(new Random()).withNodeId(2L).build());
        return new Roster(rosterEntries);
    }
}
