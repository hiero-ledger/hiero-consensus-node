// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/// Progressive backoff for threads that poll a non-blocking queue, such as the queues in
/// `org.hiero.base.concurrent.jctools.queues`, while it's full or empty: [#SPIN_LIMIT] spin hints first,
/// then parks of growing length, capped by the caller.
///
/// A wait goes through a schedule of steps: steps below [#SPIN_LIMIT] spin, each later step parks twice as long
/// as the one before, and the last step, `SPIN_LIMIT + maxParkShift`, repeats. The caller keeps the current step
/// in a local variable, starting at zero, passes it to [#backOff(int, int)] after every failed attempt, stores the
/// returned step, and resets it to zero after a successful attempt:
///
/// ```java
/// int backoffStep = 0;
/// while (!queue.offer(element)) {
///     backoffStep = SpinThenParkBackoff.backOff(backoffStep, MAX_PARK_SHIFT);
/// }
/// ```
///
/// A step below [#SPIN_LIMIT] means the waiting thread is still spinning. Callers may use that, e.g. to treat
/// a queue that stays empty through the spin phase as drained.
///
/// Waiting threads aren't served in any particular order. A thread that has backed off to parks at the cap
/// retries only once per park, while threads that are still spinning, or have just started waiting, retry all
/// the time. So under steady contention, the thread that has waited the longest can keep losing freed slots.
/// Where many threads compete, keep the cap low. A caller that needs a timeout shouldn't base it on how long
/// the thread has waited: it should fail only when the other side makes no progress, e.g. when the consumer has
/// taken no element for the whole timeout.
public final class SpinThenParkBackoff {

    /// Spin iterations before parking. 128 [Thread#onSpinWait()] hints take a few microseconds, which covers
    /// the usual gap between two messages; parking costs a system call and a scheduler wake-up (tens of
    /// microseconds on Linux), so it is only worth it once the wait gets longer.
    public static final int SPIN_LIMIT = 128;

    /// The largest supported park shift. Parks are capped at `1µs << maxParkShift`, so this allows parks of up
    /// to about 18 minutes, and keeps the park length far from overflowing.
    public static final int MAX_PARK_SHIFT = 30;

    private SpinThenParkBackoff() {}

    /// Waits once: spins for the first [#SPIN_LIMIT] calls of a wait, then parks for 1µs, 2µs, 4µs, and so
    /// on, up to `1µs << maxParkShift`. [LockSupport#parkNanos(long)] returns immediately if the thread is
    /// interrupted, so this method doesn't wait while the interrupted flag is set, and leaves it set. Callers
    /// observe interrupts on their next check.
    ///
    /// @param step the current step of this wait: zero for the first call, then the value returned by the
    ///     previous call
    /// @param maxParkShift the park length cap, as a power of two of microseconds: parks are at most
    ///     `1µs << maxParkShift` long. Must be between 0 and [#MAX_PARK_SHIFT]
    /// @return the next step, to pass to the next call. It stops at the last step, `SPIN_LIMIT + maxParkShift`,
    ///     so it never overflows during a long wait
    /// @throws IllegalArgumentException if `maxParkShift` is out of range
    public static int backOff(final int step, final int maxParkShift) {
        return backOff(step, maxParkShift, LockSupport::parkNanos);
    }

    // Same as backOff(int, int), with the park injected, so tests can check park lengths without parking
    static int backOff(final int step, final int maxParkShift, final LongConsumer park) {
        if ((maxParkShift < 0) || (maxParkShift > MAX_PARK_SHIFT)) {
            throw new IllegalArgumentException(
                    "maxParkShift must be between 0 and " + MAX_PARK_SHIFT + ": " + maxParkShift);
        }
        if (step < SPIN_LIMIT) {
            // first SPIN_LIMIT calls: stay on the CPU, the next element is likely microseconds away
            Thread.onSpinWait();
        } else {
            // after that, park for 1µs << n, where n is the number of steps past SPIN_LIMIT: 1µs, 2µs, 4µs, ...
            // capped at 1µs << maxParkShift
            park.accept(1_000L << Math.min(step - SPIN_LIMIT, maxParkShift));
        }
        // the caller passes the result back in on the next call. Capping it keeps the park length at its
        // maximum and stops the step from growing (and eventually overflowing) during a long wait
        return Math.min(step + 1, SPIN_LIMIT + maxParkShift);
    }
}
