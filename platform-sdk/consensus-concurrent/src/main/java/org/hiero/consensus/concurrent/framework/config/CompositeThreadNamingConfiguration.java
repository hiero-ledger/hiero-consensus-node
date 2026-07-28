// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import java.util.LinkedList;
import java.util.List;

/**
 * Thread naming scheme made out of component: name part + optional thread number.
 * Please see {@link #generateNextThreadName()} for the details.
 */
public class CompositeThreadNamingConfiguration extends AbstractThreadNamingConfiguration {

    /**
     * The name of the component with which this thread is associated.
     */
    protected String component;

    /**
     * A name for this thread.
     */
    protected String threadName;

    public CompositeThreadNamingConfiguration() {}

    /**
     * Set the name of the component that new threads will be associated with.
     *
     * @return this object
     */
    public CompositeThreadNamingConfiguration setComponent(final String component) {

        this.component = component;
        return this;
    }

    /**
     * Set the name for created threads.
     *
     * @return this object
     */
    public CompositeThreadNamingConfiguration setThreadName(final String threadName) {

        this.threadName = threadName;
        return this;
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
    @Override
    public String generateNextThreadName() {
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
}
