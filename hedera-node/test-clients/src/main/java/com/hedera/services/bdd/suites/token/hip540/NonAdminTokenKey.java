// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_METADATA_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_METADATA_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

/**
 * Enumerates the possible non-admin token keys in a HIP-540 test scenario.
 */
public enum NonAdminTokenKey {
    WIPE_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_WIPE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_WIPE_KEY;
        }
    },
    KYC_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_KYC_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_KYC_KEY;
        }
    },
    SUPPLY_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_SUPPLY_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_SUPPLY_KEY;
        }
    },
    FREEZE_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_FREEZE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_FREEZE_KEY;
        }
    },
    FEE_SCHEDULE_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_CUSTOM_FEE_SCHEDULE_KEY;
        }
    },
    PAUSE_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_PAUSE_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_PAUSE_KEY;
        }
    },
    METADATA_KEY {
        @Override
        public ResponseCodeEnum tokenHasNoKeyStatus() {
            return TOKEN_HAS_NO_METADATA_KEY;
        }

        @Override
        public ResponseCodeEnum invalidKeyStatus() {
            return INVALID_METADATA_KEY;
        }
    };

    public abstract ResponseCodeEnum tokenHasNoKeyStatus();

    public abstract ResponseCodeEnum invalidKeyStatus();
}
