// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.constructable.RuntimeConstructable;

/**
 * Keeps track of the {@link RuntimeConstructable} objects currently in memory.
 * One registry per process.
 */
public final class RuntimeObjectRegistry {

    /**
     * Contains records of runtime objects, per class, that are currently in memory or that were recently in memory.
     */
    private static final Map<Class<?>, List<RuntimeObjectRecord>> RECORDS = new ConcurrentHashMap<>();

    /**
     * The time provider. This is used to get the current time for the records.
     *
     * <p>We also use this field to check if the registry has already been initialized by checking if it is {@code null}.
     * An {@link AtomicReference} is used to make the check thread-safe.
     *
     */
    private static final AtomicReference<Time> time = new AtomicReference<>(null);

    private RuntimeObjectRegistry() {}

    /**
     * Initialize the registry with a {@link Time} instance. This should be called once at the beginning of the program.
     * The {@link Time} instance is used to get the current time for the records.
     *
     * @param newValue the new {@link Time} instance
     */
    public static void initialize(@NonNull final Time newValue) {
        if (!RuntimeObjectRegistry.time.compareAndSet(null, requireNonNull(newValue))) {
            throw new IllegalStateException("RuntimeObjectRegistry has already been initialized");
        }
    }

    /**
     * Create a new record for a runtime object with the specified class and add it to the records list.
     * When the record is released, it gets deleted from this registry.
     *
     * @param cls
     * 		the object class
     * @return a new {@link RuntimeObjectRecord}. Should be saved by the runtime object and released
     * 		when the object is released.
     */
    public static <T> RuntimeObjectRecord createRecord(final Class<T> cls) {
        return createRecord(cls, null);
    }

    /**
     * Create a new record for a runtime object with the specified class and add it to the records list.
     * When the record is released, it gets deleted from this registry.
     *
     * @param cls
     * 		the object class
     * @param metadata
     * 		optional metadata associated with the object
     * @return a new {@link RuntimeObjectRecord}. Should be saved by the runtime object and released
     * 		when the object is released.
     */
    public static <T> RuntimeObjectRecord createRecord(final Class<T> cls, final Object metadata) {
        // If not initialized, we use the default time provider
        time.compareAndSet(null, Time.getCurrent());
        final Instant now = time.get().now();
        final List<RuntimeObjectRecord> classRecords =
                RECORDS.computeIfAbsent(cls, clsid -> Collections.synchronizedList(new ArrayList<>()));
        final RuntimeObjectRecord objectRecord = new RuntimeObjectRecord(now, classRecords::remove, metadata);
        classRecords.add(objectRecord);

        return objectRecord;
    }

    /**
     * Get the current number of runtime objects of the specified class tracked in this registry.
     *
     * @param cls
     * 		the objwct class
     */
    public static <T> int getActiveObjectsCount(final Class<T> cls) {
        final List<RuntimeObjectRecord> classRecords = RECORDS.get(cls);
        return classRecords != null ? classRecords.size() : 0;
    }

    /**
     * Get the age of the oldest runtime object of the specified class tracked in this registry.
     *
     * @param cls
     * 		the object class
     * @param now
     * 		the current time
     */
    public static <T> Duration getOldestActiveObjectAge(final Class<T> cls, final Instant now) {
        final List<RuntimeObjectRecord> classRecords = RECORDS.get(cls);
        if (classRecords == null) {
            return Duration.ZERO;
        }
        try {
            // It doesn't make sense to check if the list is empty, as it may become empty at any moment, as
            // the method isn't synchronized. Instead, just catch IOOBE
            final RuntimeObjectRecord oldestRecord = classRecords.get(0);
            return oldestRecord.getAge(now);
        } catch (final IndexOutOfBoundsException e) {
            return Duration.ZERO;
        }
    }

    /**
     * Get the record associated with the oldest runtime object of the specified class tracked in this registry.
     *
     * @param cls the object class
     * @return the oldest record, or null if there are no records available for this class
     */
    public static RuntimeObjectRecord getOldestActiveObjectRecord(final Class<?> cls) {
        return getActiveObjectRecord(cls, true);
    }

    /**
     * Get the record associated with the newest runtime object of the specified class tracked in this registry.
     *
     * @param cls the object class
     * @return the newest record, or null if there are no records available for this class
     */
    public static RuntimeObjectRecord getNewestActiveObjectRecord(final Class<?> cls) {
        return getActiveObjectRecord(cls, false);
    }

    /**
     * Get a record associated with the runtime object of the specified class tracked in this registry.
     *
     * @param cls    the object class
     * @param oldest if true, get the oldest record; if false, get the newest record
     * @return the record requested, or null if there are no records available for this class
     */
    private static RuntimeObjectRecord getActiveObjectRecord(final Class<?> cls, final boolean oldest) {
        final List<RuntimeObjectRecord> classRecords = RECORDS.get(cls);
        if (classRecords == null || classRecords.isEmpty()) {
            return null;
        }
        try {
            // The list may become empty at any moment, as the method isn't synchronized, so we catch the exception
            return oldest ? classRecords.getFirst() : classRecords.getLast();
        } catch (final IndexOutOfBoundsException | NoSuchElementException e) {
            // NoSuchElementException can be thrown by getFirst() or getLast()
            // since getFirst() & getLast() are not synchronized, the get() within them may throw
            // IndexOutOfBoundsException
            return null;
        }
    }

    /**
     * Get object classes tracked in this registry. The set of classes is immutable.
     *
     * @return the list of tracked classes
     */
    public static Set<Class<?>> getTrackedClasses() {
        return Collections.unmodifiableSet(RECORDS.keySet());
    }

    /**
     * Drop all records of active runtime constructable objects. Allows this class to be unit tested
     * without interference from prior unit tests.
     */
    public static void reset() {
        RECORDS.clear();
        time.set(null);
    }
}
