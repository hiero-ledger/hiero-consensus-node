// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.fcqueue.internal.FCQueueNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Disabled
class FCQueueGraphTest {

    @BeforeEach
    void printHeader() {
        System.out.println("%%{init: { 'theme': 'neutral', 'fontFamily': 'arial', 'fontSize': '14px' } }%%\n");
        System.out.println("graph LR");
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertions(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();
        final Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ", queue);
        copies.add(queuePair);

        System.out.println("subgraph mainGraph[Additions]");
        for (int index = 0; index < limit; index++) {
            queue.add(new FCInt(index));
            printMermaidGraph(String.format("After adding N%d", index), limit, String.valueOf(index), copies, links);
        }

        System.out.println("end");

        queue.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertionsAndDeletions(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();
        final Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ", queue);
        copies.add(queuePair);

        System.out.println("subgraph mainGraph[Additions with deletions]");
        for (int index = 0; index < limit; index++) {
            queue.add(new FCInt(index));
        }

        printMermaidGraph(
                String.format("After adding N%d", limit - 1), limit, String.valueOf(limit - 1), copies, links);

        for (int index = 0; index < limit; index++) {
            queue.remove();

            printMermaidGraph(
                    String.format("After removing N%d", index), limit, String.format("r%d", index), copies, links);
        }

        System.out.println("end");

        queue.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertionsWithCopying(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();

        System.out.println("subgraph mainGraph[Additions with Copying]");
        Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ0", queue);
        copies.add(queuePair);
        for (int index = 0; index < limit; index++) {
            queuePair.value().add(new FCInt(index));
            printMermaidGraph(String.format("After adding N%d", index), limit, String.valueOf(index), copies, links);
            queue = queue.copy();
            queuePair = Pair.of(String.format("FCQ%d", index + 1), queue);
            copies.add(queuePair);
            printMermaidGraph(
                    String.format("After creating a copy of FCQ%d", index),
                    limit,
                    String.format("c%d", index),
                    copies,
                    links);
        }

        System.out.println("end");

        copies.forEach(p -> p.value().release());

        queue.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertionsCopyingAndReleasing(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();

        System.out.println("subgraph mainGraph[Additions with Copying and Releasing]");
        Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ0", queue);
        copies.add(queuePair);
        for (int index = 0; index < limit; index++) {
            queuePair.value().add(new FCInt(index));
            queue = queue.copy();
            queuePair = Pair.of(String.format("FCQ%d", index + 1), queue);
            copies.add(queuePair);
        }

        printMermaidGraph(
                String.format("After creating a copy of FCQ%d", limit - 1),
                limit,
                String.format("icre%d", limit - 1),
                copies,
                links);

        for (int index = 0; index < limit; index++) {
            final Pair<String, AccessibleFCQueue<FCInt>> copy = copies.remove(0);
            copy.value().release();

            printMermaidGraph(
                    String.format("After releasing FCQ%d", index), limit, String.format("re%d", index), copies, links);
        }

        System.out.println("end");

        queue.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertionsCopyingDeletingAndReleasing(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();

        System.out.println("subgraph mainGraph[Additions with Copying, Deleting and Releasing]");
        Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ0", queue);
        copies.add(queuePair);
        for (int index = 0; index < limit; index++) {
            queuePair.value().add(new FCInt(index));
            queue = queue.copy();
            queuePair = Pair.of(String.format("FCQ%d", index + 1), queue);
            copies.add(queuePair);
        }

        printMermaidGraph(
                String.format("After creating a copy of FCQ%d", limit - 1),
                limit,
                String.format("icre%d", limit - 1),
                copies,
                links);

        final int mutableIndex = copies.size() - 1;
        final Pair<String, AccessibleFCQueue<FCInt>> mutableQueuePair = copies.get(mutableIndex);
        final AccessibleFCQueue<FCInt> mutableQueue = mutableQueuePair.value();

        for (int index = 0; index < limit - 1; index++) {
            mutableQueue.remove();
            printMermaidGraph(
                    String.format("After removing N%d", index),
                    limit,
                    String.format("icremoval%d", index),
                    copies,
                    links);
        }

        for (int index = 0; index < limit - 1; index++) {
            final Pair<String, AccessibleFCQueue<FCInt>> copy = copies.remove(0);
            copy.value().release();

            printMermaidGraph(
                    String.format("After releasing FCQ%d", index), limit, String.format("re%d", index), copies, links);
        }

        mutableQueue.release();
        System.out.println("end");
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void refCountForInsertionsCopyingInsertionsAndRemovals(final int limit) {
        AccessibleFCQueue<FCInt> queue = new AccessibleFCQueue<>();
        final List<Pair<String, AccessibleFCQueue<FCInt>>> copies = new ArrayList<>();
        final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links = new HashMap<>();

        System.out.println("subgraph mainGraph[Additions, Copying, Additions, and Deletions]");
        Pair<String, AccessibleFCQueue<FCInt>> queuePair = Pair.of("FCQ0", queue);
        copies.add(queuePair);
        for (int index = 0; index < limit; index++) {
            queuePair.value().add(new FCInt(index));
        }

        printMermaidGraph(
                String.format("After inserting N%d", limit - 1),
                limit,
                String.format("icir%d", limit - 1),
                copies,
                links);

        final AccessibleFCQueue<FCInt> mutableQueue = queue.copy();
        final Pair<String, AccessibleFCQueue<FCInt>> mutablePair = Pair.of("FCQ1", mutableQueue);
        copies.add(mutablePair);

        printMermaidGraph("After copying FCQ0", limit, String.format("icirc%d", limit - 1), copies, links);

        for (int index = limit; index < 2 * limit; index++) {
            mutableQueue.add(new FCInt(index));
        }

        printMermaidGraph(
                "After inserting N13", mutableQueue.size(), String.format("icirci%d", 2 * limit - 1), copies, links);

        for (int index = 0; index < limit; index++) {
            mutableQueue.remove();
        }

        printMermaidGraph(
                "After removing N6", mutableQueue.size(), String.format("icircir%d", 2 * limit - 1), copies, links);

        //		queue.release();
        //		copies.remove(0);
        //		printMermaidGraph("After releasing FCQ0",
        //				mutableQueue.size(),
        //				String.format("icircirr%d", 2*limit - 1),
        //				copies,
        //				links);

        System.out.println("end");
    }

    private void printMermaidGraph(
            final String name,
            final int limit,
            final String prefixId,
            List<Pair<String, AccessibleFCQueue<FCInt>>> copies,
            final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links) {

        final Map<FCQueueNode<FCInt>, String> visitedNodes = new HashMap<>();
        System.out.printf("\tsubgraph graph%d%s[%s]%n", limit, prefixId, name);
        int queueIndex = 0;
        for (final Pair<String, AccessibleFCQueue<FCInt>> copy : copies) {
            printQueue(prefixId, queueIndex, copy, visitedNodes, links);
            queueIndex++;
        }

        System.out.println("\tend\n");
        links.clear();
    }

    private void printQueue(
            final String prefixId,
            final int queueIndex,
            final Pair<String, AccessibleFCQueue<FCInt>> queuePair,
            final Map<FCQueueNode<FCInt>, String> visitedNodes,
            final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links) {
        final String headId = String.format("H%s%d", prefixId, queueIndex);
        final String tailId = String.format("T%s%d", prefixId, queueIndex);
        final String queueName = queuePair.key();
        final AccessibleFCQueue<FCInt> queue = queuePair.value();
        if (queue.isEmpty()) {
            System.out.printf("\t\tFCQ%s%d[%s]\n", prefixId, queueIndex, queueName);
            return;
        }

        System.out.printf("\t\tFCQ%s%d[%s] --> %s(head)\n", prefixId, queueIndex, queuePair.key(), headId);
        System.out.printf("\t\tFCQ%s%d[%s] --> %s(tail)\n", prefixId, queueIndex, queuePair.key(), tailId);

        final FCQueueNode<FCInt> head = queue.getHead();
        final FCQueueNode<FCInt> tail = queue.getTail();

        int nodeIndex = 0;
        nodeIndex = printLink(nodeIndex, headId, prefixId, head, visitedNodes);
        nodeIndex = printLink(nodeIndex, tailId, prefixId, tail, visitedNodes);

        FCQueueNode<FCInt> node = head;
        while (node != null) {
            final String nodeId = visitedNodes.get(node);
            final FCQueueNode<FCInt> nextNode = node.getTowardTail();
            if (nextNode == null) {
                break;
            }

            if (isLinkRequired(node, nextNode, links)) {
                nodeIndex = printLink(nodeIndex, nodeId, prefixId, nextNode, visitedNodes);
                final Set<FCQueueNode<FCInt>> nodes = links.get(node);
                nodes.add(nextNode);
            }

            final String nextNodeId = visitedNodes.get(nextNode);
            if (isLinkRequired(nextNode, node, links)) {
                printLink(nodeIndex, nextNodeId, prefixId, node, visitedNodes);
                final Set<FCQueueNode<FCInt>> nodes = links.get(nextNode);
                nodes.add(node);
            }

            node = nextNode;
        }
    }

    private boolean isLinkRequired(
            final FCQueueNode<FCInt> node01,
            final FCQueueNode<FCInt> node02,
            final Map<FCQueueNode<FCInt>, Set<FCQueueNode<FCInt>>> links) {

        Set<FCQueueNode<FCInt>> nodes = links.computeIfAbsent(node01, k -> new HashSet<>());

        return !nodes.contains(node02);
    }

    private int printLink(
            int nodeIndex,
            final String previousNodeId,
            final String prefixId,
            final FCQueueNode<FCInt> node,
            final Map<FCQueueNode<FCInt>, String> visitedNodes) {
        if (node == null) {
            return nodeIndex;
        }

        if (visitedNodes.containsKey(node)) {
            final String nodeId = visitedNodes.get(node);
            System.out.printf("\t\t%s -.-> %s", previousNodeId, nodeId);
        } else {
            final String nodeId = String.format("N%s%d", prefixId, nodeIndex);
            System.out.printf(
                    "\t\t%s -.-> %s(N%d<br/>refCount = %d)",
                    previousNodeId, nodeId, node.getElement().getValue(), node.getRefCount());
            nodeIndex++;
            visitedNodes.put(node, nodeId);
        }

        System.out.println();
        return nodeIndex;
    }
}
