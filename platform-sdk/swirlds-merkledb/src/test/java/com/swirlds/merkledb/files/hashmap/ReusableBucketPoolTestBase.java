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

package com.swirlds.merkledb.files.hashmap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

abstract class ReusableBucketPoolTestBase {

    protected abstract ReusableBucketPool createPool(final int size);

    final AtomicReference<Throwable> error = new AtomicReference<>();
    Thread.UncaughtExceptionHandler originalHandler;

    @BeforeEach
    void setupThreads() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(((thread, throwable) -> error.set(throwable)));
    }

    @AfterEach
    void resore() {
        Assertions.assertNull(error.get(), () -> {
            final StringWriter sw = new StringWriter();
            error.get().printStackTrace(new PrintWriter(sw));
            return sw.toString();
        });
        Thread.setDefaultUncaughtExceptionHandler(originalHandler);
    }

    @Test
    @DisplayName("Basic get / release bucket")
    void basicGetRelease() {
        final ReusableBucketPool pool = createPool(2);
        final Bucket bucket1 = pool.getBucket();
        Assertions.assertNotNull(bucket1);
        final Bucket bucket2 = pool.getBucket();
        Assertions.assertNotNull(bucket2);
        Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket1));
        Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket2));
        final Bucket bucket3 = pool.getBucket();
        Assertions.assertNotNull(bucket3);
        Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket3));
    }

    @Test
    @DisplayName("Multiple gets when pool is empty")
    void multipleBlockingGets() {
        final ReusableBucketPool pool = createPool(0);
        final Bucket bucket1 = pool.getBucket();
        Assertions.assertNotNull(bucket1);
        final Bucket bucket2 = pool.getBucket();
        Assertions.assertNotNull(bucket2);
        final Set<Thread> threads = new HashSet<>();
        threads.add(new Thread(() -> {
            final Bucket bucket3 = pool.getBucket();
            Assertions.assertNotNull(bucket3);
            Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket3));
            Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket1));
        }));
        threads.add(new Thread(() -> {
            final Bucket bucket4 = pool.getBucket();
            Assertions.assertNotNull(bucket4);
            Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket4));
            Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket2));
        }));
        for (final Thread t : threads) {
            t.start();
        }
        for (final Thread t : threads) {
            Assertions.assertTimeout(Duration.ofSeconds(10), () -> t.join());
        }
    }

    @Test
    @DisplayName("Chained release / get calls")
    void chainedReleasesGets() {
        final ReusableBucketPool pool = createPool(2);
        final Bucket bucket1 = pool.getBucket();
        Assertions.assertNotNull(bucket1);
        final Bucket bucket2 = pool.getBucket();
        Assertions.assertNotNull(bucket2);
        final Set<Thread> threads = new HashSet<>();
        // Create a few threads that will all wait until there is a bucket available
        for (int i = 0; i < 4; i++) {
            final Thread t = new Thread(() -> {
                final Bucket bucket3 = pool.getBucket();
                Assertions.assertNotNull(bucket3);
                Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket3));
            });
            threads.add(t);
            t.start();
        }
        // Release one of the buckets. It should trigger chained thread unblock reaction
        Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket1));
        for (final Thread t : threads) {
            Assertions.assertTimeout(Duration.ofSeconds(10), () -> t.join());
        }
        Assertions.assertDoesNotThrow(() -> pool.releaseBucket(bucket2));
    }
}
