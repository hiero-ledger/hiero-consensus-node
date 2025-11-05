// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mintcontrol;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.RESPONSE_CODE_BOOL;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.RESPONSE_CODE_UINT256;
import static com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import static com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.contractIDToBesuAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * Translates FiatTokenV1 mint control management calls to the HTS system contract.
 * These selectors are recognized when called on a token address (via long-zero address)
 * that has a mint_control_hook_id configured.
 */
@Singleton
public class MintControlTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for updateMasterMinter(address) method.
     * Function signature: updateMasterMinter(address)
     * Selector: 0xaa20e1e4
     */
    public static final SystemContractMethod UPDATE_MASTER_MINTER = SystemContractMethod.declare(
                    "updateMasterMinter(address)", RESPONSE_CODE_BOOL)
            .withCategories(Category.MINT_CONTROL);

    /**
     * Selector for minterAllowance(address) method.
     * Function signature: minterAllowance(address)
     * Selector: 0x8a6db9c3
     */
    public static final SystemContractMethod MINTER_ALLOWANCE = SystemContractMethod.declare(
                    "minterAllowance(address)", RESPONSE_CODE_UINT256)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.MINT_CONTROL);

    /**
     * Selector for isMinter(address) method.
     * Function signature: isMinter(address)
     * Selector: 0xaa271e1a
     */
    public static final SystemContractMethod IS_MINTER = SystemContractMethod.declare(
                    "isMinter(address)", RESPONSE_CODE_BOOL)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.MINT_CONTROL);

    /**
     * Selector for configureMinter(address,uint256) method.
     * Function signature: configureMinter(address,uint256)
     * Selector: 0x4e44d956
     */
    public static final SystemContractMethod CONFIGURE_MINTER = SystemContractMethod.declare(
                    "configureMinter(address,uint256)", RESPONSE_CODE_BOOL)
            .withCategories(Category.MINT_CONTROL);

    /**
     * Selector for removeMinter(address) method.
     * Function signature: removeMinter(address)
     * Selector: 0x3092afd5
     */
    public static final SystemContractMethod REMOVE_MINTER = SystemContractMethod.declare(
                    "removeMinter(address)", RESPONSE_CODE_BOOL)
            .withCategories(Category.MINT_CONTROL);

    private final CodeFactory codeFactory;

    /**
     * Constructor for injection.
     *
     * @param systemContractMethodRegistry the system contract method registry
     * @param contractMetrics the contract metrics
     */
    @Inject
    public MintControlTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics,
            @NonNull final CodeFactory codeFactory) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.codeFactory = requireNonNull(codeFactory);
        registerMethods(UPDATE_MASTER_MINTER, MINTER_ALLOWANCE, IS_MINTER, CONFIGURE_MINTER, REMOVE_MINTER);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        // Only match if this is NOT a redirect (i.e., it's a direct call to the token address)
        if (attempt.isRedirect()) {
            return Optional.empty();
        }
        return attempt.isMethod(UPDATE_MASTER_MINTER, MINTER_ALLOWANCE, IS_MINTER, CONFIGURE_MINTER, REMOVE_MINTER);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        // Resolve the target token from msg.to (the long-zero address)
        // For direct calls (not redirects), the token address is the contract ID being called
        final var tokenAddress =
                contractIDToBesuAddress(attempt.nativeOperations().entityIdFactory(), attempt.systemContractID());
        final var token = attempt.linkedToken(tokenAddress);

        return new MintControlManagementCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                token,
                Bytes.wrap(attempt.selector()),
                Bytes.wrap(attempt.inputBytes()),
                codeFactory);
    }
}
