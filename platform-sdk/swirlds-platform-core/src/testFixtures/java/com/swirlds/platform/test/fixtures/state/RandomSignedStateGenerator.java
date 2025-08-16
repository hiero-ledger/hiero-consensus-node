// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.CONFIGURATION;
import static com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils.buildFakeSignature;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHash;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomHashBytes;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.mockito.Mockito.spy;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.base.time.Time;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.Reservable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.manager.SignatureVerificationTestUtils;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.base.utility.CommonUtils;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * A utility for generating random signed states.
 */
public class RandomSignedStateGenerator {

    private static final Logger logger = LogManager.getLogger(RandomSignedStateGenerator.class);
    /**
     * Signed states now use virtual maps which are heavy RAM consumers. They need to be released
     * in order to avoid producing OOMs when running tests. This list tracks all signed states
     * built on a given thread.
     */
    private static final ThreadLocal<List<SignedState>> builtSignedStates = ThreadLocal.withInitial(ArrayList::new);

    final Random random;

    private MerkleNodeState state;
    private Long round;
    private Hash legacyRunningEventHash;
    private Roster roster;
    private Instant consensusTimestamp;
    private Boolean freezeState = false;
    private SemanticVersion softwareVersion;
    private List<NodeId> signingNodeIds;
    private Map<NodeId, Signature> signatures;
    private Function<Hash, Map<NodeId, Signature>> signatureSupplier;
    private Integer roundsNonAncient = null;
    private ConsensusSnapshot consensusSnapshot;
    private SignatureVerifier signatureVerifier;
    private boolean deleteOnBackgroundThread;
    private boolean pcesRound;

    /**
     * Create a new signed state generator with a random seed.
     */
    public RandomSignedStateGenerator() {
        random = getRandomPrintSeed();
    }

    /**
     * Create a new signed state generator with a specific seed.
     */
    public RandomSignedStateGenerator(final long seed) {
        random = new Random(seed);
    }

    /**
     * Create a new signed state generator with a random object.
     */
    public RandomSignedStateGenerator(final Random random) {
        this.random = random;
    }

    /**
     * Build a new signed state.
     *
     * @return a new signed state
     */
    public SignedState build() {
        return buildWithFacade().left();
    }

    /**
     * Build a pair of new signed state and a platform state facade used for that
     *
     * @return a new signed state
     */
    public Pair<SignedState, TestPlatformStateFacade> buildWithFacade() {

        final Roster rosterInstance;
        if (roster == null) {
            rosterInstance = RandomRosterBuilder.create(random)
                    .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                    .build();
        } else {
            rosterInstance = roster;
        }

        final SemanticVersion softwareVersionInstance;
        if (softwareVersion == null) {
            softwareVersionInstance =
                    SemanticVersion.newBuilder().major(random.nextInt(1, 100)).build();
        } else {
            softwareVersionInstance = softwareVersion;
        }

        final MerkleNodeState stateInstance;
        final long roundInstance;
        if (round == null) {
            roundInstance = Math.abs(random.nextLong());
        } else {
            roundInstance = round;
        }

        TestPlatformStateFacade platformStateFacade = new TestPlatformStateFacade();
        if (state == null) {
            final String virtualMapLabel =
                    "vm-" + RandomSignedStateGenerator.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
            stateInstance = TestVirtualMapState.createInstanceWithVirtualMapLabel(virtualMapLabel);
            stateInstance.init(
                    Time.getCurrent(),
                    CONFIGURATION,
                    new NoOpMetrics(),
                    TestMerkleCryptoFactory.getInstance(),
                    () -> platformStateFacade.roundOf(stateInstance));
        } else {
            stateInstance = state;
        }

        final Hash legacyRunningEventHashInstance;
        if (legacyRunningEventHash == null) {
            legacyRunningEventHashInstance = randomHash(random);
        } else {
            legacyRunningEventHashInstance = legacyRunningEventHash;
        }

        final Instant consensusTimestampInstance;
        if (consensusTimestamp == null) {
            consensusTimestampInstance = RandomUtils.randomInstant(random);
        } else {
            consensusTimestampInstance = consensusTimestamp;
        }

        final boolean freezeStateInstance;
        if (freezeState == null) {
            freezeStateInstance = random.nextBoolean();
        } else {
            freezeStateInstance = freezeState;
        }

        final int roundsNonAncientInstance;
        if (roundsNonAncient == null) {
            roundsNonAncientInstance = 26;
        } else {
            roundsNonAncientInstance = roundsNonAncient;
        }

        final ConsensusSnapshot consensusSnapshotInstance;
        final List<JudgeId> judges = Stream.generate(() -> new JudgeId(0L, randomHashBytes(random)))
                .limit(10)
                .toList();
        if (consensusSnapshot == null) {
            consensusSnapshotInstance = ConsensusSnapshot.newBuilder()
                    .round(roundInstance)
                    .judgeIds(judges)
                    .minimumJudgeInfoList(IntStream.range(0, roundsNonAncientInstance)
                            .mapToObj(i -> new MinimumJudgeInfo(roundInstance - i, 0L))
                            .toList())
                    .nextConsensusNumber(roundInstance)
                    .consensusTimestamp(CommonUtils.toPbjTimestamp(consensusTimestampInstance))
                    .build();
        } else {
            consensusSnapshotInstance = consensusSnapshot;
        }
        TestingAppStateInitializer.DEFAULT.initPlatformState(stateInstance);

        platformStateFacade.bulkUpdateOf(stateInstance, v -> {
            v.setSnapshot(consensusSnapshotInstance);
            v.setLegacyRunningEventHash(legacyRunningEventHashInstance);
            v.setCreationSoftwareVersion(softwareVersionInstance);
            v.setRoundsNonAncient(roundsNonAncientInstance);
            v.setConsensusTimestamp(consensusTimestampInstance);
        });

        TestingAppStateInitializer.DEFAULT.initRosterState(stateInstance);
        RosterUtils.setActiveRoster(stateInstance, rosterInstance, roundInstance);

        if (signatureVerifier == null) {
            signatureVerifier = SignatureVerificationTestUtils::verifySignature;
        }

        final Configuration configuration = new TestConfigBuilder()
                .withValue("state.stateHistoryEnabled", true)
                .withConfigDataType(StateConfig.class)
                .getOrCreateConfig();

        final SignedState signedState = new SignedState(
                configuration,
                signatureVerifier,
                stateInstance,
                "RandomSignedStateGenerator.build()",
                freezeStateInstance,
                deleteOnBackgroundThread,
                pcesRound,
                platformStateFacade);
        signedState.init(PlatformContext.create(configuration));

        final Map<NodeId, Signature> signaturesInstance;
        if (signatureSupplier != null) {
            signaturesInstance = signatureSupplier.apply(stateInstance.getRoot().getHash());
        } else if (signatures == null) {
            final List<NodeId> signingNodeIdsInstance;
            if (signingNodeIds == null) {
                signingNodeIdsInstance = new LinkedList<>();
                if (!rosterInstance.rosterEntries().isEmpty()) {
                    for (int i = 0; i < rosterInstance.rosterEntries().size() / 3 + 1; i++) {
                        final RosterEntry node = rosterInstance.rosterEntries().get(i);
                        signingNodeIdsInstance.add(NodeId.of(node.nodeId()));
                    }
                }
            } else {
                signingNodeIdsInstance = signingNodeIds;
            }

            signaturesInstance = new HashMap<>();

            for (final NodeId nodeID : signingNodeIdsInstance) {
                signaturesInstance.put(nodeID, randomSignature(random));
            }
        } else {
            signaturesInstance = signatures;
        }

        for (final NodeId nodeId : signaturesInstance.keySet()) {
            // this call results in the hash calculation of the state
            signedState.addSignature(nodeId, signaturesInstance.get(nodeId));
        }

        builtSignedStates.get().add(signedState);
        return Pair.of(signedState, spy(platformStateFacade));
    }

    /**
     * Build multiple states.
     *
     * @param count the number of states to build
     */
    public List<SignedState> build(final int count) {
        final List<SignedState> states = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            states.add(build());
        }

        return states;
    }

    /**
     * Set if this state should be deleted on a background thread.
     * ({@link com.swirlds.platform.state.signed.StateGarbageCollector} must be wired up in order for this to happen)
     *
     * @param deleteOnBackgroundThread if true, delete on a background thread
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator setDeleteOnBackgroundThread(final boolean deleteOnBackgroundThread) {
        this.deleteOnBackgroundThread = deleteOnBackgroundThread;
        return this;
    }

    /**
     * Set the state.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setState(final MerkleNodeState state) {
        this.state = state;
        return this;
    }

    /**
     * Set the round when the state was generated.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setRound(final long round) {
        this.round = round;
        return this;
    }

    /**
     * Set the legacy running hash of all events that have been applied to this state since genesis.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setLegacyRunningEventHash(final Hash legacyRunningEventHash) {
        this.legacyRunningEventHash = legacyRunningEventHash;
        return this;
    }

    /**
     * Set the roster.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setRoster(final Roster roster) {
        this.roster = roster;
        return this;
    }

    /**
     * Set the timestamp associated with this state.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setConsensusTimestamp(final Instant consensusTimestamp) {
        this.consensusTimestamp = consensusTimestamp;
        return this;
    }

    /**
     * Specify if this state was written to disk as a result of a freeze.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setFreezeState(final boolean freezeState) {
        this.freezeState = freezeState;
        return this;
    }

    /**
     * Set the software version that was used to create this state.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setSoftwareVersion(final SemanticVersion softwareVersion) {
        this.softwareVersion = softwareVersion;
        return this;
    }

    /**
     * Specify which nodes have signed this signed state. Ignored if signatures are set.
     *
     * @param signingNodeIds a list of nodes that have signed this state
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator setSigningNodeIds(@NonNull final List<NodeId> signingNodeIds) {
        Objects.requireNonNull(signingNodeIds, "signingNodeIds must not be null");
        this.signingNodeIds = signingNodeIds;
        return this;
    }

    /**
     * Provide signatures for the signed state.
     *
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator setSignatures(@NonNull final Map<NodeId, Signature> signatures) {
        Objects.requireNonNull(signatures, "signatures must not be null");
        this.signatures = signatures;
        return this;
    }

    /**
     * Configures the generator to use the signature supplier that generates signatures
     *
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator useSignatureSupplierFromRoster() {
        Objects.requireNonNull(roster, "roster must not be null");
        this.signatureSupplier = createSignatureSupplier(roster);
        return this;
    }

    /**
     * Set the number of non-ancient rounds.
     *
     * @return this object
     */
    public RandomSignedStateGenerator setRoundsNonAncient(final int roundsNonAncient) {
        this.roundsNonAncient = roundsNonAncient;
        return this;
    }

    @NonNull
    public RandomSignedStateGenerator setConsensusSnapshot(@NonNull final ConsensusSnapshot consensusSnapshot) {
        this.consensusSnapshot = consensusSnapshot;
        return this;
    }

    /**
     * Set the signature verifier.
     *
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator setSignatureVerifier(@NonNull final SignatureVerifier signatureVerifier) {
        this.signatureVerifier = signatureVerifier;
        return this;
    }

    /**
     * Set if this state was generated during a PCES round.
     *
     * @param pcesRound true if this state was generated during a PCES round
     * @return this object
     */
    @NonNull
    public RandomSignedStateGenerator setPcesRound(final boolean pcesRound) {
        this.pcesRound = pcesRound;
        return this;
    }

    /**
     * Keep calling release() on a given Reservable until it's completely released.
     * @param reservable a reservable to release
     */
    public static void releaseReservable(@NonNull final Reservable reservable) {
        while (reservable.getReservationCount() >= 0) {
            reservable.release();
        }
    }

    /**
     * Release all the SignedState objects built by this generator on the current thread,
     * and then clear the list of built states.
     */
    public static void releaseAllBuiltSignedStates() {
        builtSignedStates.get().forEach(signedState -> {
            try {
                releaseReservable(signedState.getState().getRoot());
                signedState.getState().getRoot().treeIterator().forEachRemaining(node -> {
                    if (node instanceof VirtualMap) {
                        while (node.getReservationCount() >= 0) {
                            node.release();
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Exception while releasing state", e);
            }
        });
        MerkleDbTestUtils.assertAllDatabasesClosed();
        builtSignedStates.get().clear();
    }

    /**
     * Clear the list of states built on the current thread w/o releasing them.
     * There are tests that actually release the states on purpose, verifying the reserve/release behavior.
     * Some of these tests use mocks which fail if the state is released more than what the test expects.
     * For these few special cases, this method allows the test to "forget" about any states that it built
     * using this generator on the current thread. As long as the number of such special cases is low enough,
     * this shouldn't cause any serious resource leaks or OOMs in tests.
     */
    public static void forgetAllBuiltSignedStatesWithoutReleasing() {
        builtSignedStates.get().clear();
    }

    public static void changeStateHashRandomly(@NonNull final SignedState state) {
        try {
            final Field hashField = VirtualMap.class.getDeclaredField("hash");
            hashField.setAccessible(true);
            final AtomicReference<Hash> hashRef =
                    (AtomicReference<Hash>) hashField.get(state.getState().getRoot());
            final Hash newHash = randomHash();
            hashRef.set(newHash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Given the roster, create a function that will return a map of node IDs to signatures.
     * @param roster roster to use for creating signatures
     * @return a function that takes a hash and returns a map of node IDs to signatures
     */
    public static Function<Hash, Map<NodeId, Signature>> createSignatureSupplier(Roster roster) {
        return hash -> {
            final Map<NodeId, Signature> signatures = new HashMap<>();
            for (final RosterEntry node : roster.rosterEntries()) {
                final PublicKey publicKey =
                        RosterUtils.fetchGossipCaCertificate(node).getPublicKey();
                signatures.put(NodeId.of(node.nodeId()), buildFakeSignature(publicKey, hash));
            }
            return signatures;
        };
    }
}
