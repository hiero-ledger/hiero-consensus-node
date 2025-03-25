// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromMnemonic.createAndLinkSimpleKey;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.CommonUtils;

public class SpecKeyFromLiteral extends UtilOp {
    private static final Logger log = LogManager.getLogger(SpecKeyFromLiteral.class);

    private final String name;
    private final String hexEncodedPrivateKey;
    private Optional<String> linkedId = Optional.empty();

    public SpecKeyFromLiteral(String name, String hexEncodedPrivateKey) {
        this.name = name;
        this.hexEncodedPrivateKey = hexEncodedPrivateKey;
    }

    public SpecKeyFromLiteral linkedTo(String id) {
        linkedId = Optional.of(id);
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        byte[] privateKey = CommonUtils.unhex(hexEncodedPrivateKey);
        createAndLinkSimpleKey(spec, privateKey, name, linkedId, log);
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        var helper = super.toStringHelper();
        helper.add("name", name);
        linkedId.ifPresent(s -> helper.add("linkedId", s));
        return helper;
    }
}
