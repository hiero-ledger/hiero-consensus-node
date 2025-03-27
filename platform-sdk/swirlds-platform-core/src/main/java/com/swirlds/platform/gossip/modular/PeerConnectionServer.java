// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.connectivity.SocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;

/**
 * Listens on a server socket for incoming connections. All new connections are passed on to the supplied handler.
 */
public class PeerConnectionServer implements InterruptableRunnable {
    /** number of milliseconds to sleep when a server socket binds fails until trying again */
    private static final int SLEEP_AFTER_BIND_FAILED_MS = 100;
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(PeerConnectionServer.class);
    /** the port that this server listens on for establishing new connections */
    private final int port;
    /** responsible for creating and binding the server socket */
    private final SocketFactory socketFactory;
    /** handles newly established connections */
    private volatile InboundConnectionHandler newConnectionHandler;
    /** a thread pool used to handle incoming connections - we need it, as ssl handshake takes long */
    private final ExecutorService incomingConnPool;

    /**
     * @param threadManager            responsible for managing thread lifecycles
     * @param port                     the port ot use
     * @param inboundConnectionHandler handles a new connection after it has been created
     * @param socketFactory            responsible for creating new sockets
     */
    public PeerConnectionServer(
            final ThreadManager threadManager,
            int port,
            InboundConnectionHandler inboundConnectionHandler,
            SocketFactory socketFactory,
            int maxThreads) {
        this.port = port;
        this.newConnectionHandler = inboundConnectionHandler;
        this.socketFactory = socketFactory;
        this.incomingConnPool = new ThreadPoolExecutor(
                1,
                maxThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new ThreadConfiguration(threadManager)
                        .setThreadName("sync_server")
                        .buildFactory());
    }

    @Override
    public void run() throws InterruptedException {
        try (final ServerSocket serverSocket = socketFactory.createServerSocket(port)) {
            listen(serverSocket);
        } catch (final RuntimeException | IOException e) {
            logger.error(EXCEPTION.getMarker(), "Cannot bind ServerSocket on port {}", port, e);
        }
        // if the above fails, sleep a while before trying again
        Thread.sleep(SLEEP_AFTER_BIND_FAILED_MS);
    }

    /**
     * listens for incoming connections until interrupted or socket is closed
     */
    private void listen(final ServerSocket serverSocket) throws InterruptedException {
        // Handle incoming connections
        while (!serverSocket.isClosed()) {
            try {
                final Socket clientSocket = serverSocket.accept(); // listen, waiting until someone connects
                incomingConnPool.submit(() -> newConnectionHandler.handle(clientSocket));
            } catch (final SocketTimeoutException expectedWithNonZeroSOTimeout) {
                // A timeout is expected, so we won't log it
                if (Thread.currentThread().isInterrupted()) {
                    // since accept() cannot be interrupted, we check the interrupted status on a timeout and throw
                    throw new InterruptedException();
                }
            } catch (final RuntimeException | IOException e) {
                logger.error(EXCEPTION.getMarker(), "SyncServer serverSocket.accept() error", e);
            }
        }
    }

    /**
     * Update information about possible peers
     *
     * @param peers new list of all peers
     */
    public void replacePeers(List<PeerInfo> peers) {
        // there is no good way to synchronize these two field updates versus accept/handle in listen method
        // there is always a chance that accept was done with a older version of certs, but connectionHandler sees
        // new version already
        // worst what can happen is that we will reject a valid connection once, but on next reconnection it will
        // succeed
        synchronized (this.socketFactory) {
            this.socketFactory.reload(peers);
            this.newConnectionHandler = this.newConnectionHandler.withNewPeers(peers);
        }
    }
}
