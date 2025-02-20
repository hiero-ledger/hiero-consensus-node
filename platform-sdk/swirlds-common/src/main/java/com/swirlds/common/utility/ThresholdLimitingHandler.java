// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Provides a simple reusable thresholding mechanism to effectively apply rate limiting on arbitrary events. This
 * implementation groups the events by {@code Strings} that are extracted from the supplied elements.
 *
 * @param <E>
 * 		the type of element to be rate limited
 */
public class ThresholdLimitingHandler<E> {

    /** the threshold at which to begin suppression */
    private final long threshold;

    /** the function that extract the key from a given element */
    private final Function<E, String> keyExtractor;
    /** the state of each threshold counter for each element type */
    private final Map<String, Long> state;

    /**
     * Constructor that initializes the underlying threshold.
     *
     * The {@code keyExtractor} groups the events by the underlying {@link Class} of the supplied elements.
     *
     * @param threshold
     * 		the threshold at which events are discarded
     */
    public ThresholdLimitingHandler(final long threshold) {
        this(threshold, ThresholdLimitingHandler::resolveElementClass);
    }

    /**
     * Constructor that initializes the underlying threshold.
     *
     * @param threshold
     * 		the threshold at which events are discarded
     * @param keyExtractor
     * 		function that extracts the key
     */
    public ThresholdLimitingHandler(final long threshold, final Function<E, String> keyExtractor) {
        this.threshold = threshold;
        this.keyExtractor = keyExtractor;
        this.state = new ConcurrentHashMap<>();
    }

    /**
     * Applies thresholding to a given {@code element} based on it's {@link Class} and invokes the {@code callback}
     * method if the number of times we have seen this element is less than or equal to the threshold limit.
     *
     * @param element
     * 		the item for which thresholding should be applied
     * @param callback
     * 		the {@link Consumer} to notify if the supplied element is within the threshold limits
     * @throws IllegalArgumentException
     * 		if the {@code element} parameter is {@code null}
     */
    public void handle(final E element, final Consumer<E> callback) {
        if (element == null) {
            throw new IllegalArgumentException("The element argument may not be a null value");
        }

        final String key = keyExtractor.apply(element);

        final long counter = state.compute(key, (k, oldValue) -> {
            if (oldValue == null) {
                return 1L;
            }

            return oldValue + 1;
        });

        if (counter <= threshold && callback != null) {
            callback.accept(element);
        }
    }

    /**
     * Returns the current counter for the given element. Uses the {@link Class} of the element to locate the counter.
     *
     * @param element
     * 		the element for which to retrieve the counter
     * @return the number of times we have seen elements with the same {@link Class} as the one provided
     * @throws IllegalArgumentException
     * 		if the {@code element} parameter is {@code null}
     */
    public long getCurrentThreshold(final E element) {
        if (element == null) {
            throw new IllegalArgumentException("The element argument may not be a null value");
        }

        final String key = keyExtractor.apply(element);

        return state.getOrDefault(key, 0L);
    }

    /**
     * Clears all counters.
     */
    public void reset() {
        state.clear();
    }

    /**
     * Clears the counter for the provided element. Uses the {@link Class} of the element to locate the counter.
     *
     * @param element
     * 		the element for which the counter should be cleared
     */
    public void reset(final E element) {
        final String key = keyExtractor.apply(element);

        state.remove(key);
    }

    private static <E> String resolveElementClass(final E element) {
        @SuppressWarnings("unchecked")
        final Class<E> elClass = (element != null) ? (Class<E>) element.getClass() : null;

        return "" + elClass;
    }
}
