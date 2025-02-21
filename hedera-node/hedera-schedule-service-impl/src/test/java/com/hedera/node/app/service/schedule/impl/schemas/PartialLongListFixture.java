// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.schemas;

import java.util.List;
import java.util.Spliterator;
import org.eclipse.collections.api.LazyLongIterable;
import org.eclipse.collections.api.LongIterable;
import org.eclipse.collections.api.bag.primitive.MutableLongBag;
import org.eclipse.collections.api.block.function.primitive.LongToObjectFunction;
import org.eclipse.collections.api.block.function.primitive.ObjectLongIntToObjectFunction;
import org.eclipse.collections.api.block.function.primitive.ObjectLongToObjectFunction;
import org.eclipse.collections.api.block.predicate.primitive.LongPredicate;
import org.eclipse.collections.api.block.procedure.primitive.LongIntProcedure;
import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.list.primitive.LongList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.set.primitive.MutableLongSet;

/**
 * A partial implementation of {@link LongList} that delegates to a regular java.lang {@link List}. This
 * class exists solely to avoid depending on any eclipse implementations in this module's test code.
 */
class PartialLongListFixture implements LongList {
    private final List<Long> list;

    PartialLongListFixture(final List<Long> list) {
        this.list = list;
    }

    @Override
    public long get(int i) {
        return list.get(i);
    }

    @Override
    public long dotProduct(LongList longList) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public int binarySearch(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public int lastIndexOf(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long getLast() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LazyLongIterable asReversed() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long getFirst() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public int indexOf(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongIterator longIterator() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long[] toArray() {
        return list.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    public boolean contains(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean containsAll(long... longs) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean containsAll(LongIterable longIterable) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public void forEach(LongProcedure longProcedure) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public void each(LongProcedure longProcedure) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongList select(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongList reject(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public <V> ListIterable<V> collect(LongToObjectFunction<? extends V> longToObjectFunction) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long detectIfNone(LongPredicate longPredicate, long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public int count(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean anySatisfy(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean allSatisfy(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public boolean noneSatisfy(LongPredicate longPredicate) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public MutableLongList toList() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public MutableLongSet toSet() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public MutableLongBag toBag() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LazyLongIterable asLazy() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public <T> T injectInto(T t, ObjectLongToObjectFunction<? super T, ? extends T> objectLongToObjectFunction) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long sum() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long max() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long maxIfEmpty(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long min() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long minIfEmpty(long l) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public double average() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public double median() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public long[] toSortedArray() {
        return new long[0];
    }

    @Override
    public MutableLongList toSortedList() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public ImmutableLongList toImmutable() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongList distinct() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public <T> T injectIntoWithIndex(
            T t, ObjectLongIntToObjectFunction<? super T, ? extends T> objectLongIntToObjectFunction) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public void forEachWithIndex(LongIntProcedure longIntProcedure) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongList toReversed() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public LongList subList(int i, int i1) {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public Spliterator.OfLong spliterator() {
        throw new UnsupportedOperationException("not supported");
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public void appendString(Appendable appendable, String s, String s1, String s2) {
        throw new UnsupportedOperationException("not supported");
    }
}
