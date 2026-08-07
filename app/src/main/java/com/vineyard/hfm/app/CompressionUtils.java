package com.vineyard.hfm.app;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

public class CompressionUtils {

    /**
     * Compresses data from an InputStream and writes it to an OutputStream using LZ4.
     *
     * @param in  The InputStream to read raw data from.
     * @param out The OutputStream to write compressed data to.
     * @throws IOException if an I/O error occurs.
     */
    public static void compress(InputStream in, OutputStream out) throws IOException {
        // This simplified implementation is more robust for the LZ4 library.
        // It ensures that the close() method, which writes the critical end-of-stream markers,
        // is only called after the read/write loop completes successfully.
        LZ4FrameOutputStream lz4Out = new LZ4FrameOutputStream(out);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) {
            lz4Out.write(buffer, 0, len);
        }
        // It is critical to close the LZ4 stream to finalize the frame.
        // This also closes the underlying 'out' stream.
        lz4Out.close();
    }

    /**
     * Decompresses data from an InputStream and writes it to an OutputStream using LZ4.
     *
     * @param in  The InputStream to read compressed data from.
     * @param out The OutputStream to write decompressed raw data to.
     * @throws IOException if an I/O error occurs.
     */
    public static void decompress(InputStream in, OutputStream out) throws IOException {
        // This simplified implementation is more robust for the LZ4 library.
        // If an error happens during the read/write loop, the exception will propagate
        // and the stream won't be closed here, preventing further issues.
        LZ4FrameInputStream lz4In = new LZ4FrameInputStream(in);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = lz4In.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        // Closing the wrapper stream is good practice and also closes the underlying 'in' stream.
        lz4In.close();
    }
}

