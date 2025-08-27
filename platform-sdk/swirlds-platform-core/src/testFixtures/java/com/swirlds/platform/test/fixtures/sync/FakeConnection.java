// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.node.NodeId;

public class FakeConnection implements Connection {
    public final CountDownLatch disconnect = new CountDownLatch(1);
    private final NodeId selfId;
    private final NodeId peerId;

    public FakeConnection() {
        this(NodeId.of(0L), NodeId.of(1));
    }

    public FakeConnection(final NodeId selfId, final NodeId peerId) {
        this.selfId = selfId;
        this.peerId = peerId;
    }

    @Override
    public void disconnect() {
        disconnect.countDown();
    }

    public boolean awaitDisconnect() throws InterruptedException {
        return disconnect.await(1, TimeUnit.MILLISECONDS);
    }

    @Override
    public NodeId getSelfId() {
        return selfId;
    }

    @Override
    public NodeId getOtherId() {
        return peerId;
    }

    @Override
    public SyncInputStream getDis() {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public SyncOutputStream getDos() {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public boolean connected() {
        return disconnect.getCount() > 0;
    }

    @Override
    public int getTimeout() {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public void setTimeout(final long timeoutMillis) {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public void initForSync() {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public boolean isOutbound() {
        throw new UnsupportedOperationException("N/A");
    }

    @Override
    public String getDescription() {
        return toString();
    }
}
