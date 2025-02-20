// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification;

import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RecordFileParser {
    private static final Logger log = LogManager.getLogger(RecordFileParser.class);

    private static final byte TYPE_PREV_HASH = 1;
    private static final byte TYPE_RECORD = 2;

    private static final MessageDigest metaDigest;
    private static final MessageDigest contentDigest;

    static {
        try {
            metaDigest = MessageDigest.getInstance("SHA-384");
            contentDigest = MessageDigest.getInstance("SHA-384");
        } catch (Exception fatal) {
            throw new IllegalStateException("Cannot initialize digests!", fatal);
        }
    }

    public static RecordFile parseFrom(File file) {
        FileInputStream stream = null;
        List<TxnHistory> histories = new LinkedList<>();
        byte[] prevHash = null;

        if (!file.exists()) {
            throw new IllegalArgumentException("No such file - " + file);
        }

        try {
            stream = new FileInputStream(file);
            DataInputStream dis = new DataInputStream(stream);

            prevHash = new byte[48];
            int record_format_version = dis.readInt();
            int version = dis.readInt();

            log.debug("File '{}' is: ", file);
            log.debug("  -> Record format v{}", record_format_version);
            log.debug("  -> HAPI protocol v{}", version);

            while (dis.available() != 0) {
                try {
                    byte typeDelimiter = dis.readByte();

                    switch (typeDelimiter) {
                        case TYPE_PREV_HASH:
                            dis.read(prevHash);
                            break;
                        case TYPE_RECORD:
                            int n = dis.readInt();
                            byte[] buffer = new byte[n];
                            dis.readFully(buffer);
                            Transaction signedTxn = Transaction.parseFrom(buffer);

                            n = dis.readInt();
                            buffer = new byte[n];
                            dis.readFully(buffer);
                            TransactionRecord record = TransactionRecord.parseFrom(buffer);

                            /* We don't (currently) validate any of these histories, so save the memory here;
                            it can cause OOM in CircleCI when validating records after a long umbrella run. */
                            //							histories.add(new TxnHistory(signedTxn, record));

                            break;
                        default:
                            log.warn("Record file '{}' contained unrecognized delimiter |{}|", file, typeDelimiter);
                    }
                } catch (Exception e) {
                    log.warn("Problem parsing record file '{}'", file);
                    break;
                }
            }

            metaDigest.reset();
            contentDigest.reset();
            byte[] everything = Files.readAllBytes(file.toPath());
            byte[] preface = Arrays.copyOfRange(everything, 0, 57);
            byte[] bodyHash = contentDigest.digest(Arrays.copyOfRange(everything, 57, everything.length));
            metaDigest.update(ArrayUtils.addAll(preface, bodyHash));
        } catch (FileNotFoundException e) {
            throw new IllegalStateException();
        } catch (IOException e) {
            log.error("Problem reading record file '{}'!", file, e);
        } catch (Exception e) {
            log.error("Problem parsing record file '{}'!", file, e);
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                log.error("Exception in closing stream for '{}'!", file, ex);
            }
        }

        return new RecordFile(prevHash, metaDigest.digest(), histories);
    }

    private static byte[] asBytes(int number) {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(number);
        return b.array();
    }

    public static class RecordFile {
        private final byte[] prevHash;
        private final byte[] thisHash;
        private final List<TxnHistory> txnHistories;

        RecordFile(byte[] prevHash, byte[] thisHash, List<TxnHistory> txnHistories) {
            this.prevHash = prevHash;
            this.thisHash = thisHash;
            this.txnHistories = txnHistories;
        }

        public byte[] getPrevHash() {
            return prevHash;
        }

        public byte[] getThisHash() {
            return thisHash;
        }

        public List<TxnHistory> getTxnHistories() {
            return txnHistories;
        }
    }

    public static class TxnHistory {
        private final Transaction signedTxn;
        private final TransactionRecord record;

        public TxnHistory(Transaction signedTxn, TransactionRecord record) {
            this.signedTxn = signedTxn;
            this.record = record;
        }

        public Transaction getSignedTxn() {
            return signedTxn;
        }

        public TransactionRecord getRecord() {
            return record;
        }
    }
}
