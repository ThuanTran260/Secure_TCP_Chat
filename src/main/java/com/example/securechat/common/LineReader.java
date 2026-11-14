package com.example.securechat.common;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-delimited UTF-8 lines from a stream with a hard byte limit.
 * Bytes are accumulated before decoding, so a multi-byte character split across
 * TCP reads is reassembled correctly.
 */
public final class LineReader {

    private final BufferedInputStream in;
    private final int maxBytes;

    public LineReader(InputStream in, int maxBytes) {
        this.in = new BufferedInputStream(in);
        this.maxBytes = maxBytes;
    }

    /**
     * Returns the next line without its '\n' (and without a trailing '\r'), or null at end of stream.
     * A partial line at EOF is returned as-is.
     *
     * @throws MessageTooLargeException if more than maxBytes arrive before a '\n'
     * @throws IOException on read failure
     */
    public String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buf.size() == 0) {
                    return null;
                }
                return decode(buf);
            }
            if (b == '\n') {
                return decode(buf);
            }
            if (buf.size() >= maxBytes) {
                throw new MessageTooLargeException("Line exceeds " + maxBytes + " bytes");
            }
            buf.write(b);
        }
    }

    private static String decode(ByteArrayOutputStream buf) {
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
