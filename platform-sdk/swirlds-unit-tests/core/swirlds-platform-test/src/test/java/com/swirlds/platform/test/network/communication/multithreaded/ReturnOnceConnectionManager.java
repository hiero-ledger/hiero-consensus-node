/*
 * Copyright (C) 2018-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.network.communication.multithreaded;

import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionManager;

/**
 * A connection manager that returns a connection once after which it throws an interrupted exception every time
 */
public class ReturnOnceConnectionManager implements ConnectionManager {
    final Connection connection;
    private volatile boolean connectionReturned = false;

    public ReturnOnceConnectionManager(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection waitForConnection() throws InterruptedException {
        if (connectionReturned) {
            throw new InterruptedException();
        }
        connectionReturned = true;
        return connection;
    }

    @Override
    public Connection getConnection() {
        throw new IllegalStateException("unsupported");
    }

    @Override
    public void newConnection(final Connection connection) {
        throw new IllegalStateException("unsupported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOutbound() {
        return connection.isOutbound();
    }
}
