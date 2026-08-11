// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Thread naming scheme made out of component: name part + optional thread number. Please see
 * {@link #generateNextThreadName()} for the details.
 */
public class CompositeThreadNameProvider {

    /**
     * The name of the component with which this thread is associated.
     */
    protected String component;

    /**
     * A name for this thread.
     */
    protected String threadName;

    /**
     * If true then use thread numbers when generating the thread name.
     */
    protected boolean useThreadNumbers;
    /**
     * If thread numbers are enabled, this contains the next thread number that should be used.
     */
    protected final AtomicInteger nextThreadNumber = new AtomicInteger(0);

    protected CompositeThreadNameProvider() {}

    protected CompositeThreadNameProvider(final String component, final String threadName) {
        this.component = component;
        this.threadName = threadName;
    }

    /**
     * Create a default thread name out of component and threadName. Please see {@link #generateNextThreadName()} for
     * the details.
     *
     * @param component  component of the system
     * @param threadName specific thread name part
     * @return full thread name
     */
    public static String create(final String component, final String threadName) {
        return new CompositeThreadNameProvider(component, threadName).generateNextThreadName();
    }

    /**
     * Create a default thread name supplier out of component and threadName, which extends the name with a next thread
     * number each time it is called. Please see {@link #generateNextThreadName()} for the details.
     *
     * @param component  component of the system
     * @param threadName specific thread name part
     * @return full thread name
     */
    public static Supplier<String> createNumbered(final String component, final String threadName) {
        return new CompositeThreadNameProvider(component, threadName).supplier();
    }

    /**
     * <p>
     * Construct a thread name. Format is as follows:
     * </p>
     *
     * <pre>
     *  &lt;COMPONENT: NAME  #THREAD_NUM&gt;
     *   |________| |____|  |_________|
     *       |       |           |
     *       |   "unnamed"       |
     *       |    if unset       |
     *       |                   |
     * omitted if unset    omitted if unset
     *
     * </pre>
     *
     */
    protected String generateNextThreadName() {
        // The parts are joined together with a space in-between each.
        final List<String> parts = new LinkedList<>();

        final boolean hasComponent = component != null && !component.isBlank();
        final boolean hasName = threadName != null && !threadName.isBlank();

        if (hasComponent) {
            parts.add(component + ":");
        }

        if (hasName) {
            parts.add(threadName);
        } else {
            parts.add("unnamed");
        }

        if (useThreadNumbers) {
            parts.add("#" + nextThreadNumber.getAndIncrement());
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("<");
        for (int index = 0; index < parts.size(); index++) {
            sb.append(parts.get(index));
            if (index + 1 < parts.size()) {
                sb.append(" ");
            }
        }
        sb.append(">");

        return sb.toString();
    }

    /**
     * Switch provider into multi-thread mode, so it will start appending thread numbers
     *
     * @return Version of thread name provider prepared to generate names for multiple threads
     */
    public Supplier<String> supplier() {
        useThreadNumbers = true;
        return this::generateNextThreadName;
    }
}
