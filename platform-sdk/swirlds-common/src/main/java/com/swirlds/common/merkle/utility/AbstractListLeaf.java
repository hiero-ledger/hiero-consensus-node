// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A list embedded within a merkle leaf.
 *
 * @param <T>
 * 		The type contained by the list.
 */
public abstract class AbstractListLeaf<T extends FastCopyable & SelfSerializable> extends PartialMerkleLeaf
        implements List<T>, MerkleLeaf {

    /**
     * Version information for AbstractEmbeddedListLeaf.
     */
    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    protected List<T> elements;

    /**
     * Create a new AbstractEmbeddedListLeaf.
     */
    protected AbstractListLeaf() {
        elements = new ArrayList<>();
    }

    /**
     * Initialize this list from another list. Deep copies the given list.
     *
     * @param elements
     * 		The list of elements to copy.
     */
    @SuppressWarnings("unchecked")
    protected AbstractListLeaf(final List<T> elements) {
        if (elements.size() > getMaxSize()) {
            throw new IllegalArgumentException("Provided list exceeds maximum size");
        }
        this.elements = new ArrayList<>(elements.size());
        for (final T element : elements) {
            this.elements.add((T) element.copy());
        }
    }

    /**
     * Copy constructor. Use this to implement child's copy constructor.
     */
    @SuppressWarnings("unchecked")
    protected AbstractListLeaf(final AbstractListLeaf<T> that) {
        super(that);

        this.elements = new ArrayList<>(that.elements.size());
        for (final T element : that.elements) {
            this.elements.add((T) element.copy());
        }
    }

    /**
     * Create a new AbstractEmbeddedListLeaf with an initial size.
     *
     * @param initialSize
     * 		The initial size of the list.
     */
    public AbstractListLeaf(final int initialSize) {
        elements = new ArrayList<>(initialSize);
    }

    /**
     * The maximum size that the list is allowed to be (for deserialization safety).
     */
    protected int getMaxSize() {
        return Integer.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeSerializableList(elements, true, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, int version) throws IOException {
        elements = in.readSerializableList(getMaxSize());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return elements.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(Object o) {
        return elements.contains(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<T> iterator() {
        return elements.iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] toArray() {
        return elements.toArray();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T1> T1[] toArray(final T1[] a) {
        return elements.toArray(a);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(final T t) {
        return elements.add(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(final Object o) {
        return elements.remove(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsAll(final Collection<?> c) {
        return elements.containsAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(final Collection<? extends T> c) {
        return elements.addAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(final int index, final Collection<? extends T> c) {
        return elements.addAll(index, c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAll(final Collection<?> c) {
        return elements.removeAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean retainAll(final Collection<?> c) {
        return elements.retainAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        elements.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T get(final int index) {
        return elements.get(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T set(final int index, final T element) {
        return elements.set(index, element);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final int index, final T element) {
        elements.add(index, element);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T remove(final int index) {
        return elements.remove(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int indexOf(final Object o) {
        return elements.indexOf(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int lastIndexOf(final Object o) {
        return elements.lastIndexOf(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListIterator<T> listIterator() {
        return elements.listIterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListIterator<T> listIterator(final int index) {
        return elements.listIterator(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<T> subList(final int fromIndex, final int toIndex) {
        return elements.subList(fromIndex, toIndex);
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("List size: ").append(elements.size());
        sb.append(" [");
        for (int index = 0; index < elements.size(); index++) {
            final T element = elements.get(index);
            sb.append((element == null) ? "null" : element.toString());
            if (index + 1 < elements.size()) {
                sb.append(", ");
            }
        }
        sb.append("]");

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract AbstractListLeaf<T> copy();
}
