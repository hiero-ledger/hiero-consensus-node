// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.common.test.fixtures.RandomUtils.nextInt;
import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static com.swirlds.common.test.fixtures.RandomUtils.randomInstant;
import static com.swirlds.common.test.fixtures.RandomUtils.randomString;
import static com.swirlds.platform.state.service.PbjConverter.toPbjPlatformState;
import static com.swirlds.platform.state.service.PbjConverter.toPbjTimestamp;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.internal.CryptoUtils;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.time.Instant;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class PbjConverterTest {

    public static final NodeId NODE_ID_1 = NodeId.of(1);
    public static final NodeId NODE_ID_2 = NodeId.of(2);
    private Randotron randotron;

    @BeforeEach
    void setUp() {
        randotron = Randotron.create();
    }

    @Test
    void testToPbjPlatformState() {
        final PlatformStateModifier platformState = randomPlatformState(randotron);

        final com.hedera.hapi.platform.state.PlatformState pbjPlatformState =
                PbjConverter.toPbjPlatformState(platformState);

        assertEquals(
                platformState.getCreationSoftwareVersion().getPbjSemanticVersion(),
                pbjPlatformState.creationSoftwareVersion());
        assertEquals(platformState.getRoundsNonAncient(), pbjPlatformState.roundsNonAncient());
        assertEquals(
                platformState.getLastFrozenTime().getEpochSecond(),
                pbjPlatformState.lastFrozenTime().seconds());
        assertEquals(platformState.getLegacyRunningEventHash().getBytes(), pbjPlatformState.legacyRunningEventHash());
        assertEquals(
                platformState.getLowestJudgeGenerationBeforeBirthRoundMode(),
                pbjPlatformState.lowestJudgeGenerationBeforeBirthRoundMode());
        assertEquals(
                platformState.getFirstVersionInBirthRoundMode().getPbjSemanticVersion(),
                pbjPlatformState.firstVersionInBirthRoundMode());

        assertEquals(platformState.getSnapshot(), pbjPlatformState.consensusSnapshot());
    }

    @Test
    void testToPbjTimestamp_null() {
        assertNull(toPbjTimestamp(null));
    }

    @Test
    void testToPbjTimestamp() {
        final Instant instant = randomInstant(randotron);
        final Timestamp pbjTimestamp = toPbjTimestamp(instant);
        assertEquals(instant.getEpochSecond(), pbjTimestamp.seconds());
    }

    @Test
    void testFromPbjTimestamp_null() {
        assertNull(PbjConverter.fromPbjTimestamp(null));
    }

    @Test
    void testFromPbjTimestamp() {
        final Instant instant = randomInstant(randotron);
        final Timestamp pbjTimestamp = toPbjTimestamp(instant);
        assertEquals(instant, PbjConverter.fromPbjTimestamp(pbjTimestamp));
    }

    @Test
    void testFromPbjAddressBook_null() {
        assertNull(PbjConverter.fromPbjAddressBook(null));
    }

    @Test
    void testFromPbjAddressBook() {
        final com.hedera.hapi.platform.state.AddressBook pbjAddressBook = randomPbjAddressBook();
        final AddressBook addressBook = PbjConverter.fromPbjAddressBook(pbjAddressBook);
        assertAddressBook(addressBook, pbjAddressBook);
    }

    @Test
    void testToPbjPlatformState_acc_updateCreationSoftwareVersion() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.creationSoftwareVersion(),
                toPbjPlatformState(oldState, accumulator).creationSoftwareVersion());

        var newValue = randomSoftwareVersion();

        accumulator.setCreationSoftwareVersion(newValue);

        assertEquals(
                newValue.getPbjSemanticVersion(),
                toPbjPlatformState(oldState, accumulator).creationSoftwareVersion());
    }

    @Test
    void testToPbjPlatformState_acc_updateRoundsNonAncient() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.roundsNonAncient(),
                toPbjPlatformState(oldState, accumulator).roundsNonAncient());

        var newValue = nextInt();

        accumulator.setRoundsNonAncient(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).roundsNonAncient());
    }

    @Test
    void testToPbjPlatformState_acc_freezeTime() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.freezeTime(), toPbjPlatformState(oldState, accumulator).freezeTime());

        var newValue = randomInstant(randotron);

        accumulator.setFreezeTime(newValue);

        assertEquals(
                toPbjTimestamp(newValue),
                toPbjPlatformState(oldState, accumulator).freezeTime());
    }

    @Test
    void testToPbjPlatformState_acc_lastFrozenTime() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.freezeTime(), toPbjPlatformState(oldState, accumulator).freezeTime());

        var newValue = randomInstant(randotron);

        accumulator.setLastFrozenTime(newValue);

        assertEquals(
                toPbjTimestamp(newValue),
                toPbjPlatformState(oldState, accumulator).lastFrozenTime());
    }

    @Test
    void testToPbjPlatformState_acc_legacyRunningEventHash() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.legacyRunningEventHash(),
                toPbjPlatformState(oldState, accumulator).legacyRunningEventHash());

        var newValue = randomHash();

        accumulator.setLegacyRunningEventHash(newValue);

        assertArrayEquals(
                newValue.copyToByteArray(),
                toPbjPlatformState(oldState, accumulator)
                        .legacyRunningEventHash()
                        .toByteArray());
    }

    @Test
    void testToPbjPlatformState_acc_lowestJudgeGenerationBeforeBirthRoundMode() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.lowestJudgeGenerationBeforeBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).lowestJudgeGenerationBeforeBirthRoundMode());

        var newValue = nextInt();

        accumulator.setLowestJudgeGenerationBeforeBirthRoundMode(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).lowestJudgeGenerationBeforeBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_firstVersionInBirthRoundMode() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.firstVersionInBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).firstVersionInBirthRoundMode());

        var newValue = randomSoftwareVersion();

        accumulator.setFirstVersionInBirthRoundMode(newValue);

        assertEquals(
                newValue.getPbjSemanticVersion(),
                toPbjPlatformState(oldState, accumulator).firstVersionInBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_lastRoundBeforeBirthRoundMode() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.lastRoundBeforeBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).lastRoundBeforeBirthRoundMode());

        var newValue = nextInt();

        accumulator.setLastRoundBeforeBirthRoundMode(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).lastRoundBeforeBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_round() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        var newValue = nextInt();

        accumulator.setRound(newValue);

        assertEquals(
                newValue,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_round_and_snapshot() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        var newRound = nextInt();
        var newSnapshot = randomSnapshot(randotron);

        accumulator.setRound(newRound);
        accumulator.setSnapshot(newSnapshot);

        // snapshot fields shouldn't be lost
        assertThat(toPbjPlatformState(oldState, accumulator).consensusSnapshot())
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withIgnoredFields("round")
                        .build())
                .isEqualTo(newSnapshot);
        assertEquals(
                newRound,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_consensusSnapshotTimestamp() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        var newValue = nextInt();

        accumulator.setRound(newValue);

        assertEquals(
                newValue,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_consensusTimestamp_and_snapshot() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        var consensusTimestamp = randomInstant(randotron);
        var newSnapshot = randomSnapshot(randotron);

        accumulator.setConsensusTimestamp(consensusTimestamp);
        accumulator.setSnapshot(newSnapshot);

        // snapshot fields shouldn't be lost
        assertThat(toPbjPlatformState(oldState, accumulator).consensusSnapshot())
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withIgnoredFields("consensusTimestamp")
                        .build())
                .isEqualTo(newSnapshot);
        assertEquals(
                toPbjTimestamp(consensusTimestamp),
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().consensusTimestamp());
    }

    @Test
    void testToPbjPlatformState_acc_updateAll() {
        var oldState = randomPbjPlatformState();
        var accumulator = new PlatformStateValueAccumulator();

        var newValue = randomPlatformState(randotron);

        accumulator.setCreationSoftwareVersion(newValue.getCreationSoftwareVersion());
        accumulator.setRoundsNonAncient(newValue.getRoundsNonAncient());
        accumulator.setSnapshot(newValue.getSnapshot());
        accumulator.setFreezeTime(newValue.getLastFrozenTime());
        accumulator.setLastFrozenTime(newValue.getLastFrozenTime());
        accumulator.setLegacyRunningEventHash(newValue.getLegacyRunningEventHash());
        accumulator.setLowestJudgeGenerationBeforeBirthRoundMode(
                newValue.getLowestJudgeGenerationBeforeBirthRoundMode());
        accumulator.setFirstVersionInBirthRoundMode(newValue.getFirstVersionInBirthRoundMode());
        accumulator.setLastRoundBeforeBirthRoundMode(newValue.getLastRoundBeforeBirthRoundMode());

        var pbjState = toPbjPlatformState(oldState, accumulator);

        assertEquals(newValue.getCreationSoftwareVersion().getPbjSemanticVersion(), pbjState.creationSoftwareVersion());
        assertEquals(newValue.getRoundsNonAncient(), pbjState.roundsNonAncient());
        assertEquals(newValue.getSnapshot(), pbjState.consensusSnapshot());
        assertEquals(toPbjTimestamp(newValue.getLastFrozenTime()), pbjState.freezeTime());
        assertEquals(toPbjTimestamp(newValue.getLastFrozenTime()), pbjState.lastFrozenTime());
        assertArrayEquals(
                newValue.getLegacyRunningEventHash().getBytes().toByteArray(),
                pbjState.legacyRunningEventHash().toByteArray());
        assertEquals(
                newValue.getLowestJudgeGenerationBeforeBirthRoundMode(),
                pbjState.lowestJudgeGenerationBeforeBirthRoundMode());
        assertEquals(
                newValue.getFirstVersionInBirthRoundMode().getPbjSemanticVersion(),
                pbjState.firstVersionInBirthRoundMode());
        assertEquals(newValue.getLastRoundBeforeBirthRoundMode(), pbjState.lastRoundBeforeBirthRoundMode());
    }

    static PlatformStateModifier randomPlatformState(Randotron randotron) {
        final PlatformStateValueAccumulator platformState = new PlatformStateValueAccumulator();
        platformState.setCreationSoftwareVersion(randomSoftwareVersion());
        platformState.setRoundsNonAncient(nextInt());
        platformState.setLastFrozenTime(randomInstant(randotron));
        platformState.setLegacyRunningEventHash(randomHash());
        platformState.setLowestJudgeGenerationBeforeBirthRoundMode(nextInt());
        platformState.setLastRoundBeforeBirthRoundMode(nextInt());
        platformState.setFirstVersionInBirthRoundMode(randomSoftwareVersion());
        platformState.setSnapshot(randomSnapshot(randotron));
        return platformState;
    }

    private com.hedera.hapi.platform.state.PlatformState randomPbjPlatformState() {
        return toPbjPlatformState(randomPlatformState(randotron));
    }

    private com.hedera.hapi.platform.state.ConsensusSnapshot randomPbjSnapshot() {
        Instant instant = randomInstant(randotron);
        return new com.hedera.hapi.platform.state.ConsensusSnapshot(
                nextInt(),
                asList(randomHash().getBytes(), randomHash().getBytes()),
                asList(
                        new com.hedera.hapi.platform.state.MinimumJudgeInfo(nextInt(), nextInt()),
                        new com.hedera.hapi.platform.state.MinimumJudgeInfo(nextInt(), nextInt())),
                nextInt(),
                new Timestamp(instant.getEpochSecond(), instant.getNano()));
    }

    private com.hedera.hapi.platform.state.AddressBook randomPbjAddressBook() {
        return new com.hedera.hapi.platform.state.AddressBook(
                nextInt(),
                new com.hedera.hapi.platform.state.NodeId(NODE_ID_2.id() + 1),
                asList(randomPbjAddress(NODE_ID_1), randomPbjAddress(NODE_ID_2)));
    }

    private void assertAddressBook(AddressBook addressBook, com.hedera.hapi.platform.state.AddressBook pbjAddressBook) {
        assertEquals(addressBook.getRound(), pbjAddressBook.round());
        assertEquals(addressBook.getSize(), pbjAddressBook.addresses().size());
        assertAddress(
                addressBook.getAddress(NODE_ID_1), pbjAddressBook.addresses().get(0));
        assertAddress(
                addressBook.getAddress(NODE_ID_2), pbjAddressBook.addresses().get(1));
    }

    private void assertAddress(Address address, com.hedera.hapi.platform.state.Address pbjAddress) {
        assertEquals(address.getNodeId().id(), pbjAddress.id().id());
        assertEquals(address.getNickname(), pbjAddress.nickname());
        assertEquals(address.getSelfName(), pbjAddress.selfName());
        assertEquals(address.getWeight(), pbjAddress.weight());
        assertEquals(address.getHostnameInternal(), pbjAddress.hostnameInternal());
        assertEquals(address.getPortInternal(), pbjAddress.portInternal());
        assertEquals(address.getHostnameExternal(), pbjAddress.hostnameExternal());
        assertEquals(address.getPortExternal(), pbjAddress.portExternal());
        try {
            assertArrayEquals(
                    address.getAgreeCert().getEncoded(),
                    pbjAddress.agreementCertificate().toByteArray());
            assertArrayEquals(
                    address.getSigCert().getEncoded(),
                    pbjAddress.signingCertificate().toByteArray());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static ConsensusSnapshot randomSnapshot(Randotron randotron) {
        return new ConsensusSnapshot(
                nextInt(),
                asList(randomHash().getBytes(), randomHash().getBytes()),
                asList(new MinimumJudgeInfo(nextInt(), nextInt()), new MinimumJudgeInfo(nextInt(), nextInt())),
                nextInt(),
                PbjConverter.toPbjTimestamp(randomInstant(randotron)));
    }

    static AddressBook randomAddressBook(Randotron randotron) {
        final AddressBook addresses = new AddressBook();
        addresses.setRound(nextInt());
        addresses.add(randomAddress(randotron, NODE_ID_1));
        addresses.add(randomAddress(randotron, NODE_ID_2));

        return addresses;
    }

    private static Address randomAddress(Randotron randotron, NodeId nodeId) {
        return new Address(
                nodeId,
                randomString(randotron, 10),
                randomString(randotron, 10),
                nextInt(),
                randomString(randotron, 10),
                nextInt(),
                randomString(randotron, 10),
                nextInt(),
                randomX509Certificate(),
                randomX509Certificate(),
                randomString(randotron, 10));
    }

    private com.hedera.hapi.platform.state.Address randomPbjAddress(NodeId nodeId) {
        return new com.hedera.hapi.platform.state.Address(
                new com.hedera.hapi.platform.state.NodeId(nodeId.id()),
                randomString(randotron, 10),
                randomString(randotron, 10),
                nextInt(),
                randomString(randotron, 10),
                nextInt(),
                randomString(randotron, 10),
                nextInt(),
                randomEncodedCertificate(),
                randomEncodedCertificate(),
                randomString(randotron, 10));
    }

    private static Bytes randomEncodedCertificate() {
        try {
            return Bytes.wrap(randomX509Certificate().getCertificate().getEncoded());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static SoftwareVersion randomSoftwareVersion() {
        return new BasicSoftwareVersion(nextInt(1, 100));
    }

    private static SerializableX509Certificate randomX509Certificate() {
        try {
            final SecureRandom secureRandom = CryptoUtils.getDetRandom();

            final KeyPairGenerator rsaKeyGen = KeyPairGenerator.getInstance("RSA");
            rsaKeyGen.initialize(3072, secureRandom);
            final KeyPair rsaKeyPair1 = rsaKeyGen.generateKeyPair();

            final String name = "CN=Bob";
            return new SerializableX509Certificate(
                    CryptoStatic.generateCertificate(name, rsaKeyPair1, name, rsaKeyPair1, secureRandom));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
