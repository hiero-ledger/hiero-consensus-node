// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the teacher.
 *
 * @param <T> the type of a message sent to the learner
 */
public interface TeacherTreeView<T> extends AutoCloseable {

    /**
     * Returns the parser function used to deserialize messages received from the learner.
     * The returned parser is passed to the {@link AsyncInputStream} at construction time.
     *
     * @return the input message parser
     */
    @NonNull
    Function<ReadableSequentialData, T> getInputParser();

    /**
     * For this tree view, start all required reconnect tasks in the given work group. Teaching synchronizer
     * will then wait for all tasks in the work group to complete before proceeding to the next tree view. If
     * new custom tree views are encountered, they must be added to {@code subtrees}, although it isn't
     * currently supported by virtual tree views, as nested virtual maps are not supported.
     *
     * @param workGroup the work group to run teaching task(s) in
     * @param in the input stream to read data from learner
     * @param out the output stream to write data to learner
     */
    void startTeacherTasks(
            final Time time,
            final StandardWorkGroup workGroup,
            final AsyncInputStream<T> in,
            final AsyncOutputStream out);
}
