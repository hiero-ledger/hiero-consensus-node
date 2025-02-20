// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

/**
 * Enumerates the marker files that may be written by a node.
 */
public enum MarkerFile {
    NOW_FROZEN_MF {
        @Override
        public String fileName() {
            return "now_frozen.mf";
        }
    },
    EXEC_IMMEDIATE_MF {
        @Override
        public String fileName() {
            return "execute_immediate.mf";
        }
    },
    EXEC_TELEMETRY_MF {
        @Override
        public String fileName() {
            return "execute_telemetry.mf";
        }
    },
    FREEZE_SCHEDULED_MF {
        @Override
        public String fileName() {
            return "freeze_scheduled.mf";
        }
    },
    FREEZE_ABORTED_MF {
        @Override
        public String fileName() {
            return "freeze_aborted.mf";
        }
    };

    public abstract String fileName();
}
