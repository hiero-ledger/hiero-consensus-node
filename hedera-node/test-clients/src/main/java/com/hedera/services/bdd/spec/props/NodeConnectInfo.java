// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.props;

import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiPropertySource.realm;
import static com.hedera.services.bdd.spec.HapiPropertySource.shard;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isNumericLiteral;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import java.util.stream.Stream;

/**
 * Node connection information.
 */
public class NodeConnectInfo {
    public static int NEXT_DEFAULT_ACCOUNT_NUM = 3;
    private static final int DEFAULT_PORT = 50211;
    private static final int DEFAULT_TLS_PORT = 50212;
    private static final String DEFAULT_HOST = "localhost";
    private static final String FORMATTER = "%s:%d";

    private final String host;
    private final int port;
    private final int tlsPort;
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
                .map(a -> AccountID.newBuilder()
                        .setShardNum(shard)
                        .setRealmNum(realm)
                        .setAccountNum(a.getAccountNum())
                        .build())
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

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getTlsPort() {
        return tlsPort;
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
                .add("account", HapiPropertySource.asAccountString(account))
                .toString();
    }
}
