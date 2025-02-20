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

package com.hedera.services.bdd.junit;

public class TestTags {

    private TestTags() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static final String CRYPTO = "CRYPTO";
    public static final String SMART_CONTRACT = "SMART_CONTRACT";
    public static final String LONG_RUNNING = "LONG_RUNNING";
    public static final String TOKEN = "TOKEN";
    public static final String RESTART = "RESTART";
    public static final String ND_RECONNECT = "ND_RECONNECT";
    public static final String UPGRADE = "UPGRADE";
    /**
     * Tags a embedded tests run as part of the default {@code Test} to provide efficient
     * integration tests of the app workflows (e.g., ingest, pre-handle, handle) and services.
     */
    public static final String INTEGRATION = "INTEGRATION";
    /**
     * Tags a test that <b>must</b> be run in subprocess mode, generally because it
     * depends on actual gossip occurring.
     */
    public static final String ONLY_SUBPROCESS = "ONLY_SUBPROCESS";
    /**
     * Tags a test that <b>must</b> be run in embedded mode, either because it directly
     * submits duplicate or invalid transactions to non-default nodes; or because it
     * uses direct state access only available in embedded mode.
     */
    public static final String ONLY_EMBEDDED = "EMBEDDED";
    /**
     * Tags a test that <b>must</b> be run in repeatable mode, either because it depends on
     * virtual time to complete in a reasonable period or because .
     */
    public static final String ONLY_REPEATABLE = "REPEATABLE";
    /**
     * Tags a test that <b>cannot</b> be run with the {@code testRepeatable} task for
     * some reason; e.g., it does not use fake time; or uses randomness or parallelism
     * that repeatable mode does not yet automatically toggle off; or explicitly uses
     * ECDSA keys (whose signatures are inherently random).
     */
    public static final String NOT_REPEATABLE = "NOT_REPEATABLE";
    /**
     * Tags a test that can be run alone, without any other tests.
     */
    public static final String ADHOC = "ADHOC";
}
