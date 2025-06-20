// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams.internal;

import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_CLASS_ID;

import java.util.Deque;
import java.util.LinkedList;

/**
 * An element on a serialization debug stack.
 */
public class SerializationStackElement {

    public static final int MAX_CHILD_COUNT = 10;
    public static final int SECOND_TO_LAST_MAX_CHILD_COUNT = 5;
    private static final int MAX_STRING_REPRESENTATION_LENGTH = 30;

    private static final String INDENT = "   ";

    private final SerializationOperation operation;
    private final StackTraceElement callLocation;
    private Long classId;
    private Class<?> clazz;
    private String stringRepresentation;

    private final Deque<SerializationStackElement> children = new LinkedList<>();
    private SerializationStackElement secondToLastChild;
    private long currentChildCount;
    private long totalChildCount;

    /**
     * Create a new serialization element for the debug stack.
     *
     * @param operation
     * 		the operation being performed
     */
    public SerializationStackElement(final SerializationOperation operation, final StackTraceElement callLocation) {
        this.operation = operation;
        this.callLocation = callLocation;
    }

    /**
     * Get the operation for this element.
     */
    public SerializationOperation getOperation() {
        return operation;
    }

    /**
     * Add a child operation.
     *
     * @param child
     * 		the child operation
     */
    public void addChild(final SerializationStackElement child) {

        // Only the last two children are permitted to have children of their own.
        final SerializationStackElement newThirdToLastChild = secondToLastChild;
        if (newThirdToLastChild != null) {
            newThirdToLastChild.children.clear();
            newThirdToLastChild.currentChildCount = 0;
        }

        // The second to last child is permitted to have a limited number of children of its own.
        if (currentChildCount > 0) {
            final SerializationStackElement newSecondToLastChild = children.getLast();
            newSecondToLastChild.reduceChildCount(SECOND_TO_LAST_MAX_CHILD_COUNT);

            // Only the last child of the second to last child is permitted to have children.
            if (newSecondToLastChild.secondToLastChild != null) {
                newSecondToLastChild.secondToLastChild.children.clear();
                newSecondToLastChild.secondToLastChild.currentChildCount = 0;
            }

            secondToLastChild = newSecondToLastChild;
        }

        currentChildCount++;
        totalChildCount++;

        children.addLast(child);
        reduceChildCount(MAX_CHILD_COUNT);
    }

    /**
     * Set the class ID of the element if it becomes known
     *
     * @param classId
     * 		the class ID of the element
     */
    public void setClassId(final long classId) {
        this.classId = classId;
    }

    /**
     * Set the class of the element if it becomes known
     *
     * @param clazz
     * 		the class of the element
     */
    public void setClass(final Class<?> clazz) {
        this.clazz = clazz;
    }

    /**
     * Set a string that represents the value. Should only be set with very short strings.
     *
     * @param stringRepresentation
     * 		the string representation of the value
     */
    public void setStringRepresentation(final String stringRepresentation) {
        if (stringRepresentation.length() > MAX_STRING_REPRESENTATION_LENGTH) {
            this.stringRepresentation = stringRepresentation.substring(0, MAX_STRING_REPRESENTATION_LENGTH) + "...";
        } else {
            this.stringRepresentation = stringRepresentation;
        }
    }

    /**
     * Reduce the child count by dropping children. Earlier children are dropped before later children.
     */
    private void reduceChildCount(final int maxChildCount) {
        while (currentChildCount > maxChildCount) {
            children.removeFirst();
            currentChildCount--;
        }
    }

    /**
     * Write this element to a deserialization stack trace.
     *
     * @param sb
     * 		a string builder where the stack trace is being written
     * @param indent
     * 		the indentation level of this element
     */
    public void writeStackTrace(final StringBuilder sb, final int indent) {
        sb.append(INDENT.repeat(indent));
        sb.append(operation.name());

        if (stringRepresentation != null) {
            sb.append(" [value=").append(stringRepresentation).append("]");
        }

        if (clazz != null) {
            sb.append(" ").append(clazz.getSimpleName());
        } else if (classId != null) {
            if (classId == NULL_CLASS_ID) {
                sb.append(" NULL");
            } else {
                sb.append(String.format(" class ID = %d(0x%08X)", classId, classId));
            }
        }

        sb.append(" @ ").append(callLocation.getFileName()).append(":").append(callLocation.getLineNumber());
        sb.append("\n");

        final int childIndent = indent + 1;

        final long undisplayed = totalChildCount - currentChildCount;
        if (undisplayed > 0) {
            sb.append(INDENT.repeat(childIndent));
            sb.append("(").append(undisplayed).append(" hidden)\n");
        }

        for (final SerializationStackElement child : children) {
            child.writeStackTrace(sb, childIndent);
        }
    }

    /**
     * Get the location where this operation was invoked.
     */
    public StackTraceElement getCallLocation() {
        return callLocation;
    }

    /**
     * Get the class ID of this object, if it is known. Some elements (e.g. primitives) will not have a class ID.
     */
    public Long getClassId() {
        return classId;
    }

    /**
     * Get the class of this object, if it is knwon. Some elements (e.g. primitives) will not have a class set.
     */
    public Class<?> getClazz() {
        return clazz;
    }

    /**
     * Get the string representation of this object. Not all objects will have a string representation.
     */
    public String getStringRepresentation() {
        return stringRepresentation;
    }

    /**
     * Get the children belonging to this object, if it has any.
     */
    public Deque<SerializationStackElement> getChildren() {
        return children;
    }

    /**
     * Get the current (un-purged) child count of this object.
     */
    public long getCurrentChildCount() {
        return currentChildCount;
    }

    /**
     * Get the total child count of this object, including purged children.
     */
    public long getTotalChildCount() {
        return totalChildCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        writeStackTrace(sb, 0);
        return sb.toString();
    }
}
