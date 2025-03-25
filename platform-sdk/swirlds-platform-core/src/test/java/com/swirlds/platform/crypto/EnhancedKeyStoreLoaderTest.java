// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader.loadConfigFile;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.resource.ResourceLoader;
import com.swirlds.platform.util.BootstrapUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A suite of unit tests to verify the functionality of the {@link EnhancedKeyStoreLoader} class.
 */
@Execution(ExecutionMode.CONCURRENT)
class EnhancedKeyStoreLoaderTest {

    @TempDir
    Path testDataDirectory;

    @BeforeEach
    void testSetup() throws IOException {
        final ResourceLoader<EnhancedKeyStoreLoaderTest> loader =
                new ResourceLoader<>(EnhancedKeyStoreLoaderTest.class);
        final Path tempDir = loader.loadDirectory("com/swirlds/platform/crypto/EnhancedKeyStoreLoader");

        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);
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
        assertThat(testDataDirectory.resolve("legacy-invalid-case-1")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("legacy-invalid-case-2")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("hybrid-valid")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("hybrid-invalid-case-1")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("hybrid-invalid-case-2")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-valid")).exists().isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-valid-no-agreement-key"))
                .exists()
                .isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-invalid-case-1"))
                .exists()
                .isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("enhanced-invalid-case-2"))
                .exists()
                .isNotEmptyDirectory();
        assertThat(testDataDirectory.resolve("legacy-valid").resolve("public.pfx"))
                .exists()
                .isNotEmptyFile();
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
        final AddressBook addressBook = addressBook();
        final Set<NodeId> nodesToStart = addressBook.getNodeIdSet();

        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(addressBook, configure(keyDirectory), nodesToStart);

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).doesNotThrowAnyException();
        assertThatCode(loader::injectInAddressBook).doesNotThrowAnyException();

        final Map<NodeId, KeysAndCerts> kc = loader.keysAndCerts();
        for (int i = 0; i < addressBook.getSize(); i++) {
            final NodeId nodeId = addressBook.getNodeId(i);
            final Address addr = addressBook.getAddress(nodeId);

            if (!nodesToStart.contains(addr.getNodeId())) {
                assertThat(kc).doesNotContainKey(nodeId);
            } else {
                assertThat(kc).containsKey(nodeId);
                assertThat(kc.get(nodeId)).isNotNull();

                final KeysAndCerts keysAndCerts = kc.get(nodeId);
                assertThat(keysAndCerts.agrCert()).isNotNull();
                assertThat(keysAndCerts.sigCert()).isNotNull();
                assertThat(keysAndCerts.agrKeyPair()).isNotNull();
                assertThat(keysAndCerts.sigKeyPair()).isNotNull();
            }

            assertThat(addr.getSigPublicKey()).isNotNull();
            assertThat(addr.getAgreePublicKey()).isNull();
        }
    }

    /**
     * The Negative Type 1 tests are designed to test the case where the key store loader is able to scan the key
     * directory, but one or more public keys are either corrupt or missing.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException if an I/O error occurs during test setup.
     */
    @ParameterizedTest
    @DisplayName("KeyStore Loader Negative Type 1 Test")
    @ValueSource(strings = {"legacy-invalid-case-1", "hybrid-invalid-case-1", "enhanced-invalid-case-1"})
    void keyStoreLoaderNegativeCase1Test(final String directoryName) throws IOException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);
        final AddressBook addressBook = addressBook();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(addressBook, configure(keyDirectory), addressBook.getNodeIdSet());

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::verify).isInstanceOf(KeyLoadingException.class);
        assertThatCode(loader::injectInAddressBook).isInstanceOf(KeyLoadingException.class);
        assertThatCode(loader::keysAndCerts).isInstanceOf(KeyLoadingException.class);
    }

    /**
     * The Negative Type 2 tests are designed to test the case where the key store loader is able to scan the key
     * directory, but one or more private keys are either corrupt or missing.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException if an I/O error occurs during test setup.
     */
    @ParameterizedTest
    @DisplayName("KeyStore Loader Negative Type 2 Test")
    @ValueSource(strings = {"legacy-invalid-case-2", "hybrid-invalid-case-2", "enhanced-invalid-case-2"})
    void keyStoreLoaderNegativeCase2Test(final String directoryName) throws IOException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);
        final AddressBook addressBook = addressBook();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(addressBook, configure(keyDirectory), addressBook.getNodeIdSet());

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();
        assertThatCode(loader::scan).doesNotThrowAnyException();
        assertThatCode(loader::generate).doesNotThrowAnyException();
        assertThatCode(loader::verify).isInstanceOf(KeyLoadingException.class);
        assertThatCode(loader::injectInAddressBook).isInstanceOf(KeyLoadingException.class);
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
        BootstrapUtils.setupConfigBuilder(builder, testDataDirectory.resolve("settings.txt"));

        builder.withValue("paths.keysDirPath", keyDirectory.toAbsolutePath().toString());

        return builder.build();
    }

    /**
     * A helper method used to load the {@code config.txt} configuration file and extract the address book.
     *
     * @return the fully initialized address book.
     */
    private AddressBook addressBook() {
        return loadConfigFile(testDataDirectory.resolve("config.txt")).getAddressBook();
    }

    /////////////////////////////////////////////////////////////////////////////
    //////////////////////// MIGRATION SPECIFIC UNIT TESTS //////////////////////
    /////////////////////////////////////////////////////////////////////////////

    /**
     * The Negative Type 2 tests are designed to test the case where the key store loader is able to scan the key
     * directory, but one or more private keys are either corrupt or missing.
     *
     * @param directoryName the directory name containing the test data being used to cover a given test case.
     * @throws IOException if an I/O error occurs during test setup.
     */
    @ParameterizedTest
    @DisplayName("Migration Negative Cases Test")
    @ValueSource(strings = {"migration-invalid-missing-private-key", "migration-invalid-missing-public-key"})
    void migraitonNegativeCaseTest(final String directoryName) throws IOException {
        final Path keyDirectory = testDataDirectory.resolve(directoryName);
        final AddressBook addressBook = addressBook();
        final EnhancedKeyStoreLoader loader =
                EnhancedKeyStoreLoader.using(addressBook, configure(keyDirectory), addressBook.getNodeIdSet());

        assertThat(keyDirectory).exists().isDirectory().isReadable().isNotEmptyDirectory();

        // read all files into memory for later comparison.
        Map<String, byte[]> fileContents = new HashMap<>();
        try (Stream<Path> paths = Files.list(keyDirectory)) {
            paths.forEach(path -> {
                try {
                    fileContents.put(path.getFileName().toString(), Files.readAllBytes(path));
                } catch (IOException e) {
                    assert (false);
                }
            });
        }

        assertThat(loader).isNotNull();
        assertThatCode(loader::migrate).doesNotThrowAnyException();

        // check that the migration rolled back the changes and that the files are identical.
        try (Stream<Path> paths = Files.list(keyDirectory)) {
            paths.forEach(path -> {
                try {
                    assertThat(Files.readAllBytes(path))
                            .isEqualTo(fileContents.get(path.getFileName().toString()));
                } catch (IOException e) {
                    assert (false);
                }
            });
        }
    }
}
