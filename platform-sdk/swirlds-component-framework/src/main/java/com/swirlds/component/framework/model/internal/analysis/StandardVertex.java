// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.DIRECT_SCHEDULER_COLOR;
import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.GROUP_COLOR;
import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.SCHEDULER_COLOR;
import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.SUBSTITUTION_COLOR;
import static com.swirlds.component.framework.model.internal.analysis.WiringFlowchart.TEXT_COLOR;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A standard vertex in a wiring model. Does not contain sub-vertices.
 */
public class StandardVertex implements ModelVertex {

    /**
     * The name of the vertex.
     */
    private final String name;

    /**
     * When tasks are inserted into this vertex, is this component capable of applying back pressure?
     */
    private final boolean insertionIsBlocking;

    /**
     * The task scheduler type that corresponds to this vertex.
     */
    private final TaskSchedulerType type;

    /**
     * The meta-type of this vertex. Used by the wiring diagram, ignored by other use cases.
     */
    private final ModelVertexMetaType metaType;

    /**
     * The outgoing edges of this vertex.
     */
    private final Set<ModelEdge> outgoingEdges = new HashSet<>();

    /**
     * Used to track inputs that have been substituted during diagram generation.
     */
    private final Set<String> substitutedInputs = new HashSet<>();

    /**
     * The link to the documentation for this vertex. If null, no hyperlink will be generated.
     */
    private final String hyperlink;

    /**
     * Constructor.
     *
     * @param name                the name of the vertex
     * @param type                the type of task scheduler that corresponds to this vertex
     * @param metaType            the meta-type of this vertex, used to generate a wiring diagram
     * @param hyperlink           the link to the documentation for this vertex, ignored if null
     * @param insertionIsBlocking true if the insertion of this vertex may block until capacity is available
     */
    public StandardVertex(
            @NonNull final String name,
            @NonNull final TaskSchedulerType type,
            @NonNull final ModelVertexMetaType metaType,
            @Nullable final String hyperlink,
            final boolean insertionIsBlocking) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.metaType = Objects.requireNonNull(metaType);
        this.hyperlink = hyperlink;
        this.insertionIsBlocking = insertionIsBlocking;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TaskSchedulerType getType() {
        return type;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public String getHyperlink() {
        return hyperlink;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInsertionIsBlocking() {
        return insertionIsBlocking;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<ModelEdge> getOutgoingEdges() {
        return outgoingEdges;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<String> getSubstitutedInputs() {
        return substitutedInputs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable final Object obj) {
        if (obj instanceof final StandardVertex that) {
            return name.equals(that.name);
        }
        return false;
    }

    /**
     * Makes the vertex nicer to look at in a debugger.
     */
    @Override
    public String toString() {
        if (insertionIsBlocking) {
            return "[" + name + "]";
        } else {
            return "(" + name + ")";
        }
    }

    /**
     * Generate the style for this vertex.
     *
     * @return the style for this vertex
     */
    @NonNull
    private String generateStyle() {
        final String color =
                switch (metaType) {
                    case SUBSTITUTION -> SUBSTITUTION_COLOR;
                    case GROUP -> GROUP_COLOR;
                    case SCHEDULER -> switch (type) {
                        case DIRECT -> DIRECT_SCHEDULER_COLOR;
                        case DIRECT_THREADSAFE -> DIRECT_SCHEDULER_COLOR;
                        default -> SCHEDULER_COLOR;
                    };
                };

        final StringBuilder sb = new StringBuilder();
        sb.append("fill:#").append(color).append(",stroke:#").append(TEXT_COLOR).append(",stroke-width:2px");

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void render(
            @NonNull final StringBuilder sb,
            @NonNull final MermaidNameShortener nameProvider,
            @NonNull final MermaidStyleManager styleManager) {
        final String shortenedName = nameProvider.getShortVertexName(name);
        sb.append(shortenedName);

        switch (metaType) {
            case SUBSTITUTION -> sb.append("((");
            case GROUP -> sb.append("[");
            case SCHEDULER -> {
                switch (type) {
                    case CONCURRENT -> sb.append("[[");
                    case DIRECT -> sb.append("[/");
                    case DIRECT_THREADSAFE -> sb.append("{{");
                    default -> sb.append("[");
                }
            }
        }

        sb.append("\"");
        if (hyperlink != null) {
            sb.append("<a href='").append(hyperlink).append("' style='color: #EEEEEE; text-decoration:none'>");
        }
        sb.append(name);
        if (hyperlink != null) {
            sb.append("</a>");
        }

        if (!substitutedInputs.isEmpty()) {
            sb.append("<br />");
            substitutedInputs.stream().sorted().forEachOrdered(sb::append);
        }

        sb.append("\"");

        switch (metaType) {
            case SUBSTITUTION -> sb.append("))");
            case GROUP -> sb.append("]");
            case SCHEDULER -> {
                switch (type) {
                    case CONCURRENT -> sb.append("]]");
                    case DIRECT -> sb.append("/]");
                    case DIRECT_THREADSAFE -> sb.append("}}");
                    default -> sb.append("]");
                }
            }
        }

        sb.append("\n");

        styleManager.registerStyle(shortenedName, generateStyle());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDepth(final int depth) {
        // ignored
    }
}
