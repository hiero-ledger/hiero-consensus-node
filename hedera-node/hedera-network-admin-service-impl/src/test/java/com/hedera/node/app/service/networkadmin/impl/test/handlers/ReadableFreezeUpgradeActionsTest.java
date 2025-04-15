// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.node.app.service.addressbook.AddressBookHelper.loadResourceFile;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.readCertificatePemFile;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_IMMEDIATE_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.EXEC_TELEMETRY_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions.NOW_FROZEN_MARKER;
import static com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions.UPGRADE_FILE_ID;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.Builder;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeUpgradeActions;
import com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class ReadableFreezeUpgradeActionsTest {
    private static final Timestamp then =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build();

    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";
    private static final Function<String, Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    private static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();
    private static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();

    private Path noiseFileLoc;
    private Path noiseSubFileLoc;
    private Path zipArchivePath; // path to valid.zip test zip file (in zipSourceDir directory)

    @TempDir
    private Path zipSourceDir; // temp directory to place test zip files

    @TempDir
    private File zipOutputDir; // temp directory to place marker files and output of zip extraction

    @TempDir
    private File keysDir;

    @Mock
    private WritableFreezeStore writableFreezeStore;

    @Mock
    private NetworkAdminConfig adminServiceConfig;

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private ReadableFreezeUpgradeActions subject;

    @Mock
    private WritableUpgradeFileStore upgradeFileStore;

    @Mock
    private ReadableStakingInfoStore stakingInfoStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    private Configuration configuration;

    @Mock
    private NodesConfig nodesConfig;

    @Mock
    private ReadableEntityCounters readableEntityCounters;

    private ReadableNodeStore nodeStore;

    private Executor freezeExecutor;

    private X509Certificate certificate;

    @BeforeEach
    void setUp() throws IOException {
        given(configuration.getConfigData(NetworkAdminConfig.class)).willReturn(adminServiceConfig);
        given(configuration.getConfigData(NodesConfig.class)).willReturn(nodesConfig);

        noiseFileLoc = zipOutputDir.toPath().resolve("forgotten.cfg");
        noiseSubFileLoc = zipOutputDir.toPath().resolve("edargpu");

        final var readableNodeState =
                MapReadableKVState.<EntityNumber, Node>builder(NODES_KEY).build();
        given(readableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(readableNodeState);
        nodeStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);

        freezeExecutor = new ForkJoinPool(
                1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, Thread.getDefaultUncaughtExceptionHandler(), true);

        given(entityIdFactory.newFileId(UPGRADE_FILE_ID)).willReturn(FileID.DEFAULT);
        subject = new FreezeUpgradeActions(
                configuration,
                writableFreezeStore,
                freezeExecutor,
                upgradeFileStore,
                nodeStore,
                stakingInfoStore,
                entityIdFactory);

        // set up test zip
        zipSourceDir = Files.createTempDirectory("zipSourceDir");
        zipArchivePath = Path.of(zipSourceDir + "/valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipArchivePath.toFile()))) {
            ZipEntry e = new ZipEntry("garden_path_sentence.txt");
            out.putNextEntry(e);

            String fileContent = "The old man the boats";
            byte[] data = fileContent.getBytes();
            out.write(data, 0, data.length);
            out.closeEntry();
        }
    }

    @Test
    void complainsLoudlyWhenUnableToUnzipArchive() {
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(adminServiceConfig.keysPath()).willReturn(keysDir.toString());

        final Bytes invalidArchive = Bytes.wrap("Not a valid zip archive".getBytes(StandardCharsets.UTF_8));
        subject.extractSoftwareUpgrade(invalidArchive).join();

        assertThat(logCaptor.errorLogs())
                .anyMatch(l -> l.startsWith("Failed to unzip archive for NMT consumption java.io.IOException:" + " "));
        assertThat(logCaptor.errorLogs())
                .anyMatch(l -> l.equals("Manual remediation may be necessary to avoid node ISS"));

        assertThat(new File(zipOutputDir, EXEC_IMMEDIATE_MARKER)).doesNotExist();
    }

    @Test
    void preparesForUpgrade() throws IOException {
        setupNoiseFiles();
        rmIfPresent(EXEC_IMMEDIATE_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(adminServiceConfig.keysPath()).willReturn(keysDir.toString());
        given(adminServiceConfig.exportCandidateRoster()).willReturn(true);
        given(nodesConfig.enableDAB()).willReturn(true);

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractSoftwareUpgrade(realArchive).join();

        assertThat(logCaptor.errorLogs()).anyMatch(l -> l.equals("Node state is empty, which should be impossible"));
        assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
    }

    @Test
    void preparesForUpgradeWithDAB() throws IOException, CertificateException {
        setupNoiseFiles();
        rmIfPresent(EXEC_IMMEDIATE_MARKER);
        setupNodes();

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(adminServiceConfig.keysPath()).willReturn(keysDir.toString());
        given(adminServiceConfig.exportCandidateRoster()).willReturn(true);
        given(nodesConfig.enableDAB()).willReturn(true);

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractSoftwareUpgrade(realArchive).join();

        assertDABFilesCreated(EXEC_IMMEDIATE_MARKER, zipOutputDir.toPath(), keysDir.toPath());
        assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
    }

    @Test
    void preparesForUpgradeWithDAB2() throws IOException, CertificateException {
        setupNoiseFiles();
        rmIfPresent(EXEC_IMMEDIATE_MARKER);
        setupNodes2();

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(adminServiceConfig.keysPath()).willReturn(keysDir.toString());
        given(adminServiceConfig.exportCandidateRoster()).willReturn(true);
        given(nodesConfig.enableDAB()).willReturn(true);

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractSoftwareUpgrade(realArchive).join();

        assertDABFilesCreated2(EXEC_IMMEDIATE_MARKER, zipOutputDir.toPath(), keysDir.toPath());
        assertMarkerCreated(EXEC_IMMEDIATE_MARKER, null);
    }

    @Test
    void upgradesTelemetry() throws IOException {
        rmIfPresent(EXEC_TELEMETRY_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(adminServiceConfig.keysPath()).willReturn(keysDir.toString());

        final Bytes realArchive = Bytes.wrap(Files.readAllBytes(zipArchivePath));
        subject.extractTelemetryUpgrade(realArchive, then).join();

        assertMarkerCreated(EXEC_TELEMETRY_MARKER, then);
    }

    @Test
    void externalizesFreeze() throws IOException {
        rmIfPresent(NOW_FROZEN_MARKER);

        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));

        subject.externalizeFreezeIfUpgradePending();

        assertMarkerCreated(NOW_FROZEN_MARKER, null);
    }

    @Test
    void testCatchUpOnMissedSideEffects() throws IOException {
        final var store = mock(ReadablePlatformStateStore.class);
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        Instant checkpoint = localDateTime.atZone(ZoneId.of("UTC")).toInstant();
        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint.minusMillis(10000));
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));

        assertThatNoException().isThrownBy(() -> subject.isFreezeScheduled(store));
        assertThat(subject.isFreezeScheduled(store)).isTrue();

        rmIfPresent(NOW_FROZEN_MARKER);
        given(adminServiceConfig.upgradeArtifactsPath()).willReturn(zipOutputDir.toString());

        Bytes expectedContent = Bytes.wrap("expected");
        given(upgradeFileStore.getFull(any())).willReturn(expectedContent);

        assertThatNoException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
    }

    @Test
    void invokeMissedUpgradePrepException() {
        final var store = mock(ReadablePlatformStateStore.class);
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));
        subject.isPreparedFileHashValidGiven(null, null);
        assertThatNullPointerException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
        given(writableFreezeStore.updateFileHash()).willReturn(null);
        assertThatNoException().isThrownBy(() -> subject.catchUpOnMissedSideEffects(store));
    }

    @Test
    void validatePreparedFileHashGiven() {
        given(writableFreezeStore.updateFileHash()).willReturn(Bytes.wrap("fake hash"));
        byte[] curSpecialFileHash = new BigInteger("e04fd020ea3a6910a2d808002b30309d", 16).toByteArray();
        byte[] hashFromTxnBody =
                requireNonNull(writableFreezeStore.updateFileHash()).toByteArray();
        assertThat(subject.isPreparedFileHashValidGiven(curSpecialFileHash, hashFromTxnBody))
                .isFalse();
    }

    @Test
    void testIfFreezeIsScheduled() {
        final var store = mock(ReadablePlatformStateStore.class);
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        Instant checkpoint = localDateTime.atZone(ZoneId.of("UTC")).toInstant();

        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint.minusMillis(10000));
        assertThatNoException().isThrownBy(() -> subject.isFreezeScheduled(store));
        assertThat(subject.isFreezeScheduled(store)).isTrue();

        given(store.getFreezeTime()).willReturn(null);
        given(store.getLastFrozenTime()).willReturn(checkpoint.plusSeconds(2));
        assertThat(subject.isFreezeScheduled(store)).isFalse();

        given(store.getFreezeTime()).willReturn(checkpoint);
        given(store.getLastFrozenTime()).willReturn(checkpoint);
        assertThat(subject.isFreezeScheduled(store)).isFalse();
    }

    private void rmIfPresent(final String file) {
        rmIfPresent(zipOutputDir.toPath(), file);
    }

    private static void rmIfPresent(final Path baseDir, final String file) {
        final File f = baseDir.resolve(file).toFile();
        if (f.exists()) {
            boolean deleted = f.delete();
            assert (deleted);
        }
    }

    private void assertMarkerCreated(final String file, final @Nullable Timestamp when) throws IOException {
        assertMarkerCreated(file, when, zipOutputDir.toPath());
    }

    private void assertMarkerCreated(final String file, final @Nullable Timestamp when, final Path baseDir)
            throws IOException {
        final Path filePath = baseDir.resolve(file);
        assertThat(filePath.toFile()).exists();
        final var contents = Files.readString(filePath);
        assertThat(filePath.toFile().delete()).isTrue();

        if (file.equals(EXEC_IMMEDIATE_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished unzipping ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else if (file.equals(EXEC_TELEMETRY_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for telemetry update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l ->
                            (l.startsWith("Finished unzipping ") && l.contains(" bytes for telemetry update into ")));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        } else {
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
        }
        if (when != null) {
            final var writtenEpochSecond = Long.parseLong(contents);
            assertThat(when.seconds()).isEqualTo(writtenEpochSecond);
        } else {
            assertThat(contents).isEqualTo(FreezeUpgradeActions.MARK);
        }
    }

    private void setupNoiseFiles() throws IOException {
        Files.write(
                noiseFileLoc,
                List.of("There, the eyes are", "Sunlight on a broken column", "There, is a tree swinging"));
        Files.write(
                noiseSubFileLoc,
                List.of(
                        "And voices are",
                        "In the wind's singing",
                        "More distant and more solemn",
                        "Than a fading star"));
    }

    private void setupNodes() throws CertificateException, IOException {
        final var pemFileName = "s-public-node1.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        certificate = readCertificatePemFile(pemFilePath);

        final var node1 = new Node(
                1,
                asAccount(0L, 0L, 3),
                "node2",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.1", 1234),
                        V053AddressBookSchema.endpointFor("35.186.191.247", 50211)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.247", 50231)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc1CertificateHash"),
                2,
                false,
                A_COMPLEX_KEY,
                false);
        final var node2 = new Node(
                2,
                asAccount(0L, 0L, 4),
                "node3",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.2", 1245),
                        V053AddressBookSchema.endpointFor("35.186.191.245", 50221)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.245", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc2CertificateHash"),
                4,
                false,
                A_COMPLEX_KEY,
                false);
        final var node3 = new Node(
                3,
                asAccount(0L, 0L, 6),
                "node4",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.3", 1245),
                        V053AddressBookSchema.endpointFor("35.186.191.235", 50221)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.235", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc3CertificateHash"),
                1,
                true,
                A_COMPLEX_KEY,
                false);
        final var node4 = new Node(
                4,
                asAccount(0L, 0L, 8),
                "node5",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.4", 1445),
                        V053AddressBookSchema.endpointFor("test.domain.com", 50225),
                        V053AddressBookSchema.endpointFor("35.186.191.225", 50225)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.225", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc5CertificateHash"),
                8,
                false,
                A_COMPLEX_KEY,
                false);
        final var readableNodeState = MapReadableKVState.<EntityNumber, Node>builder(NODES_KEY)
                .value(new EntityNumber(4), node4)
                .value(new EntityNumber(2), node2)
                .value(new EntityNumber(3), node3)
                .value(new EntityNumber(1), node1)
                .build();
        given(readableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(readableNodeState);
        nodeStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);
        given(readableEntityCounters.getCounterFor(EntityType.NODE)).willReturn(5L);
        subject = new FreezeUpgradeActions(
                configuration,
                writableFreezeStore,
                freezeExecutor,
                upgradeFileStore,
                nodeStore,
                stakingInfoStore,
                entityIdFactory);
        var stakingNodeInfo1 = mock(StakingNodeInfo.class);
        var stakingNodeInfo2 = mock(StakingNodeInfo.class);
        var stakingNodeInfo4 = mock(StakingNodeInfo.class);
        given(stakingNodeInfo1.stake()).willReturn(5L);
        given(stakingNodeInfo2.stake()).willReturn(10L);
        given(stakingNodeInfo4.stake()).willReturn(20L);
        given(stakingInfoStore.get(1)).willReturn(stakingNodeInfo1);
        given(stakingInfoStore.get(2)).willReturn(stakingNodeInfo2);
        given(stakingInfoStore.get(4)).willReturn(stakingNodeInfo4);
    }

    private void assertDABFilesCreated(final String file, final Path baseDir, final Path keyDir)
            throws IOException, CertificateException {
        final Path filePath = baseDir.resolve(file);
        final Path configFilePath = baseDir.resolve("config.txt");
        assertTrue(configFilePath.toFile().exists());
        final var configFile = Files.readString(configFilePath);

        final Path pemFilePath1 = keyDir.resolve("s-public-node2.pem");
        assertTrue(pemFilePath1.toFile().exists());
        final Path pemFilePath2 = keyDir.resolve("s-public-node3.pem");
        assertTrue(pemFilePath2.toFile().exists());
        final Path pemFilePath3 = keyDir.resolve("s-public-node4.pem");
        assertFalse(pemFilePath3.toFile().exists());
        final Path pemFilePath4 = keyDir.resolve("s-public-node5.pem");
        assertTrue(pemFilePath4.toFile().exists());
        final var pemFile1 = readCertificatePemFile(pemFilePath1);
        final var pemFile2 = readCertificatePemFile(pemFilePath2);
        final var pemFile4 = readCertificatePemFile(pemFilePath4);

        final String configContents = new StringBuilder()
                .append("address, 1, 1, node2, 5, 127.0.0.1, 1234, 35.186.191.247, 50211, 0.0.3\n")
                .append("address, 2, 2, node3, 10, 127.0.0.2, 1245, 35.186.191.245, 50221, 0.0.4\n")
                .append("address, 4, 4, node5, 20, 127.0.0.4, 1445, test.domain.com, 50225, 0.0.8\n")
                .toString();
        final byte[] pemFile1Bytes = pemFile1.getEncoded();
        final byte[] pemFile2Bytes = pemFile2.getEncoded();
        final byte[] pemFile4Bytes = pemFile4.getEncoded();

        if (file.equals(EXEC_IMMEDIATE_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished unzipping ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished generating config.txt and pem files into " + baseDir)));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
            assertThat(configFile).isEqualTo(configContents);
            assertArrayEquals(certificate.getEncoded(), pemFile1Bytes);
            assertArrayEquals(certificate.getEncoded(), pemFile2Bytes);
            assertArrayEquals(certificate.getEncoded(), pemFile4Bytes);
        }
    }

    private void setupNodes2() throws CertificateException, IOException {
        final var pemFileName = "s-public-node1.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        certificate = readCertificatePemFile(pemFilePath);

        final var node1 = new Node(
                0,
                asAccount(0L, 0L, 3),
                "node2",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.1", 1234),
                        V053AddressBookSchema.endpointFor("35.186.191.247", 50211)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.247", 50231)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc1CertificateHash"),
                2,
                false,
                A_COMPLEX_KEY,
                false);
        final var node2 = new Node(
                1,
                asAccount(0L, 0L, 4),
                "node3",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.2", 1245),
                        V053AddressBookSchema.endpointFor("35.186.191.245", 50221)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.245", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc2CertificateHash"),
                4,
                false,
                A_COMPLEX_KEY,
                false);
        final var node3 = new Node(
                2,
                asAccount(0L, 0L, 6),
                "node4",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.3", 1245),
                        V053AddressBookSchema.endpointFor("35.186.191.235", 50221)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.235", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc3CertificateHash"),
                1,
                false,
                A_COMPLEX_KEY,
                false);
        final var node4 = new Node(
                3,
                asAccount(0L, 0L, 8),
                "node5",
                List.of(
                        V053AddressBookSchema.endpointFor("127.0.0.4", 1445),
                        V053AddressBookSchema.endpointFor("test.domain.com", 50225),
                        V053AddressBookSchema.endpointFor("35.186.191.225", 50225)),
                List.of(V053AddressBookSchema.endpointFor("45.186.191.225", 50225)),
                Bytes.wrap(certificate.getEncoded()),
                Bytes.wrap("grpc5CertificateHash"),
                8,
                true,
                A_COMPLEX_KEY,
                false);
        final var readableNodeState = MapReadableKVState.<EntityNumber, Node>builder(NODES_KEY)
                .value(new EntityNumber(3), node4)
                .value(new EntityNumber(1), node2)
                .value(new EntityNumber(2), node3)
                .value(new EntityNumber(0), node1)
                .build();
        given(readableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(readableNodeState);
        nodeStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);
        given(readableEntityCounters.getCounterFor(EntityType.NODE)).willReturn(4L);
        subject = new FreezeUpgradeActions(
                configuration,
                writableFreezeStore,
                freezeExecutor,
                upgradeFileStore,
                nodeStore,
                stakingInfoStore,
                entityIdFactory);
        var stakingNodeInfo1 = mock(StakingNodeInfo.class);
        var stakingNodeInfo2 = mock(StakingNodeInfo.class);
        var stakingNodeInfo3 = mock(StakingNodeInfo.class);
        given(stakingNodeInfo1.stake()).willReturn(5L);
        given(stakingNodeInfo2.stake()).willReturn(10L);
        given(stakingNodeInfo3.stake()).willReturn(20L);
        given(stakingInfoStore.get(0)).willReturn(stakingNodeInfo1);
        given(stakingInfoStore.get(1)).willReturn(stakingNodeInfo2);
        given(stakingInfoStore.get(2)).willReturn(stakingNodeInfo3);
    }

    private void assertDABFilesCreated2(final String file, final Path baseDir, final Path keyDir)
            throws IOException, CertificateException {
        final Path filePath = baseDir.resolve(file);
        final Path configFilePath = baseDir.resolve("config.txt");
        assertTrue(configFilePath.toFile().exists());
        final var configFile = Files.readString(configFilePath);

        final Path pemFilePath1 = keyDir.resolve("s-public-node1.pem");
        assertTrue(pemFilePath1.toFile().exists());
        final Path pemFilePath2 = keyDir.resolve("s-public-node2.pem");
        assertTrue(pemFilePath2.toFile().exists());
        final Path pemFilePath3 = keyDir.resolve("s-public-node3.pem");
        assertTrue(pemFilePath3.toFile().exists());
        final Path pemFilePath4 = keyDir.resolve("s-public-node4.pem");
        assertFalse(pemFilePath4.toFile().exists());
        final var pemFile1 = readCertificatePemFile(pemFilePath1);
        final var pemFile2 = readCertificatePemFile(pemFilePath2);
        final var pemFile3 = readCertificatePemFile(pemFilePath3);

        final String configContents = new StringBuilder()
                .append("address, 0, 0, node1, 5, 127.0.0.1, 1234, 35.186.191.247, 50211, 0.0.3\n")
                .append("address, 1, 1, node2, 10, 127.0.0.2, 1245, 35.186.191.245, 50221, 0.0.4\n")
                .append("address, 2, 2, node3, 20, 127.0.0.3, 1245, 35.186.191.235, 50221, 0.0.6\n")
                .toString();
        final byte[] pemFile1Bytes = pemFile1.getEncoded();
        final byte[] pemFile2Bytes = pemFile2.getEncoded();
        final byte[] pemFile3Bytes = pemFile3.getEncoded();

        if (file.equals(EXEC_IMMEDIATE_MARKER)) {
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("About to unzip ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished unzipping ")
                            && l.contains(" bytes for software update into " + baseDir)));
            assertThat(logCaptor.infoLogs())
                    .anyMatch(l -> (l.startsWith("Finished generating config.txt and pem files into " + baseDir)));
            assertThat(logCaptor.infoLogs()).anyMatch(l -> (l.contains("Wrote marker " + filePath)));
            assertThat(configFile).isEqualTo(configContents);
            assertArrayEquals(certificate.getEncoded(), pemFile1Bytes);
            assertArrayEquals(certificate.getEncoded(), pemFile2Bytes);
            assertArrayEquals(certificate.getEncoded(), pemFile3Bytes);
        }
    }
}
