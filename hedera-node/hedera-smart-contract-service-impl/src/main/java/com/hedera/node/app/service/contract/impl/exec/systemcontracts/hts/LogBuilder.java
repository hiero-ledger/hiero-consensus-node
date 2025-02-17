/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;

public class LogBuilder {
    private Address logger;
    private final List<Object> data = new ArrayList<>();
    private final List<LogTopic> topics = new ArrayList<>();
    final StringBuilder tupleTypes = new StringBuilder("(");

    public static LogBuilder logBuilder() {
        return new LogBuilder();
    }

    public LogBuilder forLogger(final Address logger) {
        this.logger = logger;
        return this;
    }

    public LogBuilder forEventSignature(final Bytes eventSignature) {
        topics.add(generateLogTopic(eventSignature));
        return this;
    }

    public LogBuilder forDataItem(final Object dataItem) {
        data.add(convertDataItem(dataItem));
        addTupleType(dataItem, tupleTypes);
        return this;
    }

    public LogBuilder forIndexedArgument(final Object param) {
        topics.add(generateLogTopic(param));
        return this;
    }

    public Log build() {
        if (tupleTypes.length() > 1) {
            tupleTypes.deleteCharAt(tupleTypes.length() - 1);
            tupleTypes.append(")");
            final var tuple = Tuple.from(data.toArray());
            final var tupleType = TupleType.parse(tupleTypes.toString());
            return new Log(logger, Bytes.wrap(tupleType.encode(tuple).array()), topics);
        } else {
            return new Log(logger, Bytes.EMPTY, topics);
        }
    }

    private Object convertDataItem(final Object param) {
        if (param instanceof Address address) {
            return convertBesuAddressToHeadlongAddress(address);
        } else if (param instanceof Long numeric) {
            return BigInteger.valueOf(numeric);
        } else {
            return param;
        }
    }

    private static LogTopic generateLogTopic(final Object param) {
        byte[] array = new byte[] {};
        if (param instanceof Address address) {
            array = address.toArray();
        } else if (param instanceof BigInteger numeric) {
            array = numeric.toByteArray();
        } else if (param instanceof Long numeric) {
            array = BigInteger.valueOf(numeric).toByteArray();
        } else if (param instanceof Boolean bool) {
            array = new byte[] {(byte) (Boolean.TRUE.equals(bool) ? 1 : 0)};
        } else if (param instanceof Bytes bytes) {
            array = bytes.toArray();
        }

        return LogTopic.wrap(Bytes.wrap(expandByteArrayTo32Length(array)));
    }

    private static void addTupleType(final Object param, final StringBuilder stringBuilder) {
        if (param instanceof Address) {
            stringBuilder.append("address,");
        } else if (param instanceof BigInteger || param instanceof Long) {
            stringBuilder.append("uint256,");
        } else if (param instanceof Boolean) {
            stringBuilder.append("bool,");
        }
    }

    private static byte[] expandByteArrayTo32Length(final byte[] bytesToExpand) {
        final byte[] expandedArray = new byte[32];

        System.arraycopy(
                bytesToExpand, 0, expandedArray, expandedArray.length - bytesToExpand.length, bytesToExpand.length);
        return expandedArray;
    }

    private static com.esaulpaugh.headlong.abi.Address convertBesuAddressToHeadlongAddress(
            @NonNull final Address address) {
        return com.esaulpaugh.headlong.abi.Address.wrap(
                com.esaulpaugh.headlong.abi.Address.toChecksumAddress(address.toUnsignedBigInteger()));
    }
}
