// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto.batch;

import static com.hedera.services.bdd.junit.TestTags.ISOLATED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(ISOLATED)
public class IsolatedSuite {
    @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    final Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationsDisabled() {
        return CryptoBatchIsolatedOps.autoAccountCreationsUnlimitedAssociationsDisabled();
    }
}

