// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.Set;

@ConfigData("contracts")
public record ContractsConfig(
        @ConfigProperty(defaultValue = "1062787,1461860") Set<Long> permittedDelegateCallers,
        @ConfigProperty(value = "keys.legacyActivations", defaultValue = "1058134by[1062784]")
                String keysLegacyActivations,
        @ConfigProperty(value = "localCall.estRetBytes", defaultValue = "4096") @NetworkProperty
                int localCallEstRetBytes,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean allowCreate2,
        @ConfigProperty(value = "nonces.externalization.enabled", defaultValue = "true") @NetworkProperty
                boolean noncesExternalizationEnabled,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean enforceCreationThrottle,
        @ConfigProperty(defaultValue = "15000000") @NetworkProperty long maxGasPerSec,
        @ConfigProperty(defaultValue = "15000000") @NetworkProperty long maxGasPerSecBackend,
        @ConfigProperty(defaultValue = "500000000") @NetworkProperty long maxOpsDuration,
        @ConfigProperty(defaultValue = "1") @NetworkProperty int gasThrottleBurstSeconds,
        @ConfigProperty(value = "maxKvPairs.aggregate", defaultValue = "500000000") @NetworkProperty
                long maxKvPairsAggregate,
        @ConfigProperty(value = "maxKvPairs.individual", defaultValue = "16384000") @NetworkProperty
                int maxKvPairsIndividual,
        @ConfigProperty(defaultValue = "5000000") @NetworkProperty long maxNumber,
        // CHAINID returns 295 (0x0127) for mainnet, 296 (0x0128) for testnet, and 297 (0x0129) for previewnet.
        // c.f. https://hips.hedera.com/hip/hip-26 for reference
        @ConfigProperty(defaultValue = "295") @NetworkProperty int chainId,
        @ConfigProperty(defaultValue = "CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE,CONTRACT_ACTION") @NetworkProperty
                Set<SidecarType> sidecars,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean sidecarValidationEnabled,
        @ConfigProperty(value = "throttle.throttleByGas", defaultValue = "true") @NetworkProperty
                boolean throttleThrottleByGas,
        @ConfigProperty(value = "throttle.throttleByOpsDuration", defaultValue = "false") @NetworkProperty
                boolean throttleThrottleByOpsDuration,
        @ConfigProperty(defaultValue = "20") @NetworkProperty int maxRefundPercentOfGasLimit,
        @ConfigProperty(value = "precompile.exchangeRateGasCost", defaultValue = "100") @NetworkProperty
                long precompileExchangeRateGasCost,
        @ConfigProperty(value = "precompile.htsDefaultGasCost", defaultValue = "10000") @NetworkProperty
                long precompileHtsDefaultGasCost,
        @ConfigProperty(value = "precompile.atomicCryptoTransfer.enabled", defaultValue = "true") @NetworkProperty
                boolean precompileAtomicCryptoTransferEnabled,
        @ConfigProperty(value = "precompile.disabled", defaultValue = "") @NetworkProperty
                Set<Integer> disabledPrecompiles,
        @ConfigProperty(value = "systemContract.accountService.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractAccountServiceEnabled,
        @ConfigProperty(value = "systemContract.scheduleService.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractScheduleServiceEnabled,
        @ConfigProperty(value = "systemContract.scheduleService.signSchedule.enabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractSignScheduleEnabled,
        @ConfigProperty(
                        value = "systemContract.scheduleService.signSchedule.from.contract.enabled",
                        defaultValue = "true")
                @NetworkProperty
                boolean systemContractSignScheduleFromContractEnabled,
        @ConfigProperty(value = "systemContract.scheduleService.authorizeSchedule.enabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractAuthorizeScheduleEnabled,
        @ConfigProperty(value = "systemContract.scheduleService.scheduleNative.enabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractScheduleNativeEnabled,
        @ConfigProperty(value = "systemContract.accountService.isAuthorizedRawEnabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractAccountServiceIsAuthorizedRawEnabled,
        @ConfigProperty(value = "systemContract.accountService.isAuthorizedEnabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractAccountServiceIsAuthorizedEnabled,
        @ConfigProperty(value = "systemContract.updateCustomFees.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractUpdateCustomFeesEnabled,
        @ConfigProperty(value = "systemContract.airdropTokens.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractAirdropTokensEnabled,
        @ConfigProperty(value = "systemContract.cancelAirdrops.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractCancelAirdropsEnabled,
        @ConfigProperty(value = "systemContract.claimAirdrops.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractClaimAirdropsEnabled,
        @ConfigProperty(value = "systemContract.rejectTokens.enabled", defaultValue = "true") @NetworkProperty
                boolean systemContractRejectTokensEnabled,
        @ConfigProperty(value = "systemContract.setUnlimitedAutoAssociations.enabled", defaultValue = "true")
                @NetworkProperty
                boolean systemContractSetUnlimitedAutoAssociationsEnabled,
        @ConfigProperty(value = "systemContract.hts.addresses", defaultValue = "359") // 359 = 0x167, 364 = 0x16C
                Set<Long> callableHTSAddresses,
        @ConfigProperty(value = "evm.ethTransaction.zeroHapiFees.enabled", defaultValue = "true") @NetworkProperty
                boolean evmEthTransactionZeroHapiFeesEnabled,
        @ConfigProperty(value = "evm.allowCallsToNonContractAccounts", defaultValue = "true") @NetworkProperty
                boolean evmAllowCallsToNonContractAccounts,
        @ConfigProperty(value = "evm.chargeGasOnEvmHandleException", defaultValue = "true") @NetworkProperty
                boolean chargeGasOnEvmHandleException,
        @ConfigProperty(value = "evm.nonExtantContractsFail", defaultValue = "0") @NetworkProperty
                Set<Long> evmNonExtantContractsFail,
        @ConfigProperty(value = "evm.version", defaultValue = "v0.51") @NetworkProperty String evmVersion,
        @ConfigProperty(value = "metrics.smartContract.primary.enabled", defaultValue = "true") @NetworkProperty
                boolean metricsSmartContractPrimaryEnabled,
        @ConfigProperty(value = "metrics.smartContract.secondary.enabled", defaultValue = "true") @NetworkProperty
                boolean metricsSmartContractSecondaryEnabled) {}
