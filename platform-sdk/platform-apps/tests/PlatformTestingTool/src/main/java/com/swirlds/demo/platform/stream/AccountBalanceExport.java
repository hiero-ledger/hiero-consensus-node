// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.stream;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.demo.merkle.map.MapValueData;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.system.Platform;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Signature;

public class AccountBalanceExport {

    static final byte TYPE_SIGNATURE = 3; // the file content signature, should not be hashed
    static final byte TYPE_FILE_HASH = 4; // next 48 bytes are hash384 of content of the file to be signed

    private static final Logger logger = LogManager.getLogger(AccountBalanceExport.class);
    private static final String lineSperator = "line.separator";

    private final long exportPeriodSec;

    private Instant previousTimestamp = null;

    public AccountBalanceExport(final long exportPeriodSec) {
        this.exportPeriodSec = exportPeriodSec;
    }

    /**
     * This method is called in HGCAppMain.newSignedState().
     * Return true when previousTimestamp is not null, and previousTimestamp and consensusTimestamp are in different
     * exportPeriod, for example:
     * If ACCOUNT_BALANCE_EXPORT_PERIOD_MINUTES is set to be 10, and
     * previousTimestamp is yyyy-MM-ddT12:01:00.0Z,
     * (1) when consensusTimestamp is yyyy-MM-ddT12:09:00.0Z, return false;
     * (2) when consensusTimestamp is yyyy-MM-ddT12:10:00.0Z, return true;
     *
     * @param consensusTimestamp
     * @return
     */
    public boolean timeToExport(final Instant consensusTimestamp) {
        if (previousTimestamp != null
                && consensusTimestamp.getEpochSecond() / exportPeriodSec
                        != previousTimestamp.getEpochSecond() / exportPeriodSec) {
            previousTimestamp = consensusTimestamp;
            return true;
        }
        previousTimestamp = consensusTimestamp;
        return false;
    }

    /**
     * This method is invoked during start up and executed based upon the configuration settings. It exports all the
     * existing accounts balance and write it in a file
     */
    public String exportAccountsBalanceCSVFormat(
            final PlatformTestingToolState state, final Instant consensusTimestamp, final Path exportDir) {
        // get the export path from Properties
        logger.debug("exportAccountsBalanceCSVFormat called. {}", consensusTimestamp);
        final MerkleMap<MapKey, MapValueData> accountMap = state.getStateMap().getMap();

        try {
            Files.createDirectories(exportDir);
        } catch (IOException ex) {
            logger.error(EXCEPTION.getMarker(), "{} doesn't exist and cannot be created", exportDir);
            throw new IllegalStateException(ex);
        }

        String fileName = consensusTimestamp + "_Balances.csv";
        fileName = fileName.replace(":", "_");

        final Path exportFile = exportDir.resolve(fileName);
        final List<ExportAccountObject> acctObjList = new ArrayList<>();

        if (logger.isDebugEnabled()) {
            logger.debug("Size of accountMap :: {}", accountMap.size());
        }

        accountMap.forEachNode((final MerkleNode node) -> {
            if (node != null && node.getClassId() == MapValueData.CLASS_ID) {

                final MapValueData currMv = node.cast();
                final MapKey currKey = currMv.getKey();

                final ExportAccountObject exAccObj = new ExportAccountObject(
                        currKey.getShardId(), currKey.getRealmId(), currKey.getAccountId(), currMv.getBalance());

                acctObjList.add(exAccObj);
            }
        });

        Collections.sort(acctObjList, new Comparator<ExportAccountObject>() {
            @Override
            public int compare(ExportAccountObject o1, ExportAccountObject o2) {
                return (int) (o1.getAccountNum() - o2.getAccountNum());
            }
        });

        try (FileWriter file = new FileWriter(exportFile.toFile())) {
            file.write("TimeStamp:");
            file.write(consensusTimestamp.toString());
            file.write(System.getProperty(lineSperator));
            file.write("shardNum,realmNum,accountNum,balance");
            file.write(System.getProperty(lineSperator));

            for (ExportAccountObject exAcctObj : acctObjList) {
                file.write(getAccountData(exAcctObj));
            }

            if (logger.isDebugEnabled()) {
                logger.debug("periodic export of account data completed :: {}", fileName);
            }
        } catch (IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Exception occurred while Exporting Accounts to File.. continuing without saving!! {}",
                    e.getMessage());
        }

        return exportFile.toAbsolutePath().toString();
    }

    /**
     * method to get single account data in csv format
     *
     * @param exportAcctObj
     * @return
     */
    private static String getAccountData(ExportAccountObject exportAcctObj) {
        StringBuilder accountData = new StringBuilder();
        accountData
                .append(exportAcctObj.getShardNum())
                .append(",")
                .append(exportAcctObj.getRealmNum())
                .append(",")
                .append(exportAcctObj.getAccountNum())
                .append(",")
                .append(exportAcctObj.getBalance())
                .append(System.getProperty(lineSperator));
        return accountData.toString();
    }

    /**
     * Calculate SHA384 hash of a binary file
     *
     * @param fileName
     * 		file name
     * @return byte array of hash value
     */
    public static byte[] getFileHash(String fileName) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-384");

            byte[] array = new byte[0];
            try {
                array = Files.readAllBytes(Paths.get(fileName));
            } catch (IOException e) {
                logger.error(EXCEPTION.getMarker(), "Exception ", e);
            }
            byte[] fileHash = md.digest(array);
            return fileHash;

        } catch (NoSuchAlgorithmException e) {
            logger.error(EXCEPTION.getMarker(), "Exception ", e);
            return null;
        }
    }

    public void signAccountBalanceFile(final Platform platform, final String balanceFileName) {
        byte[] fileHash = getFileHash(balanceFileName);
        Signature signature = platform.sign(fileHash);

        final String sigFileName = generateSigFile(balanceFileName, signature.getBytes(), fileHash);
        if (sigFileName != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Generated signature file for {}", balanceFileName);
            }
        }
    }

    /**
     * Create a signature file for a RecordStream/AccountBalance file;
     * This signature file contains the Hash of the file to be signed, and a signature signed by the node's Key
     *
     * @param fileName       the name of the file to be signed
     * @param signatureBytes the signature bytes
     * @param fileHash       the hash of the file to be signed
     */
    private static String generateSigFile(final String fileName, final Bytes signatureBytes, byte[] fileHash) {
        final byte[] signature = signatureBytes.toByteArray();
        try {
            String newFileName = fileName + "_sig";

            // append signature
            try (FileOutputStream output = new FileOutputStream(newFileName, false)) {
                output.write(TYPE_FILE_HASH);
                output.write(fileHash);
                output.write(TYPE_SIGNATURE);
                output.write(ByteBuffer.allocate(4).putInt(signature.length).array());
                output.write(signature);
                return newFileName;
            }
        } catch (IOException e) {
            final String message =
                    "generateSigFile :: Fail to generate signature file for %s. Exception:".formatted(fileName);
            logger.error(EXCEPTION.getMarker(), message, e);
            return null;
        }
    }
}
