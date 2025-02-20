// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.crypto.SerializableHashable;
import com.swirlds.fcqueue.internal.FCQueueNode;

final class AccessibleFCQueue<E extends FastCopyable & SerializableHashable> extends SlowMockFCQueue<E> {

    public AccessibleFCQueue() {
        super();
    }

    private AccessibleFCQueue(final SlowMockFCQueue<E> queue) {
        super(queue);
    }

    protected FCQueueNode<E> getHead() {
        return this.head;
    }

    protected FCQueueNode<E> getTail() {
        return this.tail;
    }

    public AccessibleFCQueue<E> copy() {
        return new AccessibleFCQueue<>(super.copy());
    }
}
