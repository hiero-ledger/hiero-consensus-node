// SPDX-License-Identifier: Apache-2.0
/*
 * Derived from JCTools v4.0.6 (https://github.com/JCTools/JCTools),
 * artifact jctools-core, org.jctools.queues.MessagePassingQueue.
 * Modified: package relocated to org.hiero.base.concurrent.jctools.queues; Javadoc converted to Markdown (///)
 * with link targets pointing at the relocated classes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hiero.base.concurrent.jctools.queues;

/// Message passing queues are intended for concurrent method passing. A subset of [java.util.Queue] methods are
/// provided
/// with the same semantics, while further functionality which accomodates the concurrent usecase is also on
/// offer.
///
/// Message passing queues provide happens before semantics to messages passed through, namely that writes made
/// by the producer before offering the message are visible to the consuming thread after the message has been
/// polled out of the queue.
///
/// @param <T> the event/message type
public interface MessagePassingQueue<T> {
    int UNBOUNDED_CAPACITY = -1;

    interface Supplier<T> {
        /// This method will return the next value to be written to the queue. As such the queue
        /// implementations are commited to insert the value once the call is made.
        ///
        /// Users should be aware that underlying queue implementations may upfront claim parts of the queue
        /// for batch operations and this will effect the view on the queue from the supplier method. In
        /// particular size and any offer methods may take the view that the full batch has already happened.
        ///
        /// **WARNING**: this method is assumed to never throw. Breaking this assumption can lead to a broken
        /// queue.
        ///
        /// **WARNING**: this method is assumed to never return `null`. Breaking this assumption can lead to a
        /// broken queue.
        ///
        /// @return new element, NEVER `null`
        T get();
    }

    interface Consumer<T> {
        /// This method will process an element already removed from the queue. This method is expected to
        /// never throw an exception.
        ///
        /// Users should be aware that underlying queue implementations may upfront claim parts of the queue
        /// for batch operations and this will effect the view on the queue from the accept method. In
        /// particular size and any poll/peek methods may take the view that the full batch has already
        /// happened.
        ///
        /// **WARNING**: this method is assumed to never throw. Breaking this assumption can lead to a broken
        /// queue.
        ///
        /// @param e not `null`
        void accept(T e);
    }

    interface WaitStrategy {
        /// This method can implement static or dynamic backoff. Dynamic backoff will rely on the counter for
        /// estimating how long the caller has been idling. The expected usage is:
        ///
        /// ```
        /// int ic = 0;
        /// while(true) {
        ///   if(!isGodotArrived()) {
        ///     ic = w.idle(ic);
        ///     continue;
        ///   }
        ///   ic = 0;
        ///   // party with Godot until he goes again
        /// }
        /// ```
        ///
        /// @param idleCounter idle calls counter, managed by the idle method until reset
        /// @return new counter value to be used on subsequent idle cycle
        int idle(int idleCounter);
    }

    interface ExitCondition {

        /// This method should be implemented such that the flag read or determination cannot be hoisted out of
        /// a loop which notmally means a volatile load, but with JDK9 VarHandles may mean getOpaque.
        ///
        /// @return true as long as we should keep running
        boolean keepRunning();
    }

    /// Called from a producer thread subject to the restrictions appropriate to the implementation and
    /// according to the [java.util.Queue#offer(Object)] interface.
    ///
    /// @param e not `null`, will throw NPE if it is
    /// @return true if element was inserted into the queue, false iff full
    boolean offer(T e);

    /// Called from the consumer thread subject to the restrictions appropriate to the implementation and
    /// according to the [java.util.Queue#poll()] interface.
    ///
    /// @return a message from the queue if one is available, `null` iff empty
    T poll();

    /// Called from the consumer thread subject to the restrictions appropriate to the implementation and
    /// according to the [java.util.Queue#peek()] interface.
    ///
    /// @return a message from the queue if one is available, `null` iff empty
    T peek();

    /// This method's accuracy is subject to concurrent modifications happening as the size is estimated and as
    /// such is a best effort rather than absolute value. For some implementations this method may be O(n)
    /// rather than O(1).
    ///
    /// @return number of messages in the queue, between 0 and [Integer#MAX_VALUE] but less or equals to
    /// capacity (if bounded).
    int size();

    /// Removes all items from the queue. Called from the consumer thread subject to the restrictions
    /// appropriate to the implementation and according to the [java.util.Queue#clear()] interface.
    void clear();

    /// This method's accuracy is subject to concurrent modifications happening as the observation is carried
    /// out.
    ///
    /// @return true if empty, false otherwise
    boolean isEmpty();

    /// @return the capacity of this queue or [MessagePassingQueue#UNBOUNDED_CAPACITY] if not bounded
    int capacity();

    /// Called from a producer thread subject to the restrictions appropriate to the implementation. As opposed
    /// to [java.util.Queue#offer(Object)] this method may return false without the queue being full.
    ///
    /// @param e not `null`, will throw NPE if it is
    /// @return true if element was inserted into the queue, false if unable to offer
    boolean relaxedOffer(T e);

    /// Called from the consumer thread subject to the restrictions appropriate to the implementation. As
    /// opposed to [java.util.Queue#poll()] this method may return `null` without the queue being empty.
    ///
    /// @return a message from the queue if one is available, `null` if unable to poll
    T relaxedPoll();

    /// Called from the consumer thread subject to the restrictions appropriate to the implementation. As
    /// opposed to [java.util.Queue#peek()] this method may return `null` without the queue being empty.
    ///
    /// @return a message from the queue if one is available, `null` if unable to peek
    T relaxedPeek();

    /// Remove up to _limit_ elements from the queue and hand to consume. This should be semantically
    /// similar to:
    ///
    /// <pre>`M m;
    ///   int i = 0;
    ///   for(;i < limit && (m = relaxedPoll()) != null; i++){
    ///     c.accept(m);
    ///   }
    ///   return i;`</pre>
    ///
    /// There's no strong commitment to the queue being empty at the end of a drain. Called from a consumer
    /// thread subject to the restrictions appropriate to the implementation.
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Consumer#accept] make sure you have read
    /// and understood these before using this method.
    ///
    /// @return the number of polled elements
    /// @throws IllegalArgumentException c is `null`
    /// @throws IllegalArgumentException if limit is negative
    int drain(Consumer<T> c, int limit);

    /// Stuff the queue with up to _limit_ elements from the supplier. Semantically similar to:
    ///
    /// <pre>`for(int i=0; i < limit && relaxedOffer(s.get()); i++);`</pre>
    ///
    /// There's no strong commitment to the queue being full at the end of a fill. Called from a producer
    /// thread subject to the restrictions appropriate to the implementation.
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Supplier#get] make sure you have read
    /// and understood these before using this method.
    ///
    /// @return the number of offered elements
    /// @throws IllegalArgumentException s is `null`
    /// @throws IllegalArgumentException if limit is negative
    int fill(Supplier<T> s, int limit);

    /// Remove all available item from the queue and hand to consume. This should be semantically similar to:
    /// ```
    /// M m;
    /// while((m = relaxedPoll()) != null){
    /// c.accept(m);
    /// }
    /// ```
    /// There's no strong commitment to the queue being empty at the end of a drain. Called from a
    /// consumer thread subject to the restrictions appropriate to the implementation.
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Consumer#accept] make sure you have read
    /// and understood these before using this method.
    ///
    /// @return the number of polled elements
    /// @throws IllegalArgumentException c is `null`
    int drain(Consumer<T> c);

    /// Stuff the queue with elements from the supplier. Semantically similar to:
    /// ```
    /// while(relaxedOffer(s.get());
    /// ```
    /// There's no strong commitment to the queue being full at the end of a fill. Called from a
    /// producer thread subject to the restrictions appropriate to the implementation.
    ///
    /// Unbounded queues will fill up the queue with a fixed amount rather than fill up to oblivion.
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Supplier#get] make sure you have read
    /// and understood these before using this method.
    ///
    /// @return the number of offered elements
    /// @throws IllegalArgumentException s is `null`
    int fill(Supplier<T> s);

    /// Remove elements from the queue and hand to consume forever. Semantically similar to:
    ///
    /// ```
    ///  int idleCounter = 0;
    ///  while (exit.keepRunning()) {
    ///      E e = relaxedPoll();
    ///      if(e==null){
    ///          idleCounter = wait.idle(idleCounter);
    ///          continue;
    ///      }
    ///      idleCounter = 0;
    ///      c.accept(e);
    ///  }
    /// ```
    ///
    /// Called from a consumer thread subject to the restrictions appropriate to the implementation.
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Consumer#accept] make sure you have read
    /// and understood these before using this method.
    ///
    /// @throws IllegalArgumentException c OR wait OR exit are `null`
    void drain(Consumer<T> c, WaitStrategy wait, ExitCondition exit);

    /// Stuff the queue with elements from the supplier forever. Semantically similar to:
    ///
    /// ```
    ///  int idleCounter = 0;
    ///  while (exit.keepRunning()) {
    ///      E e = s.get();
    ///      while (!relaxedOffer(e)) {
    ///          idleCounter = wait.idle(idleCounter);
    ///          continue;
    ///      }
    ///      idleCounter = 0;
    ///  }
    /// ```
    ///
    /// Called from a producer thread subject to the restrictions appropriate to the implementation. The main
    /// difference
    /// being that implementors MUST assure room in the queue is available BEFORE calling [Supplier#get].
    ///
    /// **WARNING**: Explicit assumptions are made with regards to [Supplier#get] make sure you have read
    /// and understood these before using this method.
    ///
    /// @throws IllegalArgumentException s OR wait OR exit are `null`
    void fill(Supplier<T> s, WaitStrategy wait, ExitCondition exit);
}
