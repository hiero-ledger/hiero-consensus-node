// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.transaction.handler;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.demo.platform.fs.stresstest.proto.CreateSmartContract;
import com.swirlds.demo.platform.fs.stresstest.proto.VirtualMerkleTransaction;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapKeySerializer;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValue;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode.SmartContractByteCodeMapValueSerializer;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapKey;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapKeySerializer;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapValue;
import com.swirlds.demo.virtualmerkle.map.smartcontracts.data.SmartContractMapValueSerializer;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import java.time.Instant;
import org.hiero.consensus.model.crypto.DigestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class VirtualMerkleTransactionHandlerTest {

    private static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();
    private static VirtualMap<SmartContractMapKey, SmartContractMapValue> smartContract;
    private static VirtualMap<SmartContractByteCodeMapKey, SmartContractByteCodeMapValue> smartContractByteCodeVM;

    @BeforeAll
    public static void beforeAll() {
        // Should storage dir be set to a certain value?

        final long maximumNumberOfKeyValuePairsCreation = 28750;
        final SmartContractMapKeySerializer keySerializer = new SmartContractMapKeySerializer();
        final SmartContractMapValueSerializer valueSerializer = new SmartContractMapValueSerializer();
        final MerkleDbTableConfig tableConfig =
                new MerkleDbTableConfig((short) 1, DigestType.SHA_384, maximumNumberOfKeyValuePairsCreation, 0);
        final MerkleDbDataSourceBuilder dataSourceBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);

        smartContract =
                new VirtualMap<>("smartContracts", keySerializer, valueSerializer, dataSourceBuilder, CONFIGURATION);

        final long totalSmartContractCreations = 23;

        final SmartContractByteCodeMapKeySerializer keySerializer2 = new SmartContractByteCodeMapKeySerializer();
        final SmartContractByteCodeMapValueSerializer valueSerializer2 = new SmartContractByteCodeMapValueSerializer();
        final MerkleDbTableConfig tableConfig2 =
                new MerkleDbTableConfig((short) 1, DigestType.SHA_384, totalSmartContractCreations, 0);
        final MerkleDbDataSourceBuilder dataSourceBuilder2 = new MerkleDbDataSourceBuilder(tableConfig2, CONFIGURATION);

        smartContractByteCodeVM = new VirtualMap<>(
                "smartContractByteCode", keySerializer2, valueSerializer2, dataSourceBuilder2, CONFIGURATION);
    }

    private VirtualMerkleTransaction buildCreateSmartContractTransaction(
            final long id, final long totalKeyValuePairs, final int byteCodeSize) {
        return VirtualMerkleTransaction.newBuilder()
                .setSmartContract(CreateSmartContract.newBuilder()
                        .setContractId(id)
                        .setTotalValuePairs(totalKeyValuePairs)
                        .setByteCodeSize(byteCodeSize))
                .setSampled(false)
                .build();
    }

    @Test
    public void ISSTest() {

        VirtualMerkleTransactionHandler.handle(
                Instant.now(),
                buildCreateSmartContractTransaction(46, 158, 2897),
                null,
                null,
                smartContract,
                smartContractByteCodeVM);

        VirtualMerkleTransactionHandler.handle(
                Instant.now(),
                buildCreateSmartContractTransaction(0, 198, 2873),
                null,
                null,
                smartContract,
                smartContractByteCodeVM);
        /*
        2021-11-22 23:45:48.684 83       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 189, byte code size 2303 for sc with
        id 69.
        2021-11-22 23:45:48.686 84       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 150, byte code size 2491 for sc with
        id 47.
        2021-11-22 23:45:48.688 85       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 109, byte code size 4624 for sc with
        id 48.
        2021-11-22 23:45:48.690 86       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 161, byte code size 3838 for sc with
        id 49.
        2021-11-22 23:45:48.692 87       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 141, byte code size 2330 for sc with
        id 50.
        2021-11-22 23:45:48.694 88       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 170, byte code size 3527 for sc with
        id 51.
        2021-11-22 23:45:48.695 89       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 228, byte code size 2567 for sc with
        id 52.
        2021-11-22 23:45:48.697 90       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 104, byte code size 4161 for sc with
        id 53.
        2021-11-22 23:45:48.698 91       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 147, byte code size 2662 for sc with
        id 54.
        2021-11-22 23:45:48.700 92       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 181, byte code size 2555 for sc with
        id 55.
        2021-11-22 23:45:48.701 93       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 158, byte code size 4264 for sc with
        id 56.
        2021-11-22 23:45:48.704 94       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 213, byte code size 3951 for sc with
        id 57.
        2021-11-22 23:45:48.706 95       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 167, byte code size 3694 for sc with
        id 58.
        2021-11-22 23:45:48.707 96       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 226, byte code size 3254 for sc with
        id 59.
        2021-11-22 23:45:48.709 97       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 249, byte code size 2810 for sc with
        id 1.
        2021-11-22 23:45:48.711 98       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 114, byte code size 2224 for sc with
        id 2.
        2021-11-22 23:45:48.713 99       INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 220, byte code size 2089 for sc with
        id 3.
        2021-11-22 23:45:48.715 100      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 235, byte code size 2463 for sc with
        id 4.
        2021-11-22 23:45:48.716 101      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 208, byte code size 4387 for sc with
        id 5.
        2021-11-22 23:45:48.718 102      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 142, byte code size 3467 for sc with
        id 6.
        2021-11-22 23:45:48.719 103      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 101, byte code size 2840 for sc with
        id 7.
        2021-11-22 23:45:48.720 104      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 205, byte code size 4455 for sc with
        id 8.
        2021-11-22 23:45:48.722 105      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 194, byte code size 2954 for sc with
        id 9.
        2021-11-22 23:45:48.723 106      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 196, byte code size 2647 for sc with
        id 10.
        2021-11-22 23:45:48.724 107      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 171, byte code size 4759 for sc with
        id 11.
        2021-11-22 23:45:48.726 108      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 232, byte code size 4715 for sc with
        id 12.
        2021-11-22 23:45:48.727 109      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 170, byte code size 3305 for sc with
        id 13.
        2021-11-22 23:45:48.854 110      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 104, byte code size 3845 for sc with
        id 70.
        2021-11-22 23:45:48.857 111      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 196, byte code size 4742 for sc with
        id 71.
        2021-11-22 23:45:48.861 112      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 118, byte code size 4800 for sc with
        id 72.
        2021-11-22 23:45:48.863 113      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 101, byte code size 4925 for sc with
        id 73.
        2021-11-22 23:45:48.865 114      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 105, byte code size 2339 for sc with
        id 74.
        2021-11-22 23:45:48.866 115      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 154, byte code size 4832 for sc with
        id 75.
        2021-11-22 23:45:48.870 116      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 223, byte code size 2158 for sc with
        id 76.
        2021-11-22 23:45:48.872 117      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 159, byte code size 2478 for sc with
        id 77.
        2021-11-22 23:45:48.874 118      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 247, byte code size 2077 for sc with
        id 78.
        2021-11-22 23:45:48.877 119      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 215, byte code size 4523 for sc with
        id 79.
        2021-11-22 23:45:48.878 120      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 187, byte code size 2064 for sc with
        id 80.
        2021-11-22 23:45:48.880 121      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 124, byte code size 3190 for sc with
        id 81.
        2021-11-22 23:45:48.881 122      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 173, byte code size 3854 for sc with
        id 82.
        2021-11-22 23:45:48.884 123      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 193, byte code size 2850 for sc with
        id 83.
        2021-11-22 23:45:48.887 124      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 165, byte code size 2680 for sc with
        id 14.
        2021-11-22 23:45:48.890 125      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 120, byte code size 4843 for sc with
        id 15.
        2021-11-22 23:45:48.893 126      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 165, byte code size 2169 for sc with
        id 16.
        2021-11-22 23:45:48.895 127      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 189, byte code size 4043 for sc with
        id 17.
        2021-11-22 23:45:48.896 128      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 159, byte code size 2145 for sc with
        id 18.
        2021-11-22 23:45:48.902 129      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 247, byte code size 2271 for sc with
        id 19.
        2021-11-22 23:45:48.904 130      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 194, byte code size 2807 for sc with
        id 20.
        2021-11-22 23:45:48.905 131      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 136, byte code size 2167 for sc with
        id 21.
        2021-11-22 23:45:48.906 132      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 167, byte code size 4564 for sc with
        id 22.
        2021-11-22 23:45:48.908 133      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 191, byte code size 4987 for sc with
        id 60.
        2021-11-22 23:45:48.910 134      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 241, byte code size 3947 for sc with
        id 61.
        2021-11-22 23:45:48.911 135      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 174, byte code size 4127 for sc with
        id 62.
        2021-11-22 23:45:48.913 136      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 236, byte code size 2610 for sc with
        id 63.
        2021-11-22 23:45:48.914 137      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 132, byte code size 3630 for sc with
        id 64.
        2021-11-22 23:45:48.916 138      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 240, byte code size 3898 for sc with
        id 65.
        2021-11-22 23:45:48.918 139      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 195, byte code size 3474 for sc with
        id 66.
        2021-11-22 23:45:48.919 140      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 247, byte code size 2637 for sc with
        id 67.
        2021-11-22 23:45:48.920 141      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 166, byte code size 4785 for sc with
        id 68.
        2021-11-22 23:45:48.934 142      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 67.
        2021-11-22 23:45:48.951 143      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 52.
        2021-11-22 23:45:48.960 144      INFO  STATE_TO_DISK    <<platform-core: objectFileManager 0 #0>>
        SignedStateFileManager: Started writing 'Signed state for round 2' to disk
        2021-11-22 23:45:48.967 145      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 59.
        2021-11-22 23:45:48.981 146      DEBUG SNAPSHOT_MANAGER <<platform-core: objectFileManager 0 #0>>
        SnapshotManager: SnapshotManager: Successfully queued snapshot request [taskType='BACKUP', applicationName='com
        .swirlds.demo.platform.PlatformTestingToolMain', worldId='123', nodeId=0, roundNumber=2,
        snapshotId=00000003-0000005F-1, timeStarted=2021-11-22T23:45:48.964400226Z, timeCompleted=null, complete=false,
         error=false ]
        2021-11-22 23:45:48.983 147      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 60.
        2021-11-22 23:45:49.005 148      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 56.
        2021-11-22 23:45:49.015 149      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 19.
        2021-11-22 23:45:49.026 150      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 13.
        2021-11-22 23:45:49.036 151      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 7.
        2021-11-22 23:45:49.048 152      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 5.
        2021-11-22 23:45:49.057 153      INFO  STATE_TO_DISK    <<platform-core: objectFileManager 0 #0>>
        SignedStateFileManager: Done writing saved state with HashEventsCons
        9337764f5b36af7fc46f8c2c5a4dd2298d4d7ee1818196c1b6f7c1047ec173d359d40092597f8b676bbd64920886f22d, starting with
         local events
        2021-11-22 23:45:49.059 154      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 22.
        2021-11-22 23:45:49.061 155      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 109, byte code size 2610 for sc with
        id 84.
        2021-11-22 23:45:49.064 156      INFO  STATE_TO_DISK    <<platform-core: objectFileManager 0 #0>>
        SignedStateFileManager: 'Signed state for round 2' waiting for snapshotTask
        2021-11-22 23:45:49.064 157      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 202, byte code size 2494 for sc with
        id 85.
        2021-11-22 23:45:49.067 158      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 230, byte code size 3568 for sc with
        id 86.
        2021-11-22 23:45:49.068 159      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 141, byte code size 4833 for sc with
        id 87.
        2021-11-22 23:45:49.070 160      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 178, byte code size 2107 for sc with
        id 88.
        2021-11-22 23:45:49.072 161      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 138, byte code size 4514 for sc with
        id 89.
        2021-11-22 23:45:49.073 162      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 150, byte code size 4358 for sc with
        id 90.
        2021-11-22 23:45:49.074 163      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Created smart contract. Key value pairs 105, byte code size 3882 for sc with
        id 91.
        2021-11-22 23:45:49.085 164      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 80.
        2021-11-22 23:45:49.097 165      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 78.
        2021-11-22 23:45:49.137 166      INFO  DEMO_INFO        <<event-flow: thread-cons 0 #0>>
        VirtualMerkleTransactionHandler: Handled transaction with ops 1000 1750 1200 and id 84.
        */
    }
}
