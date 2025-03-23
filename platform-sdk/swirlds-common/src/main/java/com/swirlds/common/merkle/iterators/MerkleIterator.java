// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators;

import com.swirlds.common.io.utility.IOConsumer;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.internal.BreadthFirstAlgorithm;
import com.swirlds.common.merkle.iterators.internal.MerkleIterationAlgorithm;
import com.swirlds.common.merkle.iterators.internal.NullNode;
import com.swirlds.common.merkle.iterators.internal.PostOrderedDepthFirstAlgorithm;
import com.swirlds.common.merkle.iterators.internal.PostOrderedDepthFirstRandomAlgorithm;
import com.swirlds.common.merkle.iterators.internal.PreOrderedDepthFirstAlgorithm;
import com.swirlds.common.merkle.iterators.internal.ReversePostOrderedDepthFirstAlgorithm;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;

/**
 * Iterate over a merkle tree.
 *
 * @param <T>
 * 		The type of the node returned by this iterator.
 */
public class MerkleIterator<T extends MerkleNode> implements Iterator<T> {

    /**
     * The root of the tree being iterated.
     */
    private final MerkleNode root;

    /**
     * The next node to be returned by this iterator
     */
    private T next;

    /**
     * The route of the next node to be returned by this iterator.
     */
    private MerkleRoute nextRoute;

    /**
     * The merkle route of the node that was most recently returned by {@link #next}.
     */
    private MerkleRoute previousRoute;

    /**
     * True if the value contained should be returned by the iterator
     */
    private boolean hasNext;

    /**
     * If the filter is not null, do not return a node if the filter returns false for that node.
     */
    private BiPredicate<MerkleNode, MerkleRoute> filter;

    /**
     * If not null, do not return any nodes below any internal nodes that
     * causes this method to return false.
     */
    private Predicate<MerkleInternal> descendantFilter;

    /**
     * If true then don't return null nodes, and don't pass any null nodes to the
     * {@link #filter} or {@link #descendantFilter}.
     */
    private boolean ignoreNull = true;

    /**
     * The iteration order used by this algorithm.
     */
    private MerkleIterationOrder order = MerkleIterationOrder.POST_ORDERED_DEPTH_FIRST;

    /**
     * The algorithm that implements the proper iteration order.
     */
    private MerkleIterationAlgorithm algorithm;

    /**
     * Create a new iterator.
     *
     * @param root
     * 		the root of the tree
     */
    public MerkleIterator(final MerkleNode root) {
        this.root = root;
    }

    /**
     * Provide an optional filter. If not null, do not return any node that causes the filter to return false.
     *
     * @param filter
     * 		a predicate that is applied to every node before it can be returned.
     * 		If {@link #ignoreNull(boolean)} has been called and set to true then this
     * 		method will never be passed a null node.
     * @return this object
     * @throws IllegalStateException
     * 		if called after {@link #hasNext()} or {@link #next()}
     */
    public MerkleIterator<T> setFilter(final Predicate<MerkleNode> filter) {
        setFilter((final MerkleNode node, final MerkleRoute route) -> filter.test(node));
        return this;
    }

    /**
     * Provide an optional filter. If not null, do not return any node that causes the filter to return false.
     *
     * @param filter
     * 		a predicate that is applied to every node before it can be returned.
     * 		If {@link #ignoreNull(boolean)} has been called and set to true then this
     * 		method will never be passed a null node. The merkle route corresponds the merkle
     * 		route of the node, which is useful if null valuess are being filtered.
     * @return this object
     * @throws IllegalStateException
     * 		if called after {@link #hasNext()} or {@link #next()}
     */
    public MerkleIterator<T> setFilter(final BiPredicate<MerkleNode, MerkleRoute> filter) {
        if (algorithm != null) {
            throw new IllegalStateException("iterator can not be configured after iteration has started");
        }
        this.filter = filter;
        return this;
    }

    /**
     * Provide an optional filter that can exclude descendants of a node. If this method returns false for an internal
     * node, no node descendant from that internal node will be returned. An internal node that causes the descendant
     * filter to return false can still itself be returned if the filter specified by {@link #setFilter(Predicate)}
     * returns true for that internal node.
     *
     * @param descendantFilter
     * 		only nodes that cause this filter to return true will have their descendants iterated over
     * @return this object
     * @throws IllegalStateException
     * 		if called after {@link #hasNext()} or {@link #next()}
     */
    public MerkleIterator<T> setDescendantFilter(final Predicate<MerkleInternal> descendantFilter) {
        if (algorithm != null) {
            throw new IllegalStateException("iterator can not be configured after iteration has started");
        }
        this.descendantFilter = descendantFilter;
        return this;
    }

    /**
     * Specify if null nodes should be returned. If true then null nodes will never be returned, and null nodes will
     * never be passed to the predicate set by {@link #setFilter(Predicate)}. Default value is true.
     *
     * @param ignoreNullNodes
     * 		if true then ignore null nodes (default), if false then return null nodes
     * @return this object
     * @throws IllegalStateException
     * 		if called after {@link #hasNext()} or {@link #next()}
     */
    public MerkleIterator<T> ignoreNull(final boolean ignoreNullNodes) {
        if (algorithm != null) {
            throw new IllegalStateException("iterator can not be configured after iteration has started");
        }
        this.ignoreNull = ignoreNullNodes;
        return this;
    }

    /**
     * Specify the iteration order. If unset, default order is {@link MerkleIterationOrder#POST_ORDERED_DEPTH_FIRST}.
     *
     * @param order
     * 		an iteration order
     * @return this object
     * @throws IllegalStateException
     * 		if called after {@link #hasNext()} or {@link #next()}
     */
    public MerkleIterator<T> setOrder(final MerkleIterationOrder order) {
        this.order = order;
        return this;
    }

    /**
     * A filter that is applied to each internal node. If false then none of the nodes descended from
     * the internal node will be returned.
     *
     * @param node
     * 		the internal node in question
     * @return false then none of this node's descendants will be returned by the iterator
     */
    private boolean shouldVisitDescendants(final MerkleInternal node) {
        if (descendantFilter != null) {
            return descendantFilter.test(node);
        }
        return true;
    }

    /**
     * A filter that is applied to each node. If false then this node will not be returned by the iterator.
     * Does not stop the iterator from possibly returning descendant nodes.
     */
    private boolean shouldNodeBeReturned(final MerkleNode node, final MerkleRoute route) {
        if (ignoreNull && node == null) {
            return false;
        }
        if (filter != null) {
            return filter.test(node, route);
        }
        return true;
    }

    /**
     * Check if this node has children that need to be added to the stack/queue.
     */
    private boolean hasChildrenToHandle(MerkleNode node) {
        // If an internal node has children that need to be handled it will have been added to the stack twice in a row
        return node != null && node.isInternal() && algorithm.size() > 0 && algorithm.peek() == node;
    }

    /**
     * Fetch a node and push it onto the stack.
     *
     * @param parent
     * 		the parent of the node to push
     * @param childIndex
     * 		the index within the parent of the node to push
     */
    protected final void pushNode(final MerkleInternal parent, final int childIndex) {
        final MerkleNode node = parent.getChild(childIndex);

        // FUTURE WORK: filter here, for certain iterations may significantly reduce number
        //  of objects pushed to the queue. Instead of pushing internal nodes twice, wrap
        //  in small container object that holds needed metadata. Provide an option for
        //  descendant filter + regular filter to be the same function that is called only
        //  once.

        if (node == null) {
            if (!ignoreNull) {
                pushNode(new NullNode(parent.getRoute().extendRoute(childIndex)));
            }
        } else {
            pushNode(node);
        }
    }

    /**
     * Push a node onto the stack.
     *
     * @param node
     * 		the node to push
     */
    private void pushNode(final MerkleNode node) {
        algorithm.push(node);
        if (node.isInternal() && shouldVisitDescendants(node.asInternal())) {
            // Internal nodes with children to handle are pushed twice
            // This sends a signal to handle that node's children when the time comes
            algorithm.push(node);
        }
    }

    /**
     * Do initialization if it is required.
     */
    private void setup() {
        if (algorithm == null) {
            switch (order) {
                case POST_ORDERED_DEPTH_FIRST -> algorithm = new PostOrderedDepthFirstAlgorithm();
                case REVERSE_POST_ORDERED_DEPTH_FIRST -> algorithm = new ReversePostOrderedDepthFirstAlgorithm();
                case POST_ORDERED_DEPTH_FIRST_RANDOM -> algorithm = new PostOrderedDepthFirstRandomAlgorithm();
                case PRE_ORDERED_DEPTH_FIRST -> algorithm = new PreOrderedDepthFirstAlgorithm();
                case BREADTH_FIRST -> algorithm = new BreadthFirstAlgorithm();
                default -> throw new UnsupportedOperationException("unhandled iteration algorithm " + order);
            }
            if (root != null) {
                pushNode(root);
            }
        }
    }

    /**
     * Iterate over the merkle tree until the next MerkleNode to be returned is found.
     */
    @SuppressWarnings("unchecked")
    private void findNext() {
        setup();

        if (hasNext) {
            return;
        }

        while (algorithm.size() > 0) {
            final MerkleNode candidate = algorithm.pop();
            nextRoute = candidate.getRoute();
            final MerkleNode target = candidate.getClassId() == NullNode.CLASS_ID ? null : candidate;

            if (hasChildrenToHandle(target)) {
                algorithm.pushChildren(target.asInternal(), this::pushNode);
            } else if (shouldNodeBeReturned(target, nextRoute)) {
                next = (T) target;
                hasNext = true;
                return;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean hasNext() {
        findNext();
        return hasNext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final T next() {
        findNext();
        if (!hasNext) {
            throw new NoSuchElementException();
        }

        previousRoute = nextRoute;

        hasNext = false;
        return next;
    }

    /**
     * Get the merkle route of the most recently returned node. Useful for obtaining the route when iterating over
     * nodes that may be null.
     *
     * @return the merkle route of the most recently returned node, or null if no node has been returned
     */
    public final MerkleRoute getRoute() {
        return previousRoute;
    }

    /**
     * Convert this iterator into an iterator that walks over the same data but returns a different type.
     * Calling {@link #next()} on this iterator will update the transformed iterator, and vice versa.
     *
     * @param converter
     * 		a method that converts from the original type of the iterator to the new iterator type
     * @param <K>
     * 		the new type that the iterator will return
     * @return an iterator that walks over the same data but returns a different type
     */
    public <K> Iterator<K> transform(final Function<T, K> converter) {
        return transform((final T node, final MerkleRoute route) -> converter.apply(node));
    }

    /**
     * Convert this iterator into an iterator that walks over the same data but returns a different type.
     * Calling {@link #next()} on this iterator will update the transformed iterator, and vice versa.
     *
     * @param converter
     * 		a method that converts from the original type of the iterator to the new iterator type
     * @param <K>
     * 		the new type that the iterator will return
     * @return an iterator that walks over the same data but returns a different type
     */
    public <K> Iterator<K> transform(final BiFunction<T, MerkleRoute, K> converter) {
        final MerkleIterator<T> originalIterator = this;

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return originalIterator.hasNext();
            }

            @Override
            public K next() {
                final T node = originalIterator.next();
                final MerkleRoute route = originalIterator.getRoute();

                return converter.apply(node, route);
            }
        };
    }

    /**
     * Similar to {@link Iterator#forEachRemaining(Consumer)}, except that the action is allowed
     * to throw an {@link IOException}.
     *
     * @param action
     * 		the action to perform
     * @throws IOException
     * 		if the action throws an IO exception
     */
    public void forEachRemainingWithIO(final IOConsumer<? super T> action) throws IOException {
        while (hasNext()) {
            action.accept(next());
        }
    }

    /**
     * Similar to {@link Iterator#forEachRemaining(Consumer)}, except that the action is allowed
     * to throw an {@link InterruptedException}.
     *
     * @param action
     * 		the action to perform
     * @throws InterruptedException
     * 		if the action throws an interrupted exception
     */
    public void forEachRemainingWithInterrupt(final InterruptableConsumer<? super T> action)
            throws InterruptedException {
        while (hasNext()) {
            action.accept(next());
        }
    }
}
