// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.THREADS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hiero.base.utility.CompareTo.isGreaterThan;
import static org.hiero.consensus.model.utility.StackTrace.getStackTrace;
import static org.hiero.consensus.model.utility.interrupt.Uninterruptable.retryIfInterrupted;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.ThreadSeed;
import com.swirlds.common.threading.framework.TypedStoppableThread;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.DurationUtils;
import org.hiero.consensus.model.utility.StackTrace;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;

/**
 * Implements the concept of a thread that can be gracefully stopped. Once stopped this instance can no longer be used
 * and must be recreated.
 *
 * @param <T> the type of instance that will do work
 */
public class StoppableThreadImpl<T extends InterruptableRunnable> implements TypedStoppableThread<T> {

    private static final Logger logger = LogManager.getLogger(StoppableThreadImpl.class);
    /** the minimum await time when waiting for a thread to pause */
    private static final Duration MINIMUM_PAUSE_AWAIT = Duration.ofMillis(1);

    /**
     * The current status of this thread.
     */
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);

    /**
     * Determines the default behavior of the thread when closed
     */
    private final Stoppable.StopBehavior stopBehavior;

    /**
     * The amount of time in milliseconds to wait after setting the thread status to {@link Status#DYING} before
     * interrupting the thread if {@link #stopBehavior} is
     * {@link com.swirlds.common.threading.framework.Stoppable.StopBehavior#INTERRUPTABLE INTERRUPTABLE}.
     */
    private final int joinWaitMs;

    /**
     * If the {@link #pause()} operation takes longer than this, log a stack trace to help us understand where the
     * thread is stuck
     */
    private final Duration logStackTracePauseDuration;

    /**
     * Used to wait until the work thread has started its pause.
     */
    private final AtomicReference<CountDownLatch> pauseStartedLatch = new AtomicReference<>(new CountDownLatch(1));

    /**
     * Used by the work thread to wait until a pause has completed.
     */
    private final AtomicReference<CountDownLatch> pauseCompletedLatch = new AtomicReference<>(new CountDownLatch(1));

    /**
     * The minimum amount of time that a cycle is permitted to take. If a cycle completes in less time, then sleep until
     * the minimum period has been met.
     */
    private final Duration minimumPeriod;

    /**
     * Used to enforce the minimum period.
     */
    private Instant previousCycleStart;

    /**
     * The work to perform on the thread. Called over and over.
     */
    private final T work;

    /**
     * The work that is done after the thread is closed. Ignored if this thread is interruptable.
     */
    private final InterruptableRunnable finalCycleWork;

    /**
     * The thread on which to do work.
     */
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /**
     * True if this thread was injected.
     */
    private volatile boolean injected;

    /**
     * Used by join, necessary in case join is called before the thread is started.
     */
    private final CountDownLatch started = new CountDownLatch(1);

    /**
     * This latch is when joining a thread that was injected as a seed. When a seed is injected, the thread may live
     * after the stoppable thread finishes, and so join shouldn't wait for the thread to actually die. Ignored if this
     * thread was not injected.
     */
    private final CountDownLatch finished = new CountDownLatch(1);

    /**
     * If a thread is requested to stop but does not it is considered to be hanging after this period. If 0 then thread
     * is never considered to be hanging.
     */
    private final Duration hangingThreadDuration;

    /**
     * True if the thread is in a hanging state.
     */
    private volatile boolean hanging;

    /**
     * The configuration used to create this stoppable thread.
     */
    private final AbstractStoppableThreadConfiguration<?, T> configuration;

    /**
     * Create a new stoppable thread.
     */
    public StoppableThreadImpl(final AbstractStoppableThreadConfiguration<?, T> configuration) {

        this.configuration = configuration;

        stopBehavior = configuration.getStopBehavior();
        joinWaitMs = configuration.getJoinWaitMs();
        hangingThreadDuration = configuration.getHangingThreadPeriod();

        work = configuration.getWork();
        finalCycleWork = configuration.getFinalCycleWork();

        minimumPeriod = configuration.getMinimumPeriod();

        logStackTracePauseDuration = configuration.getLogAfterPauseDuration();

        configuration.setRunnable(this::run);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ThreadSeed buildSeed() {
        if (injected) {
            throw new IllegalStateException("this StoppableThread has already built a seed");
        }
        if (thread.get() != null) {
            throw new IllegalStateException("can not build seed after thread is started");
        }

        injected = true;

        return configuration.buildStoppableThreadSeed(this);
    }

    /**
     * Mark this stoppable thread as injected.
     */
    protected void setInjected() {
        this.injected = true;
    }

    /**
     * Check if this stoppable thread has been started or injected.
     *
     * @return true if started or injected
     */
    protected boolean hasBeenStartedOrInjected() {
        return injected || (thread.get() != null);
    }

    /**
     * The method to execute on the {@link #thread} object.
     */
    private void run() {
        final Thread currentThread = Thread.currentThread();
        try {
            for (Status currentStatus = status.get();
                    currentStatus != Status.DYING && !currentThread.isInterrupted();
                    currentStatus = status.get()) {

                if (currentStatus == Status.PAUSED) {
                    waitUntilUnpaused();
                } else {
                    enforceMinimumPeriod();
                    doWork();
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            status.set(Status.DEAD);
            finished.countDown();
        }
    }

    /**
     * If a minimum period is configured then enforce it.
     */
    private void enforceMinimumPeriod() throws InterruptedException {
        if (minimumPeriod == null) {
            return;
        }

        final Instant now = Instant.now();

        if (previousCycleStart == null) {
            previousCycleStart = now;
            return;
        }

        final Duration previousDuration = Duration.between(previousCycleStart, now);
        final Duration remainingCycleTime = minimumPeriod.minus(previousDuration);

        if (isGreaterThan(remainingCycleTime, Duration.ZERO)) {
            NANOSECONDS.sleep(remainingCycleTime.toNanos());
            previousCycleStart = now.plus(remainingCycleTime);
        } else {
            previousCycleStart = now;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start() {
        if (injected) {
            throw new IllegalStateException("Thread can not be started if it has built a seed");
        }

        final Status originalStatus = status.get();
        if (originalStatus != Status.NOT_STARTED) {
            throw new IllegalStateException(
                    "can not start thread " + getName() + " when it is in the state " + originalStatus.name());
        }

        final Thread t = configuration.buildThread(false);
        markAsStarted(t);
        t.start();
    }

    /**
     * Get the current thread. Blocks until the thread has been started. This method is not interruptable, so calling
     * this method before the thread is started is a big commitment.
     *
     * @return the thread
     */
    private Thread uninterruptableGetThread() {
        Thread t = thread.get();
        while (t == null) {
            retryIfInterrupted(() -> MILLISECONDS.sleep(1));
            t = thread.get();
        }

        return t;
    }

    /**
     * Get the current thread. Blocks until the thread has been started. This method is not interruptable, so calling
     * this method before the thread is started is a big commitment.
     *
     * @return the thread
     */
    private Thread getThread() throws InterruptedException {
        Thread t = thread.get();
        while (t == null) {
            MILLISECONDS.sleep(1);
            t = thread.get();
        }

        return t;
    }

    /**
     * Called when a pause has been requested. Waits until the pause has been lifted.
     */
    private void waitUntilUnpaused() throws InterruptedException {
        final CountDownLatch pauseCompleted = pauseCompletedLatch.get();

        // Alert pausing thread that the pause has started.
        pauseStartedLatch.get().countDown();

        // Wait until the pause has completed.
        pauseCompleted.await();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean pause() {

        final Status originalStatus = status.get();
        if (originalStatus != Status.ALIVE) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "can not pause thread {} when it is in the state {}",
                    this::getName,
                    originalStatus::name);
            return false;
        }

        final Thread t = thread.get();

        status.set(Status.PAUSED);

        // Wait until the target thread is paused or interrupted.
        retryIfInterrupted(() -> {
            // Spin until loop conditions allow exit. Conditions will block, preventing pure busy wait.
            while (!t.isInterrupted() && !waitForThreadToPause()) {
                if (pauseLogStackTrace()) {
                    // logStackTracePauseDuration has been exceeded, log a stack trace
                    logger.error(
                            EXCEPTION.getMarker(),
                            "pausing thread {} is taking longer than {}",
                            this::getName,
                            logStackTracePauseDuration::toString);
                    logger.error(EXCEPTION.getMarker(), "stack trace of {}:\n{}", this::getName, () -> new StackTrace(
                                    t.getStackTrace())
                            .toString());
                }
            }
        });

        return true;
    }

    /**
     * Blocks waiting for a thread to pause
     *
     * @return true if the thread has paused
     * @throws InterruptedException if this thread is interrupted waiting for the pause started latch
     */
    private boolean waitForThreadToPause() throws InterruptedException {
        return pauseStartedLatch
                .get()
                .await(
                        DurationUtils.max(logStackTracePauseDuration, MINIMUM_PAUSE_AWAIT)
                                .toMillis(),
                        MILLISECONDS);
    }

    /**
     * @return true if we should log a stack trace when the wait time for a pause is exceeded
     */
    private boolean pauseLogStackTrace() {
        return DurationUtils.isLonger(logStackTracePauseDuration, Duration.ZERO);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean resume() {

        final Status originalStatus = status.get();
        if (originalStatus != Status.PAUSED) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "can not resume thread {} when it is in the state {}",
                    this::getName,
                    originalStatus::name);
            return false;
        }

        status.set(Status.ALIVE);
        unblockPausedThread();

        return true;
    }

    /**
     * Calling this method unblocks the thread if it is in a paused state.
     */
    private void unblockPausedThread() {
        pauseCompletedLatch.get().countDown();
        pauseStartedLatch.set(new CountDownLatch(1));
        pauseCompletedLatch.set(new CountDownLatch(1));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void join() throws InterruptedException {
        while (status.get() == Status.NOT_STARTED) {
            MILLISECONDS.sleep(1);
        }

        if (injected) {
            // This stoppable thread was injected into a pre-existing thread. Wait for the stoppable thread to finish,
            // but don't worry about the underlying thread being stopped.
            finished.await();
        } else {
            // This stoppable thread was run on top of its own thread. Wait for that thread to be closed.
            thread.get().join();
        }
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void join(final long millis) throws InterruptedException {
        while (status.get() == Status.NOT_STARTED) {
            MILLISECONDS.sleep(1);
        }

        if (injected) {
            // This stoppable thread was injected into a pre-existing thread. Wait for the stoppable thread to finish,
            // but don't worry about the underlying thread being stopped.
            finished.await(millis, TimeUnit.MILLISECONDS);
        } else {
            // This stoppable thread was run on top of its own thread. Wait for that thread to be closed.
            getThread().join(millis);
        }
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void join(final long millis, final int nanos) throws InterruptedException {
        while (status.get() == Status.NOT_STARTED) {
            MILLISECONDS.sleep(1);
        }

        if (injected) {
            // This stoppable thread was injected into a pre-existing thread. Wait for the stoppable thread to finish,
            // but don't worry about the underlying thread being stopped.
            finished.await((long) (millis + nanos * NANOSECONDS_TO_MILLISECONDS), TimeUnit.MILLISECONDS);
        } else {
            // This stoppable thread was run on top of its own thread. Wait for that thread to be closed.
            getThread().join(millis, nanos);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean stop() {
        // Use the default stop behavior
        return stop(stopBehavior);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean stop(final StopBehavior behavior) {
        final Status originalStatus = status.get();

        if (originalStatus != Status.ALIVE && originalStatus != Status.PAUSED) {
            final Thread t = thread.get();
            final String name = t == null ? "null" : t.getName();
            final String message = "can not stop thread {} when it is in the state {}";

            if (originalStatus == Status.DEAD) {
                // Closing a thread after it dies is probably not the root cause of any errors (if there is an error)
                logger.warn(THREADS.getMarker(), message, () -> name, originalStatus::name);
            } else {
                // Closing a thread that is NOT_STARTED or DYING is probably indicative of an error
                logger.error(EXCEPTION.getMarker(), message, () -> name, originalStatus::name);
            }

            return false;
        }

        status.set(Status.DYING);

        if (originalStatus == Status.PAUSED) {
            unblockPausedThread();
        }

        try {
            close(behavior);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean interrupt() {
        final Thread t = thread.get();
        if (t == null) {
            return false;
        }

        t.interrupt();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlive() {
        return status.get() != Status.DEAD;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Status getStatus() {
        return status.get();
    }

    /**
     * Indicate that the thread has started.
     *
     * @param thread the thread that is being used
     */
    protected void markAsStarted(final Thread thread) {
        this.thread.set(thread);
        status.set(Status.ALIVE);
        started.countDown();
    }

    /**
     * Closes the thread with a certain behavior
     *
     * @param stopBehavior the type of behavior to use when closing the thread
     */
    private void close(final StopBehavior stopBehavior) throws InterruptedException {
        if (stopBehavior == StopBehavior.BLOCKING) {
            blockingClose();
        } else if (stopBehavior == StopBehavior.INTERRUPTABLE) {
            interruptClose();
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * An implementation of close that will interrupt the work thread if it doesn't terminate quickly enough.
     */
    private void interruptClose() throws InterruptedException {
        join(joinWaitMs);
        if (isAlive()) {
            interrupt();
            joinInternal();
        }
        doFinalCycleWork();
    }

    /**
     * An implementation of close that will block until the work thread terminates.
     */
    private void blockingClose() throws InterruptedException {
        joinInternal();
        doFinalCycleWork();
    }

    /**
     * Perform a join with hanging thread detection (if configured).
     */
    private void joinInternal() throws InterruptedException {
        if (!hangingThreadDuration.isZero()) {
            join(hangingThreadDuration.toMillis());
            if (isAlive()) {
                logHangingThread();
                join();
            }
        } else {
            join();
        }
    }

    /**
     * Attempt to do some work.
     *
     * @throws InterruptedException if the thread running the work is interrupted
     */
    private void doWork() throws InterruptedException {
        work.run();
    }

    /**
     * Perform the last cycle of work. Only called if {@link #stopBehavior} is
     * {@link com.swirlds.common.threading.framework.Stoppable.StopBehavior#BLOCKING BLOCKING}.
     *
     * @throws InterruptedException if the thread running the work is interrupted
     */
    private void doFinalCycleWork() throws InterruptedException {
        if (finalCycleWork != null) {
            finalCycleWork.run();
        }
    }

    /**
     * Write a log message if this thread becomes a hanging thread.
     */
    private void logHangingThread() {
        hanging = true;

        StringBuilder sb = new StringBuilder();
        sb.append("hanging thread detected: ")
                .append(getName())
                .append(" was requested to stop but is still alive after ")
                .append(hangingThreadDuration)
                .append("ms. Stop behavior = ")
                .append(stopBehavior.toString());
        logger.error(EXCEPTION.getMarker(), sb);

        sb = new StringBuilder("stack trace for hanging thread ")
                .append(getName())
                .append(":\n")
                .append(getStackTrace(uninterruptableGetThread()));

        logger.error(EXCEPTION.getMarker(), sb);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHanging() {
        return uninterruptableGetThread().isAlive() && hanging;
    }

    /**
     * Get the name of this thread.
     */
    public String getName() {
        return configuration.getThreadName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T getWork() {
        return work;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this).append(getName()).toString();
    }
}
