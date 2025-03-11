// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.spec.HapiSpec;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogMessage extends UtilOp {
    static final Logger log = LogManager.getLogger(CustomSpecAssert.class);
    private final Function<HapiSpec, String> messageFn;

    public LogMessage(Function<HapiSpec, String> messageFn) {
        this.messageFn = messageFn;
    }

    public LogMessage(String hardcoded) {
        this(ignore -> hardcoded);
    }

    @Override
    protected boolean submitOp(HapiSpec spec) {
        log.info(messageFn.apply(spec));
        return false;
    }
}
