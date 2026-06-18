// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.node.base.codec;

import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.base.Key;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class KeyProtoCodecTest {
    private static final int PBJ_0_15_9_DEFAULT_MAX_DEPTH = 128;
    private static final int MAX_TRANSACTION_BYTES = 6 * 1024;
    private static final int CONSTRAINED_STACK_SIZE = 64 * 1024;
    private static final int ONE_MEBIBYTE_STACK_SIZE = 1024 * 1024;
    private static final int PBJ_MESSAGE_FRAMES_PER_KEY_LIST_LEVEL = 2;
    private static final int DEEPEST_DEFAULT_ALLOWED_KEY_LIST_LEVELS =
            DEFAULT_MAX_DEPTH / PBJ_MESSAGE_FRAMES_PER_KEY_LIST_LEVEL;
    private static final int FIRST_KEY_LIST_LEVEL_REJECTED_BY_DEFAULT_DEPTH =
            DEEPEST_DEFAULT_ALLOWED_KEY_LIST_LEVELS + 1;
    private static final byte KEY_LIST_TAG = 50;
    private static final byte KEY_LIST_KEYS_TAG = 10;
    private static final byte ED25519_TAG = 18;
    private static final byte[] ED25519_KEY = lengthDelimited(ED25519_TAG, new byte[32]);

    @Test
    void pbjRuntimeUsesUpdatedDefaultMaxDepth() {
        assertThat(DEFAULT_MAX_DEPTH).isEqualTo(PBJ_0_15_9_DEFAULT_MAX_DEPTH);
    }

    @Test
    void deeplyNestedSerializedKeyUnderSixKiBCanOverflowUnboundedParserStack() throws InterruptedException {
        final var serializedKey = deepestKeyListNestUnder(MAX_TRANSACTION_BYTES);

        assertThat(serializedKey.bytes()).hasSizeLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThat(serializedKey.nestingLevels()).isGreaterThan(FIRST_KEY_LIST_LEVEL_REJECTED_BY_DEFAULT_DEPTH);

        assertThat(parseWithUnboundedDepthOnConstrainedStack(serializedKey.bytes()))
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void deeplyNestedSerializedKeyUnderSixKiBIsRejectedByDefaultDepthLimit() {
        final var serializedKey = deepestKeyListNestUnder(MAX_TRANSACTION_BYTES);

        assertThat(serializedKey.bytes()).hasSizeLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThatThrownBy(() -> Key.PROTOBUF.parse(
                        Bytes.wrap(serializedKey.bytes()).toReadableSequentialData(),
                        false,
                        false,
                        DEFAULT_MAX_DEPTH,
                        MAX_TRANSACTION_BYTES))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Reached maximum allowed depth");
    }

    @Test
    void pbjParserRejectsFirstKeyListLevelBeyondDefaultDepthWithParseException() {
        final var serializedKey = keyListNest(FIRST_KEY_LIST_LEVEL_REJECTED_BY_DEFAULT_DEPTH);

        assertThat(serializedKey.bytes()).hasSizeLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThatThrownBy(() -> Key.PROTOBUF.parse(Bytes.wrap(serializedKey.bytes())))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Reached maximum allowed depth");
    }

    @Test
    void deepestDefaultAllowedSerializedKeyUnderSixKiBParsesOnOneMiBStack() throws InterruptedException {
        final var serializedKey = keyListNest(DEEPEST_DEFAULT_ALLOWED_KEY_LIST_LEVELS);

        assertThat(serializedKey.bytes()).hasSizeLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThatCode(() -> parseWithDefaultDepthOnStack(serializedKey.bytes(), ONE_MEBIBYTE_STACK_SIZE))
                .doesNotThrowAnyException();
    }

    private static void parseWithDefaultDepthOnStack(final byte[] serializedKey, final int stackSize)
            throws InterruptedException {
        final var thrown = parseOnStack(serializedKey, DEFAULT_MAX_DEPTH, stackSize);
        if (thrown != null) {
            throw new AssertionError(thrown);
        }
    }

    private static Throwable parseWithUnboundedDepthOnConstrainedStack(final byte[] serializedKey)
            throws InterruptedException {
        return parseOnStack(serializedKey, Integer.MAX_VALUE, CONSTRAINED_STACK_SIZE);
    }

    private static Throwable parseOnStack(final byte[] serializedKey, final int maxDepth, final int stackSize)
            throws InterruptedException {
        final var thrown = new AtomicReference<Throwable>();
        final var thread = new Thread(
                null,
                () -> {
                    try {
                        Key.PROTOBUF.parse(
                                Bytes.wrap(serializedKey).toReadableSequentialData(),
                                false,
                                false,
                                maxDepth,
                                MAX_TRANSACTION_BYTES);
                    } catch (final Throwable t) {
                        thrown.set(t);
                    }
                },
                "pbj-key-stack-overflow-test",
                stackSize);
        thread.setUncaughtExceptionHandler((ignored, t) -> thrown.set(t));

        thread.start();
        thread.join(Duration.ofSeconds(10).toMillis());
        if (thread.isAlive()) {
            thread.interrupt();
            fail("Timed out while parsing deeply nested key");
        }
        return thrown.get();
    }

    private static SerializedKey deepestKeyListNestUnder(final int maxBytes) {
        byte[] bytes = ED25519_KEY;
        int nestingLevels = 0;
        while (true) {
            final var next = keyWithSingleNestedKeyList(bytes);
            if (next.length > maxBytes) {
                return new SerializedKey(bytes, nestingLevels);
            }
            bytes = next;
            nestingLevels++;
        }
    }

    private static SerializedKey keyListNest(final int nestingLevels) {
        byte[] bytes = ED25519_KEY;
        for (int i = 0; i < nestingLevels; i++) {
            bytes = keyWithSingleNestedKeyList(bytes);
        }
        return new SerializedKey(bytes, nestingLevels);
    }

    private static byte[] keyWithSingleNestedKeyList(final byte[] nestedKey) {
        final var keyList = lengthDelimited(KEY_LIST_KEYS_TAG, nestedKey);
        return lengthDelimited(KEY_LIST_TAG, keyList);
    }

    private static byte[] lengthDelimited(final byte tag, final byte[] contents) {
        final var out = new ByteArrayOutputStream(1 + varIntSize(contents.length) + contents.length);
        out.write(tag);
        writeVarInt(out, contents.length);
        out.writeBytes(contents);
        return out.toByteArray();
    }

    private static void writeVarInt(final ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private record SerializedKey(byte[] bytes, int nestingLevels) {}
}
