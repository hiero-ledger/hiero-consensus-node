package com.hedera.node.app.blocks.impl.streaming;

public enum CommonFailureType {
    CONNECTION_REFUSED, // java.net.ConnectException: Connection refused
    SOCKET_CLOSED, // java.net.SocketException: Socket closed
    BROKEN_PIPE, // java.net.SocketException: Broken pipe
    UNKNOWN_HOST,
    INTERRUPTED, // java.lang.InterruptedException
    TIMEOUT, // java.util.concurrent.TimeoutException
    OTHER;


}
