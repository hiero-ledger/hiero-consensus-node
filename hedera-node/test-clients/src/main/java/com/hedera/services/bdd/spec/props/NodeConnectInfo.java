/*
 * Copyright (C) 2020-2025 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.spec.props;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isNumericLiteral;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.stream.Stream;

/**
 * Node connection information.
 * @deprecated get node connection info directly from a {@link HederaNode} object instead
 */
@Deprecated(forRemoval = true)
public class NodeConnectInfo {
    public static int NEXT_DEFAULT_ACCOUNT_NUM = 3;
    private static final int DEFAULT_PORT = 50211;
    private static final int DEFAULT_TLS_PORT = 50212;
    private static final int DEFAULT_WORKFLOW_PORT = 60211;
    private static final int DEFAULT_WORKFLOW_TLS_PORT = 60212;
    private static final String DEFAULT_HOST = "localhost";
    private static final String FORMATTER = "%s:%d";

    private final String host;
    private final int port;
    private final int tlsPort;
    private final int workflowPort = DEFAULT_WORKFLOW_PORT;
    private final int workflowTlsPort = DEFAULT_WORKFLOW_TLS_PORT;
    private final AccountID account;

    public NodeConnectInfo(String inString) {
        String[] aspects = inString.split(":");
        int[] ports = Stream.of(aspects)
                .filter(TxnUtils::isNumericLiteral)
                .mapToInt(Integer::parseInt)
                .toArray();
        if (ports.length > 0) {
            port = ports[0];
        } else {
            port = DEFAULT_PORT;
        }
        if (ports.length > 1) {
            tlsPort = ports[1];
        } else {
            tlsPort = DEFAULT_TLS_PORT;
        }

        account = Stream.of(aspects)
                .filter(TxnUtils::isIdLiteral)
                .map(HapiPropertySource::asAccount)
                .findAny()
                .orElse(HapiPropertySource.asAccount(asEntityString(NEXT_DEFAULT_ACCOUNT_NUM++)));
        host = Stream.of(aspects)
                .filter(aspect -> !(isIdLiteral(aspect) || isNumericLiteral(aspect)))
                .findAny()
                .orElse(DEFAULT_HOST);
    }

    public String uri() {
        return String.format(FORMATTER, host, port);
    }

    public String tlsUri() {
        return String.format(FORMATTER, host, tlsPort);
    }

    public String workflowUri() {
        return String.format(FORMATTER, host, workflowPort);
    }

    public String workflowTlsUri() {
        return String.format(FORMATTER, host, workflowTlsPort);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getTlsPort() {
        return tlsPort;
    }

    public int getWorkflowPort() {
        return workflowPort;
    }

    public int getWorkflowTlsPort() {
        return workflowTlsPort;
    }

    public AccountID getAccount() {
        return account;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("host", host)
                .add("port", port)
                .add("tlsPort", tlsPort)
                .add("workflowPort", workflowPort)
                .add("workflowTlsPort", workflowTlsPort)
                .add("account", HapiPropertySource.asAccountString(account))
                .toString();
    }
}
