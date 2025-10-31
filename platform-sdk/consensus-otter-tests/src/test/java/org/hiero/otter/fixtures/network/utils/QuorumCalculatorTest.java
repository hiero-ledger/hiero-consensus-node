// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.utility.Threshold;
import java.util.ArrayList;
import java.util.List;
import org.hiero.otter.fixtures.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QuorumCalculator Test")
class QuorumCalculatorTest {

    /**
     * Creates a mock node with the specified weight.
     */
    private Node createNode(final long weight) {
        final Node node = mock(Node.class);
        when(node.weight()).thenReturn(weight);
        return node;
    }

    /**
     * Calculates the total weight of a list of nodes.
     */
    private long totalWeight(final List<Node> nodes) {
        return nodes.stream().mapToLong(Node::weight).sum();
    }

    // ==================== Tests for largestSubStrongMinority ====================

    @Test
    @DisplayName("largestSubStrongMinority: null list throws NullPointerException")
    void largestSubStrongMinorityNullList() {
        assertThatThrownBy(() -> QuorumCalculator.largestSubStrongMinority(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("largestSubStrongMinority: empty list throws exception")
    void largestSubStrongMinorityEmptyList() {
        final List<Node> nodes = List.of();
        assertThatThrownBy(() -> QuorumCalculator.largestSubStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot find partition for empty node list");
    }

    @Test
    @DisplayName("largestSubStrongMinority: single small node is sub-strong")
    void largestSubStrongMinoritySingleSmallNode() {
        final List<Node> nodes = List.of(createNode(100));
        assertThatThrownBy(() -> QuorumCalculator.largestSubStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid partition found");
    }

    @Test
    @DisplayName("largestSubStrongMinority: finds largest by weight with equal weights")
    void largestSubStrongMinorityEqualWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(100), createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(100);
    }

    @Test
    @DisplayName("largestSubStrongMinority: finds largest by weight with different weights")
    void largestSubStrongMinorityDifferentWeights() {
        final List<Node> nodes = List.of(createNode(50), createNode(100), createNode(200), createNode(250));
        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(150);
    }

    @Test
    @DisplayName("largestSubStrongMinority: selects largest node below threshold")
    void largestSubStrongMinoritySelectsLargestNodeBelowThreshold() {
        final List<Node> nodes =
                List.of(createNode(100), createNode(120), createNode(150), createNode(250), createNode(280));
        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isLessThan(300);
        assertThat(resultWeight).isEqualTo(280);
    }

    @Test
    @DisplayName("largestSubStrongMinority: throws when all partitions are strong minorities")
    void largestSubStrongMinorityThrowsWhenAllAreStrongMinorities() {
        final List<Node> nodes = List.of(createNode(100), createNode(100));
        assertThatThrownBy(() -> QuorumCalculator.largestSubStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid partition found");
    }

    @Test
    @DisplayName("largestSubStrongMinority: handles edge case with small nodes")
    void largestSubStrongMinorityEdgeCase() {
        final List<Node> nodes =
                List.of(createNode(10), createNode(10), createNode(10), createNode(10), createNode(10), createNode(50));
        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(30);
    }

    @Test
    @DisplayName("largestSubStrongMinority: handles nodes with zero weight")
    void largestSubStrongMinorityZeroWeightNodes() {
        final List<Node> nodes =
                List.of(createNode(0), createNode(30), createNode(40), createNode(100), createNode(130));

        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(70);
        assertThat(result).noneMatch(node -> node.weight() == 0);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("largestSubStrongMinority: prefers combination over single node")
    void largestSubStrongMinorityPrefersCombination() {
        final List<Node> nodes =
                List.of(createNode(100), createNode(100), createNode(100), createNode(200), createNode(500));

        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(300);
    }

    @Test
    @DisplayName("largestSubStrongMinority: works with asymmetric distribution")
    void largestSubStrongMinorityAsymmetricDistribution() {
        final List<Node> nodes =
                List.of(createNode(700), createNode(100), createNode(100), createNode(50), createNode(50));

        final List<Node> result = QuorumCalculator.largestSubStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(300);
    }

    // ==================== Tests for smallestStrongMinority ====================

    @Test
    @DisplayName("smallestStrongMinority: null list throws NullPointerException")
    void smallestStrongMinorityNullList() {
        assertThatThrownBy(() -> QuorumCalculator.smallestStrongMinority(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("smallestStrongMinority: empty list throws exception")
    void smallestStrongMinorityEmptyList() {
        final List<Node> nodes = List.of();
        assertThatThrownBy(() -> QuorumCalculator.smallestStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot find partition for empty node list");
    }

    @Test
    @DisplayName("smallestStrongMinority: single node throws exception (would be majority)")
    void smallestStrongMinoritySingleNode() {
        final List<Node> nodes = List.of(createNode(100));
        assertThatThrownBy(() -> QuorumCalculator.smallestStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid partition found");
    }

    @Test
    @DisplayName("smallestStrongMinority: finds smallest by weight with equal weights")
    void smallestStrongMinorityEqualWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        assertThat(result).hasSize(1);
        assertThat(totalWeight(result)).isEqualTo(100);
    }

    @Test
    @DisplayName("smallestStrongMinority: finds smallest by weight with different weights")
    void smallestStrongMinorityDifferentWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(200), createNode(300));
        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(200);
    }

    @Test
    @DisplayName("smallestStrongMinority: requires multiple nodes when needed")
    void smallestStrongMinorityMultipleNodesRequired() {
        final List<Node> nodes = List.of(createNode(100), createNode(150), createNode(200), createNode(250));
        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isGreaterThanOrEqualTo(234);
        assertThat(resultWeight).isLessThanOrEqualTo(350);
    }

    @Test
    @DisplayName("smallestStrongMinority: works with 2 equal nodes")
    void smallestStrongMinorityWithTwoEqualNodes() {
        final List<Node> nodes = List.of(createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(resultWeight).isEqualTo(100);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
    }

    @Test
    @DisplayName("smallestStrongMinority: handles edge case where 1/3 rounds up")
    void smallestStrongMinorityRoundingEdgeCase() {
        final List<Node> nodes = List.of(createNode(3), createNode(4), createNode(3));
        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(4);
    }

    @Test
    @DisplayName("smallestStrongMinority: excludes zero-weight nodes")
    void smallestStrongMinorityExcludesZeroWeight() {
        final Node zeroNode1 = createNode(0);
        final Node zeroNode2 = createNode(0);
        final List<Node> nodes = List.of(zeroNode1, createNode(50), createNode(100), zeroNode2, createNode(150));

        final List<Node> result = QuorumCalculator.smallestStrongMinority(nodes);

        assertThat(result).noneMatch(node -> node.weight() == 0);
        assertThat(result).doesNotContain(zeroNode1, zeroNode2);
        assertThat(totalWeight(result)).isEqualTo(100);
    }

    // ==================== Tests for smallestMajority ====================

    @Test
    @DisplayName("smallestMajority: null list throws NullPointerException")
    void smallestMajorityNullList() {
        assertThatThrownBy(() -> QuorumCalculator.smallestMajority(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("smallestMajority: empty list throws exception")
    void smallestMajorityEmptyList() {
        final List<Node> nodes = List.of();
        assertThatThrownBy(() -> QuorumCalculator.smallestMajority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot find partition for empty node list");
    }

    @Test
    @DisplayName("smallestMajority: single node throws exception (would be super majority)")
    void smallestMajoritySingleNode() {
        final List<Node> nodes = List.of(createNode(100));
        assertThatThrownBy(() -> QuorumCalculator.smallestMajority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid partition found");
    }

    @Test
    @DisplayName("smallestMajority: finds smallest by weight with equal weights")
    void smallestMajorityEqualWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.smallestMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(result).hasSize(2);
        assertThat(resultWeight).isEqualTo(200);
    }

    @Test
    @DisplayName("smallestMajority: finds smallest by weight with different weights")
    void smallestMajorityDifferentWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(200), createNode(300), createNode(400));
        final List<Node> result = QuorumCalculator.smallestMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(600);
    }

    @Test
    @DisplayName("smallestMajority: requires multiple nodes when no single node is sufficient")
    void smallestMajorityMultipleNodesRequired() {
        final List<Node> nodes = List.of(createNode(100), createNode(120), createNode(130), createNode(150));
        final List<Node> result = QuorumCalculator.smallestMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isGreaterThan(250);
        assertThat(resultWeight).isLessThanOrEqualTo(333);
    }

    @Test
    @DisplayName("smallestMajority: throws when all majorities are super majorities")
    void smallestMajorityThrowsWhenAllAreSuperMajorities() {
        final List<Node> nodes = List.of(createNode(100), createNode(100));
        assertThatThrownBy(() -> QuorumCalculator.smallestMajority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid partition found");
    }

    @Test
    @DisplayName("smallestMajority: handles even total weight")
    void smallestMajorityEvenTotalWeight() {
        final List<Node> nodes = List.of(createNode(40), createNode(60), createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.smallestMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isFalse();
        assertThat(resultWeight).isEqualTo(160);
    }

    @Test
    @DisplayName("smallestMajority: excludes zero-weight nodes")
    void smallestMajorityExcludesZeroWeight() {
        final Node zeroNode1 = createNode(0);
        final Node zeroNode2 = createNode(0);
        final List<Node> nodes = List.of(zeroNode1, createNode(100), createNode(150), zeroNode2, createNode(200));

        final List<Node> result = QuorumCalculator.smallestMajority(nodes);

        assertThat(result).noneMatch(node -> node.weight() == 0);
        assertThat(result).doesNotContain(zeroNode1, zeroNode2);
        assertThat(totalWeight(result)).isEqualTo(250);
    }

    // ==================== Tests for smallestSuperMajority ====================

    @Test
    @DisplayName("smallestSuperMajority: null list throws NullPointerException")
    void smallestSuperMajorityNullList() {
        assertThatThrownBy(() -> QuorumCalculator.smallestSuperMajority(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("smallestSuperMajority: empty list throws exception")
    void smallestSuperMajorityEmptyList() {
        final List<Node> nodes = List.of();
        assertThatThrownBy(() -> QuorumCalculator.smallestSuperMajority(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot find partition for empty node list");
    }

    @Test
    @DisplayName("smallestSuperMajority: single node satisfies super majority")
    void smallestSuperMajoritySingleNode() {
        final List<Node> nodes = List.of(createNode(100));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(result).hasSize(1);
        assertThat(resultWeight).isEqualTo(100);
    }

    @Test
    @DisplayName("smallestSuperMajority: finds smallest by weight with equal weights")
    void smallestSuperMajorityEqualWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(100), createNode(100));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(result).hasSize(3);
        assertThat(resultWeight).isEqualTo(300);
    }

    @Test
    @DisplayName("smallestSuperMajority: finds smallest by weight with different weights")
    void smallestSuperMajorityDifferentWeights() {
        final List<Node> nodes = List.of(createNode(100), createNode(200), createNode(600), createNode(700));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(resultWeight).isEqualTo(1300);
    }

    @Test
    @DisplayName("smallestSuperMajority: requires multiple nodes when no single node is sufficient")
    void smallestSuperMajorityMultipleNodesRequired() {
        final List<Node> nodes = List.of(createNode(150), createNode(200), createNode(250));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(resultWeight).isEqualTo(450);
    }

    @Test
    @DisplayName("smallestSuperMajority: handles edge case where 2/3 calculation")
    void smallestSuperMajorityEdgeCase() {
        final List<Node> nodes = List.of(createNode(3), createNode(3), createNode(3));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(result).hasSize(3);
        assertThat(resultWeight).isEqualTo(9);
    }

    @Test
    @DisplayName("smallestSuperMajority: selects single large node when available")
    void smallestSuperMajoritySingleLargeNode() {
        final List<Node> nodes = List.of(createNode(300), createNode(200), createNode(100), createNode(1200));
        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        final long total = totalWeight(nodes);
        final long resultWeight = totalWeight(result);

        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(resultWeight, total)).isTrue();
        assertThat(resultWeight).isEqualTo(1300);
    }

    @Test
    @DisplayName("smallestSuperMajority: excludes zero-weight nodes")
    void smallestSuperMajorityExcludesZeroWeight() {
        final Node zeroNode1 = createNode(0);
        final Node zeroNode2 = createNode(0);
        final List<Node> nodes = List.of(zeroNode1, createNode(100), createNode(150), zeroNode2, createNode(200));

        final List<Node> result = QuorumCalculator.smallestSuperMajority(nodes);

        assertThat(result).noneMatch(node -> node.weight() == 0);
        assertThat(result).doesNotContain(zeroNode1, zeroNode2);
        assertThat(totalWeight(result)).isEqualTo(350);
    }

    // ==================== Comprehensive scenario tests ====================

    @Test
    @DisplayName("All methods work correctly with 5 different weight nodes")
    void allMethodsWithFiveDifferentNodes() {
        final List<Node> nodes =
                List.of(createNode(50), createNode(100), createNode(100), createNode(125), createNode(125));

        final long total = totalWeight(nodes);

        final List<Node> strongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(totalWeight(strongMinority)).isEqualTo(175);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isFalse();

        final List<Node> majority = QuorumCalculator.smallestMajority(nodes);
        assertThat(totalWeight(majority)).isEqualTo(275);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isFalse();

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(totalWeight(superMajority)).isGreaterThan(333);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
    }

    @Test
    @DisplayName("All methods work correctly with different weights")
    void allMethodsWithDifferentWeights() {
        final List<Node> nodes =
                List.of(createNode(100), createNode(200), createNode(300), createNode(400), createNode(200));

        final long total = totalWeight(nodes);

        final List<Node> strongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(totalWeight(strongMinority)).isEqualTo(400);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isFalse();

        final List<Node> majority = QuorumCalculator.smallestMajority(nodes);
        assertThat(totalWeight(majority)).isEqualTo(700);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isFalse();

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(totalWeight(superMajority)).isGreaterThan(800);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
    }

    @Test
    @DisplayName("Handles large number of small equal nodes")
    void handlesMultipleSmallNodes() {
        final List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(createNode(10));
        }

        final long total = totalWeight(nodes);

        final List<Node> strongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(strongMinority).hasSize(4);
        assertThat(totalWeight(strongMinority)).isEqualTo(40);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isFalse();

        final List<Node> majority = QuorumCalculator.smallestMajority(nodes);
        assertThat(majority).hasSize(6);
        assertThat(totalWeight(majority)).isEqualTo(60);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isFalse();

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(superMajority).hasSize(7);
        assertThat(totalWeight(superMajority)).isEqualTo(70);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
    }

    @Test
    @DisplayName("Handles nodes with zero weight")
    void handlesZeroWeightNodes() {
        final List<Node> nodes = List.of(createNode(0), createNode(100), createNode(200), createNode(0));

        final long total = totalWeight(nodes);

        final List<Node> strongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(totalWeight(strongMinority)).isEqualTo(100);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isFalse();
        assertThat(strongMinority).noneMatch(node -> node.weight() == 0);
        assertThat(strongMinority).hasSize(1);

        final List<Node> majority = QuorumCalculator.smallestMajority(nodes);
        assertThat(totalWeight(majority)).isEqualTo(200);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isFalse();
        assertThat(majority).noneMatch(node -> node.weight() == 0);
        assertThat(majority).hasSize(1);

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(totalWeight(superMajority)).isEqualTo(300);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
        assertThat(superMajority).noneMatch(node -> node.weight() == 0);
        assertThat(superMajority).hasSize(2);
    }

    @Test
    @DisplayName("Handles asymmetric weight distribution")
    void handlesAsymmetricWeightDistribution() {
        final List<Node> nodes =
                List.of(createNode(800), createNode(50), createNode(50), createNode(50), createNode(50));

        final long total = totalWeight(nodes);

        assertThatThrownBy(() -> QuorumCalculator.smallestStrongMinority(nodes))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> QuorumCalculator.smallestMajority(nodes)).isInstanceOf(IllegalArgumentException.class);

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(totalWeight(superMajority)).isEqualTo(800);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
    }

    @Test
    @DisplayName("All methods including largestSubStrongMinority work correctly")
    void allMethodsIncludingLargestSubStrongMinority() {
        final List<Node> nodes = List.of(
                createNode(50), createNode(100), createNode(150), createNode(200), createNode(250), createNode(250));

        final long total = totalWeight(nodes);

        final List<Node> largestSubStrongMinority = QuorumCalculator.largestSubStrongMinority(nodes);
        assertThat(totalWeight(largestSubStrongMinority)).isLessThan(334);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(largestSubStrongMinority), total))
                .isFalse();
        assertThat(totalWeight(largestSubStrongMinority)).isEqualTo(300);

        final List<Node> smallestStrongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(smallestStrongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(smallestStrongMinority), total))
                .isFalse();
        assertThat(totalWeight(smallestStrongMinority)).isEqualTo(350);

        final List<Node> smallestMajority = QuorumCalculator.smallestMajority(nodes);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(smallestMajority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(smallestMajority), total))
                .isFalse();
        assertThat(totalWeight(smallestMajority)).isGreaterThan(500);
        assertThat(totalWeight(smallestMajority)).isLessThanOrEqualTo(666);

        final List<Node> smallestSuperMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(smallestSuperMajority), total))
                .isTrue();
        assertThat(totalWeight(smallestSuperMajority)).isGreaterThan(666);
    }

    @Test
    @DisplayName("Zero-weight nodes are never selected in any quorum")
    void zeroWeightNodesNeverSelected() {
        // Create a mix of zero and non-zero weight nodes
        final Node zeroNode1 = createNode(0);
        final Node zeroNode2 = createNode(0);
        final Node node50 = createNode(50);
        final Node node100 = createNode(100);
        final Node node200 = createNode(200);

        final List<Node> nodes = List.of(zeroNode1, node50, zeroNode2, node100, node200);

        // Test all quorum calculation methods
        final List<Node> largestSubStrong = QuorumCalculator.largestSubStrongMinority(nodes);
        final List<Node> smallestStrong = QuorumCalculator.smallestStrongMinority(nodes);
        final List<Node> smallestMaj = QuorumCalculator.smallestMajority(nodes);
        final List<Node> smallestSuper = QuorumCalculator.smallestSuperMajority(nodes);

        // Verify none of the results contain zero-weight nodes
        assertThat(largestSubStrong).noneMatch(node -> node.weight() == 0);
        assertThat(smallestStrong).noneMatch(node -> node.weight() == 0);
        assertThat(smallestMaj).noneMatch(node -> node.weight() == 0);
        assertThat(smallestSuper).noneMatch(node -> node.weight() == 0);

        // Verify the specific zero nodes are not included
        assertThat(largestSubStrong).doesNotContain(zeroNode1, zeroNode2);
        assertThat(smallestStrong).doesNotContain(zeroNode1, zeroNode2);
        assertThat(smallestMaj).doesNotContain(zeroNode1, zeroNode2);
        assertThat(smallestSuper).doesNotContain(zeroNode1, zeroNode2);
    }

    @Test
    @DisplayName("Byzantine fault tolerance scenario with proper partitions")
    void byzantineFaultToleranceScenario() {
        final List<Node> nodes = List.of(createNode(100), createNode(150), createNode(200), createNode(250));

        final long total = totalWeight(nodes);

        final List<Node> strongMinority = QuorumCalculator.smallestStrongMinority(nodes);
        assertThat(totalWeight(strongMinority)).isEqualTo(250);
        assertThat(Threshold.STRONG_MINORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isTrue();
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(strongMinority), total))
                .isFalse();

        final List<Node> majority = QuorumCalculator.smallestMajority(nodes);
        assertThat(totalWeight(majority)).isEqualTo(400);
        assertThat(Threshold.MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isTrue();
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(majority), total))
                .isFalse();

        final List<Node> superMajority = QuorumCalculator.smallestSuperMajority(nodes);
        assertThat(totalWeight(superMajority)).isEqualTo(500);
        assertThat(Threshold.SUPER_MAJORITY.isSatisfiedBy(totalWeight(superMajority), total))
                .isTrue();
    }
}
