// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/// Progressive backoff for threads that poll a non-blocking queue, such as the queues in
/// `org.hiero.base.concurrent.jctools.queues`, while it's full or empty: [#SPIN_LIMIT] spin hints first,
/// then parks of growing length, capped by the caller.
///
/// The caller keeps the number of failed attempts in a local variable, starting at zero, passes it to
/// [#backOff(int, int)] after every failed attempt, stores the returned value, and resets it to zero after a
/// successful one:
///
/// ```java
/// int idle = 0;
/// while (!queue.offer(element)) {
///     idle = SpinThenParkBackoff.backOff(idle, MAX_PARK_SHIFT);
/// }
/// ```
///
/// A count below [#SPIN_LIMIT] means the waiting thread is still spinning. Callers may use that, e.g. to treat
/// a queue that stays empty through the spin phase as drained.
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
    /// @param idle the number of failed attempts so far in the current wait, i.e. the value returned by the
    ///     previous call, or zero for the first call
    /// @param maxParkShift the park length cap, as a power of two of microseconds: parks are at most
    ///     `1µs << maxParkShift` long. Must be between 0 and [#MAX_PARK_SHIFT]
    /// @return the value to pass as `idle` to the next call. It stops growing once parks reach their maximum
    ///     length, so it never overflows during a long wait
    /// @throws IllegalArgumentException if `maxParkShift` is out of range
    public static int backOff(final int idle, final int maxParkShift) {
        return backOff(idle, maxParkShift, LockSupport::parkNanos);
    }

    // Same as backOff(int, int), with the park injected, so tests can check park lengths without parking
    static int backOff(final int idle, final int maxParkShift, final LongConsumer park) {
        if ((maxParkShift < 0) || (maxParkShift > MAX_PARK_SHIFT)) {
            throw new IllegalArgumentException(
                    "maxParkShift must be between 0 and " + MAX_PARK_SHIFT + ": " + maxParkShift);
        }
        if (idle < SPIN_LIMIT) {
            // first SPIN_LIMIT calls: stay on the CPU, the next element is likely microseconds away
            Thread.onSpinWait();
        } else {
            // after that, park for 1µs << n, where n counts the parks so far: 1µs, 2µs, 4µs, ...
            // capped at 1µs << maxParkShift
            park.accept(1_000L << Math.min(idle - SPIN_LIMIT, maxParkShift));
        }
        // the caller passes the result back in on the next call. Capping it keeps the park length at its
        // maximum and stops the counter from growing (and eventually overflowing) during a long wait
        return Math.min(idle + 1, SPIN_LIMIT + maxParkShift);
    }
}
