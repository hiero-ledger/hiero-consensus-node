// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility;

/**
 * This class is a convenience wrapper around for the java stack trace.
 */
public record StackTrace(StackTraceElement[] frames) {

    /**
     * Convenience method for getting a stack trace of the current thread.
     *
     * @return a stack grace
     */
    public static StackTrace getStackTrace() {
        return new StackTrace(ignoreFrames(Thread.currentThread().getStackTrace(), 2));
    }

    /**
     * Convenience method for getting the stack trace of a thread.
     *
     * @param thread
     * 		the target thread
     * @return a stack trace
     */
    public static StackTrace getStackTrace(final Thread thread) {
        return new StackTrace(thread.getStackTrace());
    }

    /**
     * Extract a stack trace from a throwable
     *
     * @param t
     * 		a throwable
     * @return a stack trace
     */
    public static StackTrace getStackTrace(final Throwable t) {
        return new StackTrace(t.getStackTrace());
    }

    /**
     * Return a list of stack trace elements, less a few ignored frames at the beginning.
     *
     * @param frames
     * 		an array of frames
     * @param ignoredFrames
     * 		the number of frames to remove from the beginning
     * @return a new array that does not contain the ignored frames
     */
    private static StackTraceElement[] ignoreFrames(final StackTraceElement[] frames, final int ignoredFrames) {
        if (frames.length <= ignoredFrames) {
            return new StackTraceElement[0];
        }
        final StackTraceElement[] reducedFrames = new StackTraceElement[frames.length - ignoredFrames];
        System.arraycopy(frames, ignoredFrames, reducedFrames, 0, reducedFrames.length);
        return reducedFrames;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (final StackTraceElement element : frames) {
            if (element != frames[0]) {
                sb.append("\tat ");
            }
            sb.append(element.getClassName())
                    .append(".")
                    .append(element.getMethodName())
                    .append("(")
                    .append(element.getFileName())
                    .append(":")
                    .append(element.getLineNumber())
                    .append(")")
                    .append("\n");
        }
        return sb.toString();
    }
}
