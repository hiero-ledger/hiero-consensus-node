// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.utilities;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public final class MerkleDbFileUtils {
    private MerkleDbFileUtils() {}

    /**
     * Completely read all data available from a fileChannel until either an EOF is reached or until dstBuffer is full.
     * <p>
     * FileChannel's position is updated as well as ByteBuffer's position.
     * See also for additional details: {@link ReadableByteChannel#read(ByteBuffer)}
     *
     * @param fileChannel
     * 		the FileChannel to read from.
     * @param dstBuffer
     * 		the buffer to store the read bytes in.
     * @return the total number of bytes read.
     * @throws IOException
     * 		if an exception occurs while reading.
     */
    public static int completelyRead(final ReadableByteChannel fileChannel, final ByteBuffer dstBuffer)
            throws IOException {
        int totalBytesRead = 0;
        while (dstBuffer.hasRemaining()) {
            final int bytesRead = fileChannel.read(dstBuffer);
            if (bytesRead < 0) {
                // Reached EOF
                break;
            }
            totalBytesRead += bytesRead;
        }
        return totalBytesRead;
    }

    /**
     * Reads the given number of bytes from a fileChannel into a ByteBuffer. Returned ByteBuffer's position is rewound.
     * @param fileChannel the FileChannel to read from.
     * @param bytesToRead the number of bytes to read.
     * @return a ByteBuffer containing the read bytes.
     * @throws IOException if an exception occurs while reading.
     */
    public static ByteBuffer readFromFileChannel(final FileChannel fileChannel, final int bytesToRead)
            throws IOException {
        final ByteBuffer headerBuffer = ByteBuffer.allocate(bytesToRead);
        if (completelyRead(fileChannel, headerBuffer) != bytesToRead) {
            throw new IOException("Failed to read " + bytesToRead + " bytes from file channel " + fileChannel);
        }
        headerBuffer.rewind();
        return headerBuffer;
    }

    /**
     * Completely read all data available from a fileChannel until either an EOF is reached or until dstBuffer is full.
     * <p>
     * FileChannel's position is unchanged. ByteBuffer's position is updated.
     * See also: {@link FileChannel#read(ByteBuffer, long)}
     *
     * @param fileChannel
     * 		the FileChannel to read from.
     * @param dstBuffer
     * 		the buffer to store the read bytes in.
     * @param startPosition
     * 		the starting position in the file to start reading from.
     * @return the total number of bytes read.
     * @throws IOException
     * 		if an exception occurs while reading.
     */
    public static int completelyRead(
            final FileChannel fileChannel, final ByteBuffer dstBuffer, final long startPosition) throws IOException {
        int totalBytesRead = 0;
        while (dstBuffer.hasRemaining()) {
            final int bytesRead = fileChannel.read(dstBuffer, startPosition + totalBytesRead);
            if (bytesRead < 0) {
                // Reached EOF
                break;
            }
            totalBytesRead += bytesRead;
        }
        return totalBytesRead;
    }

    /**
     * Completely write out all data from the provided ByteBuffer.
     * <p>
     * FileChannel's position is updated as well as ByteBuffer's position.
     * See also for additional details: {@link WritableByteChannel#write(ByteBuffer)}
     *
     * @param fileChannel
     * 		the FileChannel to write to.
     * @param srcBuffer
     * 		the buffer containing the bytes to write out.
     * @return the total number of bytes written
     * @throws IOException
     * 		if an exception occurs while writing.
     */
    public static int completelyWrite(final WritableByteChannel fileChannel, final ByteBuffer srcBuffer)
            throws IOException {
        int totalBytesWritten = 0;
        while (srcBuffer.hasRemaining()) {
            totalBytesWritten += fileChannel.write(srcBuffer);
        }
        return totalBytesWritten;
    }

    /**
     * Completely write out all data from the provided ByteBuffer to the given position.
     * <p>
     * FileChannel's position is unchanged. ByteBuffer's position is updated.
     * See also: {@link FileChannel#write(ByteBuffer, long)}
     *
     * @param fileChannel
     * 		the FileChannel to write to.
     * @param srcBuffer
     * 		the buffer containing the bytes to write out.
     * @param startPosition the starting position in the file channel to start writing to.
     * @return the total number of bytes written
     * @throws IOException
     * 		if an exception occurs while writing.
     */
    public static int completelyWrite(
            final FileChannel fileChannel, final ByteBuffer srcBuffer, final long startPosition) throws IOException {
        int totalBytesWritten = 0;
        while (srcBuffer.hasRemaining()) {
            totalBytesWritten += fileChannel.write(srcBuffer, startPosition + totalBytesWritten);
        }
        return totalBytesWritten;
    }

    /**
     * Completely transfer all data from srcChannel to dstChannel.
     * <p>
     * dstChannel's position is unchanged. srcChannel's position is updated if it has a position.
     * See also: {@link FileChannel#transferFrom(ReadableByteChannel, long, long)}
     *
     * @param dstChannel
     * 		the destination channel to transfer data to.
     * @param srcChannel
     * 		the source channel to transfer data from.
     * @param dstPosition
     * 		the absolute byte position in dstChannel in which to start writing data to.
     * @param maxBytesToTransfer
     * 		maximum number of bytes to transfer.
     * @return the total bytes transferred.
     * @throws IOException
     * 		if an exception occurs while trying to transfer data.
     */
    public static long completelyTransferFrom(
            final FileChannel dstChannel,
            final ReadableByteChannel srcChannel,
            final long dstPosition,
            final long maxBytesToTransfer)
            throws IOException {
        long totalBytesTransferred = 0;
        while (totalBytesTransferred < maxBytesToTransfer) {
            final long bytesTransferred = dstChannel.transferFrom(
                    srcChannel, dstPosition + totalBytesTransferred, maxBytesToTransfer - totalBytesTransferred);
            // Avoid using fileChannel.size because it requires a fstat64 operation. Instead, break when no bytes
            // transferred and assume reached the end.
            if (bytesTransferred <= 0) {
                break;
            }
            totalBytesTransferred += bytesTransferred;
        }
        return totalBytesTransferred;
    }
}
