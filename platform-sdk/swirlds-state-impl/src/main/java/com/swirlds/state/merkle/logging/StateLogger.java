// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.logging;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.state.merkle.memory.InMemoryKey;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This utility class provides convenient methods for logging state operations for different types of state types.
 */
public class StateLogger {
    /** The logger we are using for the State log */
    private static final Logger logger = LogManager.getLogger(StateLogger.class);

    /**
     * The name of the thread that handles transactions. For the sake of the app, to allow logging.
     */
    private static final String TRANSACTION_HANDLING_THREAD_NAME = "<scheduler TransactionHandler>";

    /**
     * Log the read of a singleton.
     *
     * @deprecated This method is only used in {@link com.swirlds.state.merkle.singleton.SingletonNode}
     * and can be removed if that class is deleted.
     *
     * @param label The label of the singleton
     * @param value The value of the singleton
     * @param <T> The type of the singleton
     */
    @SuppressWarnings("LoggingSimilarMessage")
    @Deprecated
    public static <T> void logSingletonRead(@NonNull final String label, @Nullable final ValueLeaf<T> value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      READ singleton {} value {}", label, value == null ? "null" : value.getValue());
        }
    }

    /**
     * Log the read of a singleton.
     *
     * @param label The label of the singleton
     * @param value The value of the singleton
     * @param <T> The type of the singleton
     */
    public static <T> void logSingletonRead(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      READ singleton {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log when the value of a singleton is written.
     *
     * @param label The label of the singleton
     * @param value The value of the singleton
     * @param <T> The type of the singleton
     */
    public static <T> void logSingletonWrite(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      WRITTEN singleton {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log when a value is removed from a singleton.
     *
     * @param label The label of the singleton
     * @param value The value of the singleton
     * @param <T> The type of the singleton
     */
    public static <T> void logSingletonRemove(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      REMOVE from singleton {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log when a value is added to a queue.
     *
     * @param label The label of the queue
     * @param value The value added to the queue
     * @param <T> The type of the queue values
     */
    public static <T> void logQueueAdd(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      ADD to queue {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log when a value is removed from a queue.
     *
     * @param label The label of the queue
     * @param value The value removed from the queue
     * @param <T> The type of the queue values
     */
    public static <T> void logQueueRemove(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      REMOVE from queue {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log when queue value is peeked.
     *
     * @param label The label of the queue
     * @param value The value peeked from the queue
     * @param <T> The type of the queue values
     */
    public static <T> void logQueuePeek(@NonNull final String label, @Nullable final T value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      PEEK on queue {} value {}", label, value == null ? "null" : value.toString());
        }
    }

    /**
     * Log the iteration over a queue.
     *
     * @deprecated This method is only used in {@link com.swirlds.state.merkle.queue.QueueNode}
     * and can be removed if that class is deleted.
     *
     * @param label The label of the queue
     * @param queue The queue that was iterated
     * @param <K> The type of the queue values
     */
    @Deprecated
    public static <K> void logQueueIterate(@NonNull final String label, @NonNull final FCQueue<ValueLeaf<K>> queue) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            if (queue.isEmpty()) {
                logger.debug("      ITERATE queue {} size 0 values:EMPTY", label);
            } else {
                logger.debug(
                        "      ITERATE queue {} size {} values:\n{}",
                        label,
                        queue.size(),
                        queue.stream()
                                .map(leaf -> leaf == null ? "null" : leaf.toString())
                                .collect(Collectors.joining(",\n")));
            }
        }
    }

    /**
     * Log the iteration over a queue.
     *
     * @param label The label of the queue
     * @param size The queue size
     * @param it The queue elements iterator
     * @param <K> The type of the queue values
     */
    public static <K> void logQueueIterate(
            @NonNull final String label, final long size, @NonNull final Iterator<K> it) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            if (size == 0) {
                logger.debug("      ITERATE queue {} size 0 values:EMPTY", label);
            } else {
                final Iterable<K> iterable = () -> it;
                logger.debug(
                        "      ITERATE queue {} size {} values:\n{}",
                        label,
                        size,
                        StreamSupport.stream(iterable.spliterator(), false)
                                .map(leaf -> leaf == null ? "null" : leaf.toString())
                                .collect(Collectors.joining(",\n")));
            }
        }
    }

    /**
     * Log the put of an entry in to a map.
     *
     * @param label The label of the map
     * @param key The key added to the map
     * @param value The value added to the map
     * @param <K> The type of the key
     * @param <V> The type of the value
     */
    public static <K, V> void logMapPut(@NonNull final String label, @NonNull final K key, @Nullable final V value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug(
                    "      PUT into map {} key {} value {}",
                    label,
                    formatKey(key),
                    value == null ? "null" : value.toString());
        }
    }

    /**
     * Log the removal of an entry from a map.
     *
     * @param label The label of the map
     * @param key The key removed from the map
     * @param value The value bytes removed from the map
     * @param <K> The type of the key
     * @param <V> The type of the value
     */
    public static <K, V> void logMapRemove(@NonNull final String label, @NonNull final K key, @Nullable final V value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug(
                    "      REMOVE from map {} key {} removed value {}",
                    label,
                    formatKey(key),
                    value == null ? "null" : value.toString());
        }
    }

    /**
     * Log the fetching of the size of a map.
     *
     * @param label The label of the map
     * @param size The size of the map
     */
    public static void logMapGetSize(@NonNull final String label, final long size) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug("      GET_SIZE on map {} size {}", label, size);
        }
    }

    /**
     * Log the get of an entry from a map.
     *
     * @param label The label of the map
     * @param key The key fetched to the map
     * @param value The value fetched to the map
     * @param <K> The type of the key
     * @param <V> The type of the value
     */
    public static <K, V> void logMapGet(@NonNull final String label, @NonNull final K key, @Nullable final V value) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            logger.debug(
                    "      GET on map {} key {} value {}",
                    label,
                    formatKey(key),
                    value == null ? "null" : value.toString());
        }
    }

    /**
     * Log the iteration of keys of a map.
     *
     * @param label The label of the map
     * @param keySet The set of keys of the map
     * @param <K> The type of the key
     */
    public static <K> void logMapIterate(@NonNull final String label, @NonNull final Set<InMemoryKey<K>> keySet) {
        if (logger.isDebugEnabled() && Thread.currentThread().getName().equals(TRANSACTION_HANDLING_THREAD_NAME)) {
            final long size = keySet.size();
            if (size == 0) {
                logger.debug("      ITERATE map {} size 0 keys:EMPTY", label);
            } else {
                logger.debug(
                        "      ITERATE map {} size {} keys:\n{}",
                        label,
                        size,
                        keySet.stream()
                                .map(InMemoryKey::key)
                                .map(StateLogger::formatKey)
                                .collect(Collectors.joining(",\n")));
            }
        }
    }

    /**
     * Log the iteration of values of a map.
     *
     * @param virtualMap The map that was iterated
     * @param <K> The type of the key
     */
    public static <K> void logMapIterate(
            @NonNull final String label, @NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec) {
        if (logger.isDebugEnabled()) {
            final var spliterator = Spliterators.spliterator(
                    virtualMap.treeIterator(), virtualMap.size(), Spliterator.SIZED & Spliterator.ORDERED);
            final long size = virtualMap.size();
            if (size == 0) {
                logger.debug("      ITERATE keys of {} state size 0 keys:EMPTY", label);
            } else {
                AtomicInteger count = new AtomicInteger(0);
                String keys = StreamSupport.stream(spliterator, false)
                        .map(merkleNode -> {
                            if (merkleNode instanceof VirtualLeafNode leaf) {
                                final var k = leaf.getKey();
                                String result = null;
                                try {
                                    result = keyCodec.parse(k).toString();
                                    count.incrementAndGet();
                                } catch (final ParseException e) {
                                    // ignore, return null
                                }
                                return result;
                            } else {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(",\n"));
                logger.debug("      ITERATE keys of {} state (size {}) keys:\n{}", label, count.get(), keys);
            }
        }
    }

    /**
     * Format entity id keys in form 0.0.123 rather than default toString() long form.
     *
     * @param key The key to format
     * @return The formatted key
     * @param <K> The type of the key
     */
    public static <K> String formatKey(@Nullable final K key) {
        if (key == null) {
            return "null";
        } else if (key instanceof AccountID accountID) {
            return accountID.shardNum() + "." + accountID.realmNum() + '.' + accountID.accountNum();
        } else if (key instanceof FileID fileID) {
            return fileID.shardNum() + "." + fileID.realmNum() + '.' + fileID.fileNum();
        } else if (key instanceof TokenID tokenID) {
            return tokenID.shardNum() + "." + tokenID.realmNum() + '.' + tokenID.tokenNum();
        } else if (key instanceof TopicID topicID) {
            return topicID.shardNum() + "." + topicID.realmNum() + '.' + topicID.topicNum();
        } else if (key instanceof ScheduleID scheduleID) {
            return scheduleID.shardNum() + "." + scheduleID.realmNum() + '.' + scheduleID.scheduleNum();
        }
        return key.toString();
    }
}
