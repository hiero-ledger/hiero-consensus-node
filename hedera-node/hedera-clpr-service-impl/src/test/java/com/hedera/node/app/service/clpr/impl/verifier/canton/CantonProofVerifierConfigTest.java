// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests of {@code CantonProofVerifier.verifyConfig}.
 *
 * <h2>Provenance (REAL vs CONSTRUCTED)</h2>
 * No real config proof exists: {@code CantonVerifier.t.sol} builds its config proofs at test time with
 * {@code CantonProofBuilder.sol} from synthetic keys, and the LocalNet transaction's views are encrypted (see
 * {@link CantonProofVerifierBundleTest}). Every proof here is assembled by {@link Cfg}, a Java port of that
 * builder, so every test is a round trip of constructed parts. The verdict typed content is the real Canton verdict
 * ({@code verdict-typed-content.hex}) with only its root hash replaced by the root of the constructed tree. The
 * mediator, mediator set, descriptor, view data, {@code ChannelConfig} contract and manifest are constructed. Not
 * byte-identical to a Solidity run.
 */
class CantonProofVerifierConfigTest {
    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] TYPED = CantonFixtures.verdictTypedContent();
    private static final CantonProto.Verdict VERDICT = CantonProto.parseVerdict(TYPED);
    private static final String SYNC = new String(VERDICT.synchronizerId(), StandardCharsets.UTF_8);

    private static final byte[] PACKAGE_ID = repeat((byte) 0x5a, 32);
    private static final byte[] OPERATOR = utf8("operator::1220" + "ab".repeat(32));
    private static final byte[] SERVICE_ADDRESS = concat(PACKAGE_ID, OPERATOR);
    private static final String APP = "app::1220" + "cd".repeat(32);
    private static final String OTHER = "other::1220" + "ef".repeat(32);
    private static final byte[] CHANNEL_ID = repeat((byte) 0x11, 32);
    private static final byte[] SUBMITTER = sha("submitter-metadata");
    private static final byte[] PARTICIPANT = sha("participant-metadata");
    private static final int GROUP = 7;
    private static final byte[] OWNER_SET = repeat((byte) 0x77, 40);

    private static final String PEER_CHAIN = "canton:localnet";
    private static final String LOCAL_CHAIN = "eip155:298";
    private static final long NANOS = 1_700_000_000_123_456_789L;
    private static final long[] THROTTLES = {50, 1_000_000, 5_000_000, 100, 10_000_000, 20, 30};

    private static final Med MEDIATOR = Med.of(9);
    private static final byte[] MEDIATOR_SET = MEDIATOR.set();

    private static final byte[] MANIFEST_BYTES = ClprEndpointManifest.PROTOBUF
            .toBytes(ClprEndpointManifest.newBuilder()
                    .version(2)
                    .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                    .endpoints(ClprEndpoint.newBuilder().build())
                    .build())
            .toByteArray();
    private static final String MANIFEST_HASH = HEX.formatHex(MiscCryptoUtils.keccak256DigestOf(MANIFEST_BYTES));

    // ── config proof: happy path ─────────────────────────────────────────────

    @Test
    void configProofIsAcceptedAndReturnsTheDeclaredConfigAndAnchor() {
        final VerifiedConfig v = verify(new Cfg());
        final ClprLedgerConfiguration c = v.config();
        assertThat(c.chainId()).isEqualTo(PEER_CHAIN);
        assertThat(c.serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDRESS));
        assertThat(c.timestamp().seconds()).isEqualTo(1_700_000_000L);
        assertThat(c.timestamp().nanos()).isEqualTo(123_456_789);
        assertThat(c.throttles().maxMessagesPerBundle()).isEqualTo(50);
        assertThat(c.throttles().maxMessagePayloadBytes()).isEqualTo(1_000_000);
        assertThat(c.throttles().maxGasPerMessage()).isEqualTo(5_000_000L);
        assertThat(c.throttles().maxQueueDepth()).isEqualTo(100);
        assertThat(c.throttles().maxSyncBytes()).isEqualTo(10_000_000L);
        assertThat(c.throttles().maxLocalEndpoints()).isEqualTo(20);
        assertThat(c.throttles().maxPeerEndpoints()).isEqualTo(30);

        final CantonAnchor.Anchor a =
                CantonAnchor.Anchor.decode(c.initialTrustAnchor().toByteArray());
        assertThat(a.synchronizerId()).isEqualTo(SYNC);
        assertThat(a.lastRequestId()).isEqualTo(VERDICT.requestId());
        assertThat(a.mediatorGroup()).isEqualTo(GROUP);
        assertThat(a.synchronizerNamespace()).isEqualTo(namespaceOf(SYNC));
        assertThat(a.ownersHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(OWNER_SET));
        assertThat(a.mediatorsHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(MEDIATOR_SET));
        assertThat(a.successorSynchronizerId()).isEmpty();
        assertThat(a.upgradeTime()).isZero();
        assertThat(a.lsuSerial()).isZero();
        assertThat(a.app()).isEqualTo(APP);
        assertThat(c.initialTrustAnchorId().toByteArray()).isEqualTo(uint64(VERDICT.requestId()));
    }

    @Test
    void emptyManifestProofReturnsTheUninitializedManifest() {
        final ClprEndpointManifest m = verify(new Cfg()).manifest();
        assertThat(m.version()).isZero();
        assertThat(m.serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDRESS));
        assertThat(m.endpoints()).isEmpty();
    }

    @Test
    void v32ParticipantDataIsAccepted() {
        assertThat(verify(new Cfg().vpdVersion(32)).config().chainId()).isEqualTo(PEER_CHAIN);
    }

    // ── config proof: the view ───────────────────────────────────────────────

    @Test
    void rootActionMustBeOpenChannel() {
        assertRejects("WrongAction", new Cfg().choice("SendMessage"));
        assertRejects("WrongAction", new Cfg().entity("Channel"));
        // UpdateEndpointManifest is only accepted in a manifest proof, never as the config.
        assertRejects("WrongAction", new Cfg().choice("UpdateEndpointManifest").entity("Channel"));
    }

    @Test
    void channelConfigMustNamePeerChainOfThisLedger() {
        assertRejects("WrongPeerChain", new Cfg().contractPeerChain("canton:other"));
    }

    @Test
    void channelConfigMustCarryTheDeclaredConfigTimeAndThrottles() {
        assertRejects("WrongContract", new Cfg().contractNanos(NANOS + 1));
        for (int i = 0; i < 7; ++i) {
            final long[] t = THROTTLES.clone();
            t[i] += 1;
            assertRejects("WrongContract", new Cfg().contractThrottles(t));
        }
    }

    @Test
    void channelConfigMustBeSignedByItsApp() {
        assertRejects("WrongContract", new Cfg().signatory(OTHER));
    }

    @Test
    void theOperatorCannotBeTheApp() {
        final String operator = new String(OPERATOR, StandardCharsets.UTF_8);
        // The operator signs it too, so only the operator check can reject it.
        assertRejects("WrongContract", new Cfg().contractApp(operator).signatory(operator));
    }

    @Test
    void channelConfigMustBelongToThisChannelAndOperator() {
        assertRejects("WrongContract", new Cfg().contractChannelHex("22".repeat(32)));
        assertRejects("WrongContract", new Cfg().contractOperator(OTHER));
    }

    @Test
    void appMustBeARequiredConfirmer() {
        assertRejects("ApproverMissing", new Cfg().informee(OTHER));
        // weight 1 of threshold 1 with another informee of weight 1: the app alone is not required.
        assertRejects("ApproverMissing", new Cfg().quorum(1, 1, 1));
    }

    @Test
    void createdIndexMustExist() {
        assertRejects("WrongContract", new Cfg().createdIndex(new byte[] {1}));
        assertRejects("WrongContract", new Cfg().createdIndex(repeat((byte) 0xff, 32)));
    }

    @Test
    void rolledBackViewIsRejected() {
        assertRejects("RolledBack", new Cfg().vpdVersion(32).rolledBack());
    }

    @Test
    void manifestHashOfTheConfigMustBeEmptyOrThirtyTwoBytes() {
        verify(new Cfg().contractManifestHash(MANIFEST_HASH));
        assertRejects("WrongContract", new Cfg().contractManifestHash("abcd"));
    }

    // ── config proof: the declaration ────────────────────────────────────────

    @Test
    void descriptorItemsFourToNineMustBeEmpty() {
        for (int i = 4; i < 10; ++i) {
            assertRejects("AuthorizationChainNotSupported", new Cfg().descriptorItem(i, rlpString(new byte[] {1})));
        }
        // An empty list is as empty as an empty string.
        verify(new Cfg().descriptorItem(5, rlpList()));
    }

    @Test
    void descriptorMustHaveTenItems() {
        assertRejects("InvalidPayloadShape", new Cfg().descriptorItems(9));
        assertRejects("InvalidPayloadShape", new Cfg().descriptorItems(11));
    }

    @Test
    void badServiceAddressIsInvalidServiceAddress() {
        assertRejects("InvalidServiceAddress", new Cfg().serviceAddress(concat(PACKAGE_ID, utf8("operator"))));
        assertRejects("InvalidServiceAddress", new Cfg().serviceAddress(PACKAGE_ID));
        assertRejects("InvalidServiceAddress", new Cfg().serviceAddress(new byte[0]));
    }

    @Test
    void badChainIdIsInvalidChainId() {
        for (final String bad : new String[] {
            "", "canton:", "canton", "ethereum:1", "canton:" + "a".repeat(33), "canton:a b", "canton:a.b", "Canton:x"
        }) {
            assertRejects("InvalidChainId", new Cfg().chainId(bad));
        }
        verify(new Cfg().chainId("canton:" + "A-z_9".repeat(6) + "ab"));
    }

    @Test
    void badConfigIsInvalidConfig() {
        assertRejects("InvalidConfig", new Cfg().declaredThrottleCount(6));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleCount(8));
        final long[] zero = THROTTLES.clone();
        zero[0] = 0;
        assertRejects("InvalidConfig", new Cfg().declaredThrottles(zero).contractThrottles(zero));
        assertRejects("InvalidConfig", new Cfg().declaredNanos(BigInteger.ONE.shiftLeft(96)));
        assertRejects("InvalidConfig", new Cfg().declaredNanos(BigInteger.ONE.shiftLeft(63)));
        assertRejects("InvalidConfig", new Cfg().group(BigInteger.ONE.shiftLeft(32)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(0, BigInteger.ONE.shiftLeft(32)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(1, BigInteger.ONE.shiftLeft(32)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(2, BigInteger.ONE.shiftLeft(64)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(3, BigInteger.ONE.shiftLeft(32)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(4, BigInteger.ONE.shiftLeft(64)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(5, BigInteger.ONE.shiftLeft(32)));
        assertRejects("InvalidConfig", new Cfg().declaredThrottleValue(6, BigInteger.ONE.shiftLeft(32)));
    }

    @Test
    void badSynchronizerIdIsInvalidSynchronizerId() {
        assertRejects("InvalidSynchronizerId", new Cfg().descriptorSync("global-domain"));
        assertRejects("InvalidSynchronizerId", new Cfg().descriptorSync("global-domain::1220" + "ab".repeat(32)));
        assertRejects("InvalidSynchronizerId", new Cfg().descriptorSync("::1220" + "ab".repeat(32) + "::35-0"));
        assertRejects("InvalidSynchronizerId", new Cfg().descriptorSync("d::1220" + "ab".repeat(32) + "::x5-0"));
        assertRejects("InvalidSynchronizerId", new Cfg().descriptorSync("d::1220" + "ab".repeat(32) + "::35"));
    }

    @Test
    void verdictMustBeSignedUnderTheDeclaredMediatorSetAndSynchronizer() {
        final byte[] otherSet = Med.of(10).set();
        assertThatThrownBy(() -> verify(new Cfg().descriptorMediatorSet(otherSet)))
                .isInstanceOf(CantonProofException.class);
        // The synchronizer the descriptor declares is the one the verdict must be on.
        assertThatThrownBy(() -> verify(new Cfg().descriptorSync(SYNC.replace("1220", "1221"))))
                .isInstanceOf(CantonProofException.class);
    }

    @Test
    void openingMustHashToTheVerdictsRootAndDeclaredGroup() {
        assertRejects("MetadataMismatch", new Cfg().group(BigInteger.valueOf(GROUP + 1)));
        assertRejects("RootHashMismatch", new Cfg().tamperVpdAfterSigning());
    }

    @Test
    void proofMustHaveSevenItems() {
        final var cfg = new Cfg();
        assertThatThrownBy(() -> CantonProofVerifier.verifyConfig(
                        rlpList(rlpString(SERVICE_ADDRESS)), CHANNEL_ID, new byte[0], utf8(LOCAL_CHAIN)))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidPayloadShape"));
        assertThatThrownBy(() ->
                        CantonProofVerifier.verifyConfig(cfg.proof(), new byte[31], new byte[0], utf8(LOCAL_CHAIN)))
                .isInstanceOf(CantonProofException.class);
    }

    // ── manifest proof ───────────────────────────────────────────────────────

    @Test
    void manifestProofReachingThisChannelsConfigReturnsTheDecodedManifest() {
        // A Channel.UpdateEndpointManifest transaction that created a ChannelConfig carrying the manifest's hash.
        final var tx =
                new Cfg().entity("Channel").choice("UpdateEndpointManifest").contractManifestHash(MANIFEST_HASH);
        final ClprEndpointManifest m =
                verify(new Cfg(), tx.manifestProof(MANIFEST_BYTES)).manifest();
        assertThat(m.version()).isEqualTo(2);
        assertThat(m.serviceAddress()).isEqualTo(Bytes.wrap(SERVICE_ADDRESS));
        assertThat(m.endpoints()).hasSize(1);
    }

    @Test
    void manifestProofMayAlsoComeFromOpenChannel() {
        final var tx = new Cfg().contractManifestHash(MANIFEST_HASH);
        assertThat(verify(new Cfg(), tx.manifestProof(MANIFEST_BYTES))
                        .manifest()
                        .version())
                .isEqualTo(2);
    }

    @Test
    void manifestProofRejectsOtherActions() {
        final var cfg = new Cfg();
        assertRejectsManifest(
                "WrongAction",
                cfg,
                new Cfg().choice("SendMessage").entity("Channel").contractManifestHash(MANIFEST_HASH));
        // OpenChannel on the Channel template is neither accepted choice of its entity.
        assertRejectsManifest("WrongAction", cfg, new Cfg().entity("Channel").contractManifestHash(MANIFEST_HASH));
    }

    @Test
    void manifestProofRejectsVersionZero() {
        final byte[] zero = ClprEndpointManifest.PROTOBUF
                .toBytes(ClprEndpointManifest.newBuilder()
                        .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                        .build())
                .toByteArray();
        final var cfg = new Cfg();
        final var proof = new Cfg().contractManifestHash(HEX.formatHex(MiscCryptoUtils.keccak256DigestOf(zero)));
        assertRejectsManifest("ManifestMismatch", cfg, proof, zero);
    }

    @Test
    void manifestProofRejectsHashAndServiceAddressMismatch() {
        final var cfg = new Cfg();
        final var proof = new Cfg().contractManifestHash(MANIFEST_HASH);
        // The preimage is not the committed one.
        assertRejectsManifest("ManifestMismatch", cfg, proof, concat(MANIFEST_BYTES, new byte[] {0}));
        // The config commits to no manifest at all.
        assertRejectsManifest("ManifestMismatch", cfg, new Cfg(), MANIFEST_BYTES);
        // The manifest names another service.
        final byte[] other = ClprEndpointManifest.PROTOBUF
                .toBytes(ClprEndpointManifest.newBuilder()
                        .version(2)
                        .serviceAddress(Bytes.wrap(concat(PACKAGE_ID, utf8("x::1220" + "00".repeat(32)))))
                        .build())
                .toByteArray();
        assertRejectsManifest(
                "ManifestMismatch",
                cfg,
                new Cfg().contractManifestHash(HEX.formatHex(MiscCryptoUtils.keccak256DigestOf(other))),
                other);
    }

    @Test
    void manifestProofMustReachTheSameAppAndChain() {
        final var cfg = new Cfg();
        assertRejectsManifest(
                "WrongContract",
                cfg,
                new Cfg().contractApp(OTHER).signatory(OTHER).informee(OTHER).contractManifestHash(MANIFEST_HASH));
        assertRejectsManifest(
                "WrongPeerChain",
                cfg,
                new Cfg().contractPeerChain("canton:other").contractManifestHash(MANIFEST_HASH));
    }

    @Test
    void manifestProofMustHaveFourItems() {
        final var cfg = new Cfg();
        assertThatThrownBy(() -> CantonProofVerifier.verifyConfig(
                        cfg.proof(), CHANNEL_ID, rlpList(rlpString(new byte[] {1})), utf8(LOCAL_CHAIN)))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidPayloadShape"));
    }

    @Test
    void manifestProofVerdictMustBeValid() {
        final var tx = new Cfg().contractManifestHash(MANIFEST_HASH);
        final byte[] badlySigned = tx.manifestProofWithBadSignature(MANIFEST_BYTES);
        assertThatThrownBy(() -> verify(new Cfg(), badlySigned)).isInstanceOf(CantonProofException.class);
    }

    // ── the ClprVerifier surface (HandleContext) ─────────────────────────────

    @Test
    void clprVerifierReadsTheLocalChainIdFromTheLedgerConfiguration() {
        final var cfg = new Cfg();
        final var context = contextWithChain(LOCAL_CHAIN);
        final VerifiedConfig v = new CantonProofVerifier()
                .verifyConfig(Bytes.wrap(cfg.proof()), Bytes.wrap(CHANNEL_ID), Bytes.EMPTY, context);
        assertThat(v.config().chainId()).isEqualTo(PEER_CHAIN);
        assertThat(v.manifest().version()).isZero();
    }

    @Test
    void clprVerifierMapsAnyFailureToConfigFailed() {
        final var cfg = new Cfg();
        // Another local chain id than the one the ChannelConfig names.
        assertConfigFailed(Bytes.wrap(cfg.proof()), Bytes.wrap(CHANNEL_ID), contextWithChain("eip155:1"));
        // Not even RLP.
        assertConfigFailed(
                Bytes.wrap(new byte[] {(byte) 0xff, 1}), Bytes.wrap(CHANNEL_ID), contextWithChain(LOCAL_CHAIN));
        // No ledger configuration to read.
        assertConfigFailed(Bytes.wrap(cfg.proof()), Bytes.wrap(CHANNEL_ID), mock(HandleContext.class));
        // A channel id that is not 32 bytes.
        assertConfigFailed(Bytes.wrap(cfg.proof()), Bytes.wrap(new byte[31]), contextWithChain(LOCAL_CHAIN));
    }

    private static HandleContext contextWithChain(final String chainId) {
        final var store = mock(ReadableLedgerConfigurationStore.class);
        when(store.getConfiguration())
                .thenReturn(
                        ClprLedgerConfiguration.newBuilder().chainId(chainId).build());
        final var storeFactory = mock(StoreFactory.class);
        when(storeFactory.readableStore(ReadableLedgerConfigurationStore.class)).thenReturn(store);
        final var context = mock(HandleContext.class);
        when(context.storeFactory()).thenReturn(storeFactory);
        return context;
    }

    private static void assertConfigFailed(final Bytes proof, final Bytes channelId, final HandleContext context) {
        assertThatThrownBy(() -> new CantonProofVerifier().verifyConfig(proof, channelId, Bytes.EMPTY, context))
                .isInstanceOf(HandleException.class)
                .satisfies(e -> assertThat(((HandleException) e).getStatus())
                        .isEqualTo(ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED));
    }

    // ── assertions ───────────────────────────────────────────────────────────

    private static VerifiedConfig verify(final Cfg c) {
        return CantonProofVerifier.verifyConfig(c.proof(), CHANNEL_ID, new byte[0], utf8(LOCAL_CHAIN));
    }

    private static VerifiedConfig verify(final Cfg c, final byte[] manifestProof) {
        return CantonProofVerifier.verifyConfig(c.proof(), CHANNEL_ID, manifestProof, utf8(LOCAL_CHAIN));
    }

    private static void assertRejects(final String errorName, final Cfg c) {
        assertThatThrownBy(() -> verify(c))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo(errorName));
    }

    private static void assertRejectsManifest(final String errorName, final Cfg config, final Cfg manifestTx) {
        assertRejectsManifest(errorName, config, manifestTx, MANIFEST_BYTES);
    }

    private static void assertRejectsManifest(
            final String errorName, final Cfg config, final Cfg manifestTx, final byte[] preimage) {
        final byte[] proof = manifestTx.manifestProof(preimage);
        assertThatThrownBy(() -> verify(config, proof))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo(errorName));
    }

    private static byte[] namespaceOf(final String syncId) {
        final int at = syncId.indexOf("::1220") + 6;
        return HEX.parseHex(syncId.substring(at, at + 64));
    }

    private static byte[] uint64(final long v) {
        return java.nio.ByteBuffer.allocate(8).putLong(v).array();
    }

    private static byte[] uintBytes(final BigInteger v) {
        final byte[] raw = v.toByteArray();
        int i = 0;
        while (i < raw.length && raw[i] == 0) {
            ++i;
        }
        return Arrays.copyOfRange(raw, i, raw.length);
    }

    // ── config proof assembly ────────────────────────────────────────────────

    /**
     * Builds a config proof, or (with {@link #manifestProof}) a manifest proof, whose transaction creates a
     * {@code ChannelConfig}; the root of the tree is signed by {@link #MEDIATOR}. Every knob defaults to the valid
     * value and a test breaks one.
     */
    private static final class Cfg {
        private int vpdVersion = 31;
        private String choice = "OpenChannel";
        private String entity = "ClprApp";
        private String informee = APP;
        private long appWeight = 1;
        private long otherWeight = 0;
        private long threshold = 1;
        private boolean rolledBack;
        private boolean tamper;
        private byte[] createdIndex = new byte[0];

        private byte[] serviceAddress = SERVICE_ADDRESS;
        private String chainId = PEER_CHAIN;
        private BigInteger declaredNanos = BigInteger.valueOf(NANOS);
        private BigInteger[] declaredThrottles =
                Arrays.stream(THROTTLES).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
        private BigInteger group = BigInteger.valueOf(GROUP);
        private String descriptorSync = SYNC;
        private byte[] descriptorMediatorSet = MEDIATOR_SET;
        private final byte[][] descriptorTail = new byte[6][];
        private int descriptorLength = 10;
        private int declaredThrottleCount = 7;

        // the ChannelConfig contract
        private String operator = new String(OPERATOR, StandardCharsets.UTF_8);
        private String channelHex = HEX.formatHex(CHANNEL_ID);
        private String contractPeerChain = LOCAL_CHAIN;
        private long contractNanos = NANOS;
        private long[] contractThrottles = THROTTLES;
        private String contractManifestHash = "";
        private String contractApp = APP;
        private String signatory = APP;

        Cfg() {
            Arrays.fill(descriptorTail, new byte[] {(byte) 0x80});
        }

        Cfg vpdVersion(final int v) {
            vpdVersion = v;
            return this;
        }

        Cfg choice(final String c) {
            choice = c;
            return this;
        }

        Cfg entity(final String e) {
            entity = e;
            return this;
        }

        Cfg informee(final String p) {
            informee = p;
            return this;
        }

        Cfg quorum(final long app, final long other, final long thr) {
            appWeight = app;
            otherWeight = other;
            threshold = thr;
            return this;
        }

        Cfg rolledBack() {
            rolledBack = true;
            return this;
        }

        Cfg tamperVpdAfterSigning() {
            tamper = true;
            return this;
        }

        Cfg createdIndex(final byte[] big) {
            createdIndex = big;
            return this;
        }

        Cfg serviceAddress(final byte[] a) {
            serviceAddress = a;
            return this;
        }

        Cfg chainId(final String c) {
            chainId = c;
            return this;
        }

        Cfg declaredNanos(final BigInteger v) {
            declaredNanos = v;
            return this;
        }

        Cfg declaredThrottles(final long[] t) {
            declaredThrottles = Arrays.stream(t).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
            return this;
        }

        Cfg declaredThrottleValue(final int i, final BigInteger v) {
            declaredThrottles = declaredThrottles.clone();
            declaredThrottles[i] = v;
            return this;
        }

        Cfg declaredThrottleCount(final int n) {
            declaredThrottleCount = n;
            return this;
        }

        Cfg group(final BigInteger g) {
            group = g;
            return this;
        }

        Cfg descriptorSync(final String s) {
            descriptorSync = s;
            return this;
        }

        Cfg descriptorMediatorSet(final byte[] s) {
            descriptorMediatorSet = s;
            return this;
        }

        Cfg descriptorItem(final int i, final byte[] rlp) {
            descriptorTail[i - 4] = rlp;
            return this;
        }

        Cfg descriptorItems(final int n) {
            descriptorLength = n;
            return this;
        }

        Cfg contractPeerChain(final String c) {
            contractPeerChain = c;
            return this;
        }

        Cfg contractNanos(final long n) {
            contractNanos = n;
            return this;
        }

        Cfg contractThrottles(final long[] t) {
            contractThrottles = t;
            return this;
        }

        Cfg contractManifestHash(final String h) {
            contractManifestHash = h;
            return this;
        }

        Cfg contractApp(final String a) {
            contractApp = a;
            return this;
        }

        Cfg signatory(final String s) {
            signatory = s;
            return this;
        }

        Cfg contractChannelHex(final String h) {
            channelHex = h;
            return this;
        }

        Cfg contractOperator(final String o) {
            operator = o;
            return this;
        }

        // ── the transaction

        private byte[] vcd() {
            final Pb pb = new Pb().field(2, utf8(informee));
            if (otherWeight > 0) {
                pb.field(2, utf8(OTHER));
            }
            final Pb quorum =
                    new Pb().field(1, new Pb().varint(1, 0).varint(2, appWeight).build());
            if (otherWeight > 0) {
                quorum.field(1, new Pb().varint(1, 1).varint(2, otherWeight).build());
            }
            quorum.varint(2, threshold);
            return pb.field(3, quorum.build()).build();
        }

        private byte[] vpd() {
            final byte[] template = utf8(HEX.formatHex(PACKAGE_ID) + ":Clpr.Service:" + entity);
            final byte[] ex =
                    new Pb().field(2, utf8(choice)).field(10, template).build();
            final Pb pb = new Pb();
            pb.field(3, Pb.lenField(1, channelConfig()));
            pb.field(6, Pb.lenField(2, ex));
            if (rolledBack) {
                pb.varint(8, 1);
            }
            return pb.build();
        }

        /** The {@code ChannelConfig [operator, channelId, peerChainId, configTimeNanos, throttles, manifestHash, app]}. */
        private byte[] channelConfig() {
            final Pb throttles = new Pb();
            for (final long t : contractThrottles) {
                throttles.field(1, Pb.lenField(1, int64(t)));
            }
            final byte[][] values = {
                Pb.lenField(7, utf8(operator)),
                text(channelHex),
                text(contractPeerChain),
                int64(contractNanos),
                Pb.lenField(13, throttles.build()),
                text(contractManifestHash),
                Pb.lenField(7, utf8(contractApp)),
            };
            final Pb record = new Pb();
            for (final byte[] v : values) {
                record.field(1, Pb.lenField(1, v));
            }
            final byte[] templateId = CantonProto.encodeIdentifier(
                    utf8(HEX.formatHex(PACKAGE_ID)), utf8("Clpr"), utf8("Service"), utf8("ChannelConfig"));
            final byte[] fat = new Pb()
                    .field(3, templateId)
                    .field(4, Pb.lenField(13, record.build()))
                    .field(5, utf8(signatory))
                    .build();
            return Pb.lenField(2, fat);
        }

        private static byte[] text(final String s) {
            return Pb.lenField(8, utf8(s));
        }

        private static byte[] int64(final long v) {
            return new Pb().varint(3, v << 1).build();
        }

        private record Tree(byte[] opening, byte[] root) {}

        private Tree tree() {
            final byte[] vcd = vcd();
            final byte[] vpd = vpd();
            final byte[] cm = commonMetadata();
            final byte[] view = CantonMerkle.inner2(leafHash(8, vcd, 30), leafHash(9, vpd, vpdVersion));
            final byte[] root = CantonMerkle.inner(
                    new byte[][] {SUBMITTER, leafHash(6, cm, 30), PARTICIPANT, CantonMerkle.inner1(view)});
            final byte[] shownVpd = tamper ? Arrays.copyOf(vpd, vpd.length + 1) : vpd;
            final byte[] opening = rlpList(
                    rlpString(SUBMITTER),
                    leafRlp(cm, 30),
                    rlpString(PARTICIPANT),
                    rlpList(leafRlp(vcd, 30), leafRlp(shownVpd, vpdVersion)),
                    rlpList(rlpList(rlpList(), rlpList())));
            return new Tree(opening, root);
        }

        /** CommonMetadata{3: synchronizer id, 5: mediator group}. */
        private static byte[] commonMetadata() {
            return concat(
                    Pb.lenField(1, Pb.lenField(2, sha("cm-salt"))),
                    Pb.lenField(3, utf8(SYNC)),
                    Pb.lenField(4, utf8("uuid-0000")),
                    new Pb().varint(5, GROUP).build());
        }

        private byte[] signedVerdict(final boolean badSignature) {
            final byte[] typed = replace(TYPED, VERDICT.rootHash(), tree().root());
            final byte[] sig = MEDIATOR.sign(CantonMerkle.verdictSigningInput(typed));
            if (badSignature) {
                sig[0] ^= 1;
            }
            final byte[] entry = rlpList(rlpString(MEDIATOR.fp()), rlpString(sig), new byte[] {(byte) 0x80});
            return rlpList(rlpString(typed), rlpList(entry));
        }

        private byte[] descriptor() {
            final byte[][] items = new byte[descriptorLength][];
            final byte[][] head = {
                rlpString(utf8(descriptorSync)),
                rlpString(uintBytes(group)),
                rlpString(OWNER_SET),
                rlpString(descriptorMediatorSet)
            };
            for (int i = 0; i < items.length; ++i) {
                items[i] = i < 4
                        ? head[i]
                        : i - 4 < descriptorTail.length ? descriptorTail[i - 4] : new byte[] {(byte) 0x80};
            }
            return rlpList(items);
        }

        byte[] proof() {
            final byte[][] throttles = new byte[declaredThrottleCount][];
            for (int i = 0; i < throttles.length; ++i) {
                throttles[i] =
                        rlpString(uintBytes(i < declaredThrottles.length ? declaredThrottles[i] : BigInteger.ONE));
            }
            return rlpList(
                    rlpString(serviceAddress),
                    rlpString(utf8(chainId)),
                    rlpString(uintBytes(declaredNanos)),
                    rlpList(throttles),
                    descriptor(),
                    signedVerdict(false),
                    rlpList(tree().opening(), rlpString(createdIndex)));
        }

        /** {@code [verdict, opening, createdIndex, manifestPreimage]}, with this transaction as the manifest's. */
        byte[] manifestProof(final byte[] preimage) {
            return rlpList(signedVerdict(false), tree().opening(), rlpString(createdIndex), rlpString(preimage));
        }

        byte[] manifestProofWithBadSignature(final byte[] preimage) {
            return rlpList(signedVerdict(true), tree().opening(), rlpString(createdIndex), rlpString(preimage));
        }
    }

    private record Med(PrivateKey priv, byte[] pub, byte[] fp) {
        static Med of(final int seed) {
            final byte[] s = new byte[32];
            Arrays.fill(s, (byte) seed);
            try {
                // The JDK derives the Ed25519 key from 32 bytes drawn from the SecureRandom, so a fixed
                // "random" source yields a deterministic key pair.
                final var fixed = new SecureRandom() {
                    @Override
                    public void nextBytes(final byte[] bytes) {
                        System.arraycopy(s, 0, bytes, 0, bytes.length);
                    }
                };
                final var gen = KeyPairGenerator.getInstance("Ed25519");
                gen.initialize(NamedParameterSpec.ED25519, fixed);
                final KeyPair pair = gen.generateKeyPair();
                final byte[] pub = rawPublicKey((EdECPublicKey) pair.getPublic());
                return new Med(pair.getPrivate(), pub, sha(pub));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** RFC 8032 encoding: little-endian y with the sign of x in the top bit. */
        private static byte[] rawPublicKey(final EdECPublicKey key) {
            final byte[] be = key.getPoint().getY().toByteArray();
            final byte[] raw = new byte[32];
            for (int i = 0; i < be.length && i < 32; ++i) {
                raw[i] = be[be.length - 1 - i];
            }
            if (key.getPoint().isXOdd()) {
                raw[31] |= (byte) 0x80;
            }
            return raw;
        }

        byte[] sign(final byte[] message) {
            try {
                final var signer = Signature.getInstance("Ed25519");
                signer.initSign(priv);
                signer.update(message);
                return signer.sign();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Packed set layout of CantonMediators: one Ed25519 member with one current key, threshold 1. */
        byte[] set() {
            final var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.writeBytes(new byte[] {0, 0, 0, 1});
            final byte[] id = new byte[32];
            id[0] = 1;
            out.writeBytes(id);
            out.writeBytes(new byte[] {0, 0, 0, 1});
            out.write(0); // delegations
            out.write(1); // current keys
            out.writeBytes(fp);
            out.write(1); // Ed25519
            out.write(0);
            out.writeBytes(pub);
            out.writeBytes(new byte[32]);
            out.write(0); // previous keys
            return out.toByteArray();
        }
    }

    private static byte[] replace(final byte[] in, final byte[] needle, final byte[] with) {
        assertThat(with).hasSameSizeAs(needle);
        int at = -1;
        for (int i = 0; i + needle.length <= in.length; ++i) {
            if (Arrays.equals(in, i, i + needle.length, needle, 0, needle.length)) {
                assertThat(at).as("needle occurs once").isEqualTo(-1);
                at = i;
            }
        }
        assertThat(at).as("needle found").isNotNegative();
        final byte[] out = in.clone();
        System.arraycopy(with, 0, out, at, with.length);
        return out;
    }

    private static void assertRejects(final String errorName, final org.junit.jupiter.api.function.Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo(errorName));
    }

    private static byte[] sha(final String s) {
        return sha(utf8(s));
    }

    private static byte[] sha(final byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final var out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] versioned(final byte[] data, final int version) {
        return concat(Pb.lenField(1, data), new Pb().varint(2, version).build());
    }

    private static byte[] leafHash(final int purpose, final byte[] data, final int version) {
        return CantonMerkle.leaf(purpose, sha(data), versioned(data, version));
    }

    private static byte[] leafRlp(final byte[] data, final int version) {
        return rlpList(rlpString(sha(data)), rlpString(versioned(data, version)));
    }

    private static byte[] rlpString(final byte[] b) {
        if (b.length == 1 && (b[0] & 0xff) < 0x80) {
            return b.clone();
        }
        return concat(rlpHeader(0x80, b.length), b);
    }

    private static byte[] rlpList(final byte[]... items) {
        final byte[] payload = concat(items);
        return concat(rlpHeader(0xc0, payload.length), payload);
    }

    private static byte[] rlpHeader(final int base, final int len) {
        if (len <= 55) {
            return new byte[] {(byte) (base + len)};
        }
        int n = 4;
        while (n > 1 && (len >>> (8 * (n - 1))) == 0) {
            --n;
        }
        final byte[] lenBytes = new byte[n];
        for (int i = 0; i < n; ++i) {
            lenBytes[i] = (byte) (len >>> (8 * (n - 1 - i)));
        }
        return concat(new byte[] {(byte) (base + 55 + n)}, lenBytes);
    }

    /** Minimal protobuf wire-format builder. */
    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            out.writeBytes(lenField(number, value));
            return this;
        }

        Pb varint(final int number, final long value) {
            writeVarint(out, (long) number << 3);
            writeVarint(out, value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }

        static byte[] lenField(final long number, final byte[] value) {
            final var o = new ByteArrayOutputStream();
            writeVarint(o, (number << 3) | CantonProto.WIRE_LEN);
            writeVarint(o, value.length);
            o.writeBytes(value);
            return o.toByteArray();
        }

        private static void writeVarint(final ByteArrayOutputStream o, long v) {
            while (true) {
                final int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) {
                    o.write(b | 0x80);
                } else {
                    o.write(b);
                    return;
                }
            }
        }
    }
}
