// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateInvalidException;
import com.swirlds.platform.state.signed.SignedStateValidationData;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterEntryBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestVirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that the {@link DefaultSignedStateValidator} uses weight correctly to determine the validity of the signed
 * state.
 */
class DefaultSignedStateValidatorTests {

    private static final int NUM_NODES_IN_STATIC_TESTS = 7;

    /** The maximum number of nodes in the network (inclusive) in randomized tests. */
    private static final int MAX_NODES_IN_RANDOMIZED_TESTS = 20;

    /** The maximum amount of weight to allocate to a single node (inclusive) in randomized tests. */
    private static final int MAX_WEIGHT_PER_NODE = 100;

    private static final int ROUND = 0;

    private Roster roster;

    private DefaultSignedStateValidator validator;

    /**
     * Test params to test specific scenarios of node signatures and weight values.
     *
     * @return stream of arguments to test specific scenarios
     */
    private static Stream<Arguments> staticNodeParams() {
        final List<Arguments> arguments = new ArrayList<>();

        // All state signatures are valid and make up a majority
        arguments.add(allNodesValidSignatures());

        // All state signatures are valid but do not make up a majority
        arguments.add(allValidSigsNoMajority());

        // 1/2 weight of valid signatures, 1/2 of weight invalid signatures
        arguments.add(someNodeValidSigsMajority());

        // less than 1/2 weight of valid signatures, more than 1/2 of weight invalid signatures
        arguments.add(someNodeValidSigsNoMajority());

        return arguments.stream();
    }

    /**
     * Test params to test randomized scenarios. Randomized variables:
     * </p>
     * <ul>
     *     <li>network size</li>
     *     <li>weight per node</li>
     *     <li>which nodes sign the state</li>
     *     <li>if a nodes signs the state, do they use a valid or invalid signature</li>
     * </ul>
     *
     * @return stream of arguments to test randomized scenarios
     */
    private static Stream<Arguments> randomizedNodeParams() {
        final List<Arguments> arguments = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            final Long seed = new Random().nextLong();
            final RandomGenerator r = RandomUtils.initRandom(seed);
            final List<Node> nodes = initRandomizedNodes(r);
            final List<Node> signingNodes = getRandomizedSigningNodes(r, nodes);
            final long validSigningWeight = getValidSignatureWeight(signingNodes);
            final long totalWeight = getTotalWeight(nodes);

            // A Roster object is considered invalid if it has a total weight of zero
            // because such a Roster is practically unusable. Therefore, we don't test it.
            if (totalWeight != 0L) {
                final String desc = String.format(
                        "\nseed: %sL:, valid signing weight: %s, total weight: %s\n",
                        seed, validSigningWeight, totalWeight);
                arguments.add(Arguments.of(desc, nodes, signingNodes));
            }
        }

        return arguments.stream();
    }

    private static List<Node> getRandomizedSigningNodes(final RandomGenerator r, final List<Node> nodes) {
        final List<Node> signingNodes = new LinkedList<>();
        for (final Node node : nodes) {
            if (r.nextBoolean()) {
                signingNodes.add(node);
            }
        }
        return signingNodes;
    }

    private static List<Node> initRandomizedNodes(final RandomGenerator r) {
        final int numNodes = r.nextInt(1, MAX_NODES_IN_RANDOMIZED_TESTS);
        final List<Node> nodes = new LinkedList<>();
        for (int i = 0; i < numNodes; i++) {
            // Allow zero-weight
            final int weight = r.nextInt(MAX_WEIGHT_PER_NODE);
            final boolean hasValidSig = r.nextBoolean();
            nodes.add(new Node(NodeId.of(i), weight, hasValidSig));
        }
        return nodes;
    }

    /**
     * @return Arguments to test that all signatures on the state are valid but do not constitute a majority.
     */
    private static Arguments allValidSigsNoMajority() {
        final List<Node> allValidSigNodes = initNodes();
        final Long seed = new Random().nextLong();
        return Arguments.of(
                formatSeedDesc(seed),
                allValidSigNodes,
                List.of(
                        allValidSigNodes.get(0),
                        allValidSigNodes.get(1),
                        allValidSigNodes.get(2),
                        allValidSigNodes.get(3)));
    }

    /**
     * @return Arguments to test when all nodes sign a state with a valid signature.
     */
    private static Arguments allNodesValidSignatures() {
        final List<Node> allValidSigNodes = initNodes();
        final Long seed = new Random().nextLong();
        return Arguments.of(formatSeedDesc(seed), allValidSigNodes, allValidSigNodes);
    }

    private static String formatSeedDesc(final Long seed) {
        return "\nseed: " + seed + "L";
    }

    /**
     * @return Arguments to test when all nodes sign a state, some with invalid signatures, but the valid signatures
     * constitute a majority.
     */
    private static Arguments someNodeValidSigsMajority() {
        // >1/2 weight of valid signatures, <1/2 of weight invalid signatures
        final List<Node> majorityValidSigs = initNodes(List.of(true, false, true, false, false, false, true));
        final Long seed = new Random().nextLong();
        return Arguments.of(formatSeedDesc(seed), majorityValidSigs, majorityValidSigs);
    }

    /**
     * @return Arguments to test when all nodes sign a state, some with invalid signatures, and the valid signatures do
     * not constitute a majority.
     */
    private static Arguments someNodeValidSigsNoMajority() {
        final List<Node> notMajorityValidSigs = initNodes(List.of(true, true, true, true, false, false, false));
        final Long seed = new Random().nextLong();
        return Arguments.of(formatSeedDesc(seed), notMajorityValidSigs, notMajorityValidSigs);
    }

    /**
     * Creates a list of nodes, some of which may sign a state with an invalid signature
     *
     * @param isValidSigList a list of booleans indicating if the node at that position will sign the state with a valid
     *                       signature
     * @return a list of nodes
     */
    private static List<Node> initNodes(final List<Boolean> isValidSigList) {
        if (isValidSigList.size() != NUM_NODES_IN_STATIC_TESTS) {
            throw new IllegalArgumentException(String.format(
                    "Incorrect isValidSigList size. Expected %s but got %s",
                    NUM_NODES_IN_STATIC_TESTS, isValidSigList.size()));
        }

        final List<Node> nodes = new ArrayList<>(NUM_NODES_IN_STATIC_TESTS);
        nodes.add(new Node(NodeId.of(0L), 5L, isValidSigList.get(0)));
        nodes.add(new Node(NodeId.of(1L), 5L, isValidSigList.get(1)));
        nodes.add(new Node(NodeId.of(2L), 8L, isValidSigList.get(2)));
        nodes.add(new Node(NodeId.of(3L), 15L, isValidSigList.get(3)));
        nodes.add(new Node(NodeId.of(4L), 17L, isValidSigList.get(4)));
        nodes.add(new Node(NodeId.of(5L), 10L, isValidSigList.get(5)));
        nodes.add(new Node(NodeId.of(6L), 30L, isValidSigList.get(6)));
        return nodes;
    }

    /**
     * @return a list of nodes whose signatures are all valid
     */
    private static List<Node> initNodes() {
        return initNodes(IntStream.range(0, NUM_NODES_IN_STATIC_TESTS)
                .mapToObj(i -> Boolean.TRUE)
                .collect(Collectors.toList()));
    }

    private static Roster createRoster(@NonNull final Random random, @NonNull final List<Node> nodes) {
        List<RosterEntry> rosterEntries = new ArrayList<>(nodes.size());
        for (final Node node : nodes.stream().sorted().toList()) {
            rosterEntries.add(RandomRosterEntryBuilder.create(random)
                    .withNodeId(node.id.id())
                    .withWeight(node.weight)
                    .build());
        }

        return new Roster(rosterEntries);
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @ParameterizedTest
    @MethodSource({"staticNodeParams", "randomizedNodeParams"})
    @DisplayName("Signed State Validation")
    void testSignedStateValidationRandom(final String desc, final List<Node> nodes, final List<Node> signingNodes) {
        final Randotron randotron = Randotron.create();
        roster = createRoster(randotron, nodes);

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        validator = new DefaultSignedStateValidator(platformContext, TEST_PLATFORM_STATE_FACADE);

        final SignedState signedState = stateSignedByNodes(signingNodes);
        final SignedStateValidationData originalData =
                new SignedStateValidationData(signedState.getState(), roster, TEST_PLATFORM_STATE_FACADE);

        final boolean shouldSucceed = stateHasEnoughWeight(nodes, signingNodes);
        if (shouldSucceed) {
            assertDoesNotThrow(
                    () -> validator.validate(signedState, roster, originalData),
                    "State signed with a majority of weight (%s out of %s) should pass validation."
                            .formatted(getValidSignatureWeight(signingNodes), getTotalWeight(nodes)));
        } else {
            assertThrows(
                    SignedStateInvalidException.class,
                    () -> validator.validate(signedState, roster, originalData),
                    "State not signed with a majority of weight (%s out of %s) should NOT pass validation."
                            .formatted(getValidSignatureWeight(signingNodes), getTotalWeight(nodes)));
        }
    }

    /**
     * Determines if the nodes in {@code signingNodes} with valid signatures have enough weight to make up a strong
     * minority of the total weight.
     *
     * @param nodes        all the nodes in the network, used to calculate the total weight
     * @param signingNodes all the nodes that signed the state
     * @return true if the state has a majority of weight from valid signatures
     */
    private boolean stateHasEnoughWeight(final List<Node> nodes, final List<Node> signingNodes) {
        final long totalWeight = getTotalWeight(nodes);
        final long signingWeight = getValidSignatureWeight(signingNodes);

        return MAJORITY.isSatisfiedBy(signingWeight, totalWeight);
    }

    private static long getTotalWeight(final List<Node> nodes) {
        long totalWeight = 0;
        for (final Node node : nodes) {
            totalWeight += node.weight;
        }
        return totalWeight;
    }

    private static long getValidSignatureWeight(final List<Node> signingNodes) {
        long signingWeight = 0;
        for (final Node signingNode : signingNodes) {
            signingWeight += signingNode.validSignature ? signingNode.weight : 0;
        }
        return signingWeight;
    }

    /**
     * Create a {@link SignedState} signed by the nodes whose ids are supplied by {@code signingNodeIds}.
     *
     * @param signingNodes the node ids signing the state
     * @return the signed state
     */
    private SignedState stateSignedByNodes(final List<Node> signingNodes) {
        final TestVirtualMapState state = new TestVirtualMapState();

        final SignatureVerifier signatureVerifier = (data, signature, key) -> {
            // a signature with a 0 byte is always invalid
            // this is set in the nodeSigs() method
            if (signature.getByte(0) == 0) {
                return false;
            }
            final Hash stateHash = state.getHash();
            final Hash hash = new Hash(data, stateHash.getDigestType());

            return hash.equals(stateHash);
        };

        return new RandomSignedStateGenerator()
                .setState(state)
                .setRound(ROUND)
                .setRoster(roster)
                .setSignatures(nodeSigs(signingNodes))
                .setSignatureVerifier(signatureVerifier)
                .build();
    }

    /**
     * @return a list of the nodes ids in the supplied nodes
     */
    private Map<NodeId, Signature> nodeSigs(final List<Node> nodes) {
        final Map<NodeId, Signature> signatures = new HashMap<>();
        for (final Node node : nodes) {
            final byte sigValid = node.validSignature ? (byte) 1 : (byte) 0;
            signatures.put(node.id, new Signature(SignatureType.RSA, new byte[] {sigValid}));
        }

        return signatures;
    }

    /**
     * A record representing a simple node that holds its id, amount of weight, and if it signs states with a valid
     * signature.
     */
    private record Node(NodeId id, long weight, boolean validSignature) implements Comparable<Node> {

        @Override
        public String toString() {
            return String.format("NodeId: %s,\tWeight: %s,\tValidSig: %s", id, weight, validSignature);
        }

        @Override
        public int compareTo(@NonNull final Node that) {
            return id.compareTo(that.id);
        }
    }
}
