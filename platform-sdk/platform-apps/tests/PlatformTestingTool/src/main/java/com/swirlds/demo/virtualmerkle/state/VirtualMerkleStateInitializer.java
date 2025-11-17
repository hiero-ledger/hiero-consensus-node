// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.state;

import static com.swirlds.demo.platform.PlatformTestingToolMain.CONFIGURATION;

import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.demo.platform.UnsafeMutablePTTStateAccessor;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.system.Platform;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/**
 * This is a helper class to initialize the part of the state that corresponds to virtual map tests.
 */
public final class VirtualMerkleStateInitializer {

    private static final MerkleDbConfig MERKLE_DB_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    /*
     * This capacity is somewhat arbitrary, but it is a reasonable limit for all the PTT tests that we have.
     * An exact number would have to be calculated like this:
     * (number of nodes) * (number of TYPE_VIRTUAL_MERKLE_CREATE config entries) * (amount specified in each config entry)
     *
     * Even though it's possible to calculate the exact number, it's not necessary for the PTT tests, so we just use the constant.
     */
    private static final Integer MAX_LIST_CAPACITY = 1_000_000;

    private static final Logger logger = LogManager.getLogger(VirtualMerkleStateInitializer.class);
    private static final Marker LOGM_DEMO_INFO = LogMarker.DEMO_INFO.getMarker();

    private VirtualMerkleStateInitializer() {}

    /**
     * This method initialize all the data structures needed during the virtual merkle tests.
     *
     * @param platform
     * 		The platform where this method is being called.
     * @param nodeId
     * 		The id of the current node.
     * @param virtualMerkleConfig
     */
    public static void initStateChildren(
            final Platform platform, final long nodeId, final VirtualMerkleConfig virtualMerkleConfig) {

        try (final AutoCloseableWrapper<PlatformTestingToolState> wrapper =
                UnsafeMutablePTTStateAccessor.getInstance().getUnsafeMutableState(platform.getSelfId())) {

            final PlatformTestingToolState state = wrapper.get();
            logger.info(LOGM_DEMO_INFO, "State = {}", state);

            final long totalAccounts = virtualMerkleConfig.getTotalAccountCreations();
            logger.info(LOGM_DEMO_INFO, "total accounts = {}", totalAccounts);
            if (state.getVirtualMap() == null && totalAccounts > 0) {
                logger.info(LOGM_DEMO_INFO, "Creating virtualmap for {} accounts.", totalAccounts);
                final VirtualMap virtualMap = createAccountsVM();
                logger.info(LOGM_DEMO_INFO, "accounts VM = {}, DS = {}", virtualMap, virtualMap.getDataSource());
                virtualMap.registerMetrics(platform.getContext().getMetrics());
                state.setVirtualMap(virtualMap);
            }

            final long maximumNumberOfKeyValuePairs = virtualMerkleConfig.getMaximumNumberOfKeyValuePairsCreation();
            logger.info(LOGM_DEMO_INFO, "max KV pairs = {}", maximumNumberOfKeyValuePairs);
            if (state.getVirtualMapForSmartContracts() == null && maximumNumberOfKeyValuePairs > 0) {
                logger.info(
                        LOGM_DEMO_INFO,
                        "Creating virtualmap for max {} key value pairs.",
                        maximumNumberOfKeyValuePairs);
                final VirtualMap virtualMap = createSmartContractsVM();
                logger.info(LOGM_DEMO_INFO, "SC VM = {}, DS = {}", virtualMap, virtualMap.getDataSource());
                virtualMap.registerMetrics(platform.getContext().getMetrics());
                state.setVirtualMapForSmartContracts(virtualMap);
            }

            final long totalSmartContracts = virtualMerkleConfig.getTotalSmartContractCreations();
            logger.info(LOGM_DEMO_INFO, "total SC = {}", totalSmartContracts);
            if (state.getVirtualMapForSmartContractsByteCode() == null && totalSmartContracts > 0) {
                logger.info(LOGM_DEMO_INFO, "Creating virtualmap for {} bytecodes.", totalSmartContracts);
                final VirtualMap virtualMap = createSmartContractByteCodeVM();
                logger.info(LOGM_DEMO_INFO, "SCBC VM = {}, DS = {}", virtualMap, virtualMap.getDataSource());
                virtualMap.registerMetrics(platform.getContext().getMetrics());
                state.setVirtualMapForSmartContractsByteCode(virtualMap);
            }
        }
    }

    private static VirtualMap createAccountsVM() {
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(
                CONFIGURATION, MAX_LIST_CAPACITY, MERKLE_DB_CONFIG.hashesRamToDiskThreshold());
        return new VirtualMap("accounts", dsBuilder, CONFIGURATION);
    }

    private static VirtualMap createSmartContractsVM() {
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(
                CONFIGURATION, MAX_LIST_CAPACITY, MERKLE_DB_CONFIG.hashesRamToDiskThreshold());
        return new VirtualMap("smartContracts", dsBuilder, CONFIGURATION);
    }

    private static VirtualMap createSmartContractByteCodeVM() {
        final VirtualDataSourceBuilder dsBuilder = new MerkleDbDataSourceBuilder(
                CONFIGURATION, MAX_LIST_CAPACITY, MERKLE_DB_CONFIG.hashesRamToDiskThreshold());
        return new VirtualMap("smartContractByteCode", dsBuilder, CONFIGURATION);
    }
}
