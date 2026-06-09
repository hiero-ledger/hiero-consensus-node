// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.addressbook.impl.RegisteredNodeChangeListener;
import com.hedera.node.app.service.addressbook.impl.RegisteredNodeChangeNotifier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegisteredNodeChangeNotifierTest {

    @Test
    void noListenersIsNoOp() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        notifier.notifyChanged(); // should not throw
    }

    @Test
    void registeredListenerIsCalledOncePerNotify() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        final AtomicInteger invocations = new AtomicInteger();
        notifier.register(invocations::incrementAndGet);

        notifier.notifyChanged();
        notifier.notifyChanged();

        assertThat(invocations.get()).isEqualTo(2);
    }

    @Test
    void multipleListenersAreAllCalled() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        final AtomicInteger a = new AtomicInteger();
        final AtomicInteger b = new AtomicInteger();
        notifier.register(a::incrementAndGet);
        notifier.register(b::incrementAndGet);

        notifier.notifyChanged();

        assertThat(a.get()).isEqualTo(1);
        assertThat(b.get()).isEqualTo(1);
    }

    @Test
    void duplicateRegistrationIsIgnored() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        final AtomicInteger invocations = new AtomicInteger();
        final RegisteredNodeChangeListener listener = invocations::incrementAndGet;

        notifier.register(listener);
        notifier.register(listener);
        notifier.notifyChanged();

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void unregisterStopsListenerFromBeingCalled() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        final AtomicInteger invocations = new AtomicInteger();
        final RegisteredNodeChangeListener listener = invocations::incrementAndGet;
        notifier.register(listener);

        notifier.notifyChanged();
        notifier.unregister(listener);
        notifier.notifyChanged();

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void misbehavingListenerDoesNotBreakDispatchForOthers() {
        final RegisteredNodeChangeNotifier notifier = new RegisteredNodeChangeNotifier();
        final AtomicInteger goodInvocations = new AtomicInteger();
        notifier.register(() -> {
            throw new RuntimeException("boom");
        });
        notifier.register(goodInvocations::incrementAndGet);

        notifier.notifyChanged(); // should not throw

        assertThat(goodInvocations.get()).isEqualTo(1);
    }
}
