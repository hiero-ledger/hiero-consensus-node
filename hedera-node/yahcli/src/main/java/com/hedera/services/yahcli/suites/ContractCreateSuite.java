// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.suites;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileCreate;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.util.HapiSpecUtils;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

/**
 * Uploads init-code (hex-encoded ASCII bytecode bytes, the standard solc {@code .bin} layout)
 * to a new Hedera file (chunked via FileAppend when needed), then submits a ContractCreate
 * referencing that file. Constructor args, when provided, are set on the transaction body's
 * {@code constructorParameters} field; the Hedera node concatenates them with the file's
 * hex bytecode before decoding to bytes for execution.
 */
public class ContractCreateSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ContractCreateSuite.class);

    private static final String CONTRACT_HANDLE = "yahcliContract";

    private final ConfigManager configManager;
    private final byte[] initCode;
    private final long gas;
    private final long initialBalance;

    @Nullable
    private final String memo;

    @Nullable
    private final Long autoRenewSecs;

    private final boolean immutable;

    @Nullable
    private final byte[] constructorArgs;

    private final AtomicReference<ContractID> createdContractId = new AtomicReference<>();

    @Nullable
    private HapiFileCreate fileCreateOp;

    @Nullable
    private HapiContractCreate contractCreateOp;

    public ContractCreateSuite(
            final ConfigManager configManager,
            final byte[] initCode,
            final long gas,
            final long initialBalance,
            @Nullable final String memo,
            @Nullable final Long autoRenewSecs,
            final boolean immutable,
            @Nullable final byte[] constructorArgs) {
        this.configManager = configManager;
        this.initCode = initCode;
        this.gas = gas;
        this.initialBalance = initialBalance;
        this.memo = memo;
        this.autoRenewSecs = autoRenewSecs;
        this.immutable = immutable;
        this.constructorArgs = constructorArgs;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(deploy());
    }

    final Stream<DynamicTest> deploy() {
        fileCreateOp = new HapiFileCreate(CONTRACT_HANDLE);
        final SpecOperation bytecodeUpload =
                UtilVerbs.updateLargeFile(HapiSuite.DEFAULT_PAYER, CONTRACT_HANDLE, ByteString.copyFrom(initCode));

        contractCreateOp = TxnVerbs.contractCreate(CONTRACT_HANDLE)
                .bytecode(CONTRACT_HANDLE)
                .gas(gas)
                .balance(initialBalance)
                .exposingContractIdTo(createdContractId::set);
        if (constructorArgs != null && constructorArgs.length > 0) {
            final var hexedArgs = CommonUtils.hex(constructorArgs);
            contractCreateOp = contractCreateOp.withExplicitParams(() -> hexedArgs);
        }
        if (memo != null) {
            contractCreateOp = contractCreateOp.entityMemo(memo);
        }
        if (autoRenewSecs != null) {
            contractCreateOp = contractCreateOp.autoRenewSecs(autoRenewSecs);
        }
        if (immutable) {
            contractCreateOp = contractCreateOp.immutable();
        }

        final var spec = new HapiSpec(
                "YahcliContractCreate",
                new MapPropertySource(configManager.asSpecConfig()),
                new SpecOperation[] {fileCreateOp, bytecodeUpload, contractCreateOp});
        return HapiSpecUtils.targeted(spec, configManager);
    }

    @Nullable
    public HapiFileCreate getFileCreateOp() {
        return fileCreateOp;
    }

    @Nullable
    public HapiContractCreate getContractCreateOp() {
        return contractCreateOp;
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Nullable
    public ContractID getCreatedContractId() {
        return createdContractId.get();
    }
}
