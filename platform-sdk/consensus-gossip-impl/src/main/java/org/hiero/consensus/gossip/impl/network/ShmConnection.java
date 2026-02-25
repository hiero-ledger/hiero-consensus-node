package org.hiero.consensus.gossip.impl.network;

import com.swirlds.config.api.Configuration;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.model.node.NodeId;

public class ShmConnection implements Connection {
    private final SyncInputStream is;
    private final SyncOutputStream os;
    private final NodeId selfId;
    private final NodeId nodeId;

    public ShmConnection(final SyncInputStream syncInputStream, final SyncOutputStream syncOutputStream,
            final NodeId selfId, final NodeId nodeId) {
        this.is = syncInputStream;
        this.os = syncOutputStream;
        this.selfId = selfId;

        this.nodeId = nodeId;
    }

    public static Connection create(final NodeId selfId, final NodeId nodeId, final ConnectionTracker connectionTracker, final Configuration configuration)
            throws Exception {


        deleteIfOld("/opt/shared/channel-" + selfId + "-"+ nodeId);
        deleteIfOld("/opt/shared/channel-" + nodeId + "-"+ selfId);

        FileChannel selfToOther = new RandomAccessFile("/opt/shared/channel-" + selfId + "-"+ nodeId, "rw").getChannel();
        FileChannel otherToSelf = new RandomAccessFile("/opt/shared/channel-" + nodeId + "-"+ selfId, "rw").getChannel();

        MappedByteBuffer selfToOtherBuffer = selfToOther.map(FileChannel.MapMode.READ_WRITE, 0, 1024*1024*1024);
        MappedByteBuffer otherToSelfBuffer = otherToSelf.map(FileChannel.MapMode.READ_WRITE, 0, 1024*1024*1024);


        ShmConnection connection = new ShmConnection(SyncInputStream.createSyncInputStream(
                configuration, new ShmInputStream(otherToSelfBuffer), 8*1024),SyncOutputStream.createSyncOutputStream(
                configuration, new ShmOutputStream(selfToOtherBuffer), 8*1024),
                selfId, nodeId);
        return connection;

//
//        logger.debug(NETWORK.getMarker(), "`connect` : finished, {} connected to {}", selfId, otherPeer.nodeId());

    }

    private static void deleteIfOld(final String s) {
        if ( System.currentTimeMillis() - new File(s).lastModified() > 10000 ) {
            new File(s).delete();
        };
    }

    @Override
    public void disconnect() {

    }

    @Override
    public NodeId getSelfId() {
        return selfId;
    }

    @Override
    public NodeId getOtherId() {
        return nodeId;
    }

    @Override
    public SyncInputStream getDis() {
        return is;
    }

    @Override
    public SyncOutputStream getDos() {
        return os;
    }

    @Override
    public boolean connected() {
        return true;
    }

    @Override
    public int getTimeout() throws SocketException {
        return 0;
    }

    @Override
    public void setTimeout(final long timeoutMillis) throws SocketException {

    }

    @Override
    public void initForSync() throws IOException {

    }

    @Override
    public boolean isOutbound() {
        return true;
    }

    @Override
    public String getDescription() {
        return "";
    }
}

