// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.ThresholdLimitingHandler;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * This class manages the learner's work task for synchronization.
 */
public class LearnerPushTask {

    private static final Logger logger = LogManager.getLogger(LearnerPushTask.class);

    private static final String NAME = "learner-task";

    private final StandardWorkGroup workGroup;
    private final AsyncInputStream<Lesson> in;
    private final AsyncOutputStream<QueryResponse> out;
    private final LearnerTreeView view;
    private final ReconnectNodeCount nodeCount;

    private final ReconnectMapStats mapStats;

    private final ThresholdLimitingHandler<Throwable> exceptionRateLimiter = new ThresholdLimitingHandler<>(1);

    /**
     * Create a new thread for the learner.
     *
     * @param workGroup
     * 		the work group that will manage the thread
     * @param in
     * 		the input stream, this object is responsible for closing the stream when finished
     * @param out
     * 		the output stream, this object is responsible for closing the stream when finished
     * @param view
     * 		a view used to interface with the subtree
     * @param nodeCount
     * 		an object used to keep track of the number of nodes sent during the reconnect
     * @param mapStats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPushTask(
            final StandardWorkGroup workGroup,
            final AsyncInputStream<Lesson> in,
            final AsyncOutputStream<QueryResponse> out,
            final LearnerTreeView view,
            final ReconnectNodeCount nodeCount,
            @NonNull final ReconnectMapStats mapStats) {
        this.workGroup = workGroup;
        this.in = in;
        this.out = out;
        this.view = view;
        this.nodeCount = nodeCount;
        this.mapStats = mapStats;
    }

    public void start() {
        workGroup.execute(NAME, this::run);
    }

    /**
     * Based on the data in a lesson, get the node that should be inserted into the tree.
     */
    private Long extractNodeFromLesson(final ExpectedLesson expectedLesson, final Lesson lesson, boolean firstLesson) {

        if (lesson.isCurrentNodeUpToDate()) {
            // We already have the correct node in our tree.
            return expectedLesson.getOriginalNode();
        } else {
            final Long node;

            if (firstLesson) {
                // Special case: roots of subtrees with custom views will have been copied
                // when synchronizing the parent tree.
                node = expectedLesson.getOriginalNode();
            } else {
                // The teacher sent us the node we should use
                node = lesson.getNode();
            }

            return node;
        }
    }

    /**
     * Handle queries associated with a lesson.
     */
    private void handleQueries(
            final LearnerTreeView view,
            final AsyncInputStream<Lesson> in,
            final AsyncOutputStream<QueryResponse> out,
            final List<Hash> queries,
            final Long originalParent,
            final Long newParent)
            throws InterruptedException {

        final int childCount = queries.size();
        for (int childIndex = 0; childIndex < childCount; childIndex++) {

            final Long originalChild;
            if (view.isInternal(originalParent, true) && view.getNumberOfChildren(originalParent) > childIndex) {
                originalChild = view.getChild(originalParent, childIndex);
            } else {
                originalChild = null;
            }

            final Hash originalHash = view.getNodeHash(originalChild);

            final Hash teacherHash = queries.get(childIndex);
            if (originalHash == null) {
                exceptionRateLimiter.handle(
                        new NullPointerException(),
                        (error) ->
                                logger.warn(RECONNECT.getMarker(), "originalHash for node {} is null", originalChild));
            }
            final boolean nodeAlreadyPresent = originalHash != null && originalHash.equals(teacherHash);
            out.sendAsync(new QueryResponse(nodeAlreadyPresent));
            mapStats.incrementTransfersFromLearner();
            view.recordHashStats(mapStats, newParent, childIndex, nodeAlreadyPresent);

            view.expectLessonFor(newParent, childIndex, originalChild, nodeAlreadyPresent);
            in.anticipateMessage();
        }
    }

    /**
     * Update node counts for statistics.
     */
    private void addToNodeCount(final ExpectedLesson expectedLesson, final Lesson lesson, final Long newChild) {
        if (lesson.isLeafLesson()) {
            mapStats.incrementLeafData(1, expectedLesson.isNodeAlreadyPresent() ? 1 : 0);
        }

        if (lesson.isCurrentNodeUpToDate()) {
            return;
        }

        if (view.isInternal(newChild, false)) {
            nodeCount.incrementInternalCount();
            if (expectedLesson.isNodeAlreadyPresent()) {
                nodeCount.incrementRedundantInternalCount();
            }
        } else {
            nodeCount.incrementLeafCount();
            if (expectedLesson.isNodeAlreadyPresent()) {
                nodeCount.incrementRedundantLeafCount();
            }
        }
    }

    /**
     * Get the tree/subtree from the teacher.
     */
    private void run() {
        boolean firstLesson = true;

        try (in;
                out;
                view) {

            view.expectLessonFor(null, 0, 0L, false);
            in.anticipateMessage();

            while (view.hasNextExpectedLesson()) {

                final ExpectedLesson expectedLesson = view.getNextExpectedLesson();
                final Lesson lesson = in.readAnticipatedMessage();
                mapStats.incrementTransfersFromTeacher();

                final Long parent = expectedLesson.getParent();

                final Long newChild = extractNodeFromLesson(expectedLesson, lesson, firstLesson);

                firstLesson = false;

                if (parent != null) {
                    view.setChild(parent, expectedLesson.getPositionInParent(), newChild);
                }

                addToNodeCount(expectedLesson, lesson, newChild);

                if (lesson.hasQueries()) {
                    final List<Hash> queries = lesson.getQueries();
                    handleQueries(view, in, out, queries, expectedLesson.getOriginalNode(), newChild);
                }
            }

            logger.info(RECONNECT.getMarker(), "learner thread finished the learning loop for the current subtree");
        } catch (final InterruptedException ex) {
            logger.warn(RECONNECT.getMarker(), "learner thread interrupted");
            Thread.currentThread().interrupt();
        } catch (final Exception ex) {
            logger.error(EXCEPTION.getMarker(), "exception in the learner's receiving thread", ex);
            throw new MerkleSynchronizationException("exception in the learner's receiving thread", ex);
        }

        logger.info(RECONNECT.getMarker(), "learner thread closed input, output, and view for the current subtree");
    }
}
