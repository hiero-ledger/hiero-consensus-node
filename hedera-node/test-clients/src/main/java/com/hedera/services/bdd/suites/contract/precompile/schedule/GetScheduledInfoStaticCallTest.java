// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asScheduleId;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;


@Tag("SMART_CONTRACT")
@HapiTestLifecycle
public class GetScheduledInfoStaticCallTest {

    private static final String GET_FUNGIBLE_CREATE_TOKEN_INFO = "getFungibleCreateTokenInfo";

    @HapiTest
    @DisplayName("call getScheduledCreateFungibleTokenInfo via staticcall and succeed")
    public Stream<DynamicTest> getScheduledCreateFungibleInfoStaticCall(
            @NonNull @Contract(contract = "HIP756Contract", creationGas = 4_000_000L, isImmutable = true)
                    final SpecContract creator,
            @NonNull @Contract(contract = "GetScheduleInfo", creationGas = 5_000_000) final SpecContract getter,
            @NonNull @Account final SpecAccount treasury,
            @NonNull @Account final SpecAccount autoRenew) {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var scheduleAddress = new AtomicReference<Address>();
            allRunFor(
                    spec,
                    creator.call("scheduleCreateFT", autoRenew, treasury)
                            .gas(1_000_000L)
                            .exposingResultTo(res -> scheduleAddress.set((Address) res[1]))
                            .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));

            final var scheduleId = asScheduleId(spec, scheduleAddress.get());
            spec.registry().saveScheduleId("scheduledCreateFT", scheduleId);

            allRunFor(
                    spec,
                    getter.call(GET_FUNGIBLE_CREATE_TOKEN_INFO, scheduleAddress.get())
                            .gas(100_000L)
                            .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
        }));
    }
}
