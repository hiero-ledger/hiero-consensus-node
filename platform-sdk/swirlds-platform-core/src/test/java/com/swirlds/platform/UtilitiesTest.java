// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import com.swirlds.platform.internal.Deserializer;
import com.swirlds.platform.internal.Serializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.net.ssl.SSLException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.Test;

class UtilitiesTest {

    @Test
    void writeReadList() throws IOException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        SerializableDataOutputStream fcOut = new SerializableDataOutputStream(byteOut);

        List<Pair<Long, Long>> w1 = null;
        List<Pair<Long, Long>> w2 = new ArrayList<>();
        List<Pair<Long, Long>> w3 = new LinkedList<>();
        w3.add(Pair.of(1L, 2L));
        w3.add(Pair.of(3L, 4L));

        Serializer<Pair<Long, Long>> ser = (pair, stream) -> {
            stream.writeLong(pair.key());
            stream.writeLong(pair.value());
        };

        Utilities.writeList(w1, fcOut, ser);
        Utilities.writeList(w2, fcOut, ser);
        Utilities.writeList(w3, fcOut, ser);

        SerializableDataInputStream fcIn =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        Deserializer<Pair<Long, Long>> des = (stream) -> {
            long key = stream.readLong();
            long value = stream.readLong();
            return Pair.of(key, value);
        };

        List<Pair<Long, Long>> r1 = Utilities.readList(fcIn, LinkedList::new, des);
        List<Pair<Long, Long>> r2 = Utilities.readList(fcIn, LinkedList::new, des);
        List<Pair<Long, Long>> r3 = Utilities.readList(fcIn, LinkedList::new, des);

        assertIterableEquals(w1, r1);
        assertIterableEquals(w2, r2);
        assertIterableEquals(w3, r3);
    }

    @Test
    void isOrCausedBySocketExceptionTest() {
        assertFalse(Utilities.isOrCausedBySocketException(null));

        SSLException sslException = new SSLException("sslException");
        assertFalse(Utilities.isOrCausedBySocketException(sslException));

        SocketException socketException = new SocketException();
        assertTrue(Utilities.isOrCausedBySocketException(socketException));

        SSLException sslExCausedBySocketEx = new SSLException(socketException);
        assertTrue(Utilities.isOrCausedBySocketException(sslExCausedBySocketEx));

        SSLException sslExceptionMultiLayer = new SSLException(sslExCausedBySocketEx);
        assertTrue(Utilities.isOrCausedBySocketException(sslExceptionMultiLayer));
    }

    @Test
    void isRootCauseSuppliedTypeTest() {
        assertTrue(
                Utilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new IOException())), IOException.class),
                "root cause should be IOException");

        assertFalse(
                Utilities.isRootCauseSuppliedType(
                        new Exception(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException");

        assertFalse(
                Utilities.isRootCauseSuppliedType(
                        new IOException(new IllegalArgumentException(new NullPointerException())), IOException.class),
                "root cause should not be IOException, even though is exists in the stack");
    }
}
