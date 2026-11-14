package com.example.securechat.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class LineReaderTest {

    private static LineReader reader(String text, int maxBytes) {
        return new LineReader(new ByteArrayInputStream(text.getBytes(UTF_8)), maxBytes);
    }

    private static int indexOf(byte[] data, byte value) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void twoMessagesInOneChunk() throws IOException {
        LineReader r = reader("{\"a\":1}\n{\"b\":2}\n", 4096);
        assertEquals("{\"a\":1}", r.readLine());
        assertEquals("{\"b\":2}", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    void splitInsideUtf8Character() throws Exception {
        byte[] all = "{\"content\":\"Xin chào\"}\n".getBytes(UTF_8);
        // Cut right after the first byte of 'à' (0xC3 0xA0), so one character spans two reads.
        int cut = indexOf(all, (byte) 0xC3) + 1;

        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 4096);
        Thread writer = new Thread(() -> {
            try {
                out.write(all, 0, cut);
                out.flush();
                Thread.sleep(100);
                out.write(all, cut, all.length - cut);
                out.flush();
                out.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        LineReader r = new LineReader(in, 4096);
        assertEquals("{\"content\":\"Xin chào\"}", r.readLine());
        writer.join(5000);
    }

    @Test
    void crlfIsStripped() throws IOException {
        assertEquals("x", reader("x\r\n", 10).readLine());
    }

    @Test
    void lineAtLimitIsAccepted() throws IOException {
        assertEquals(4096, reader("a".repeat(4096) + "\n", 4096).readLine().length());
    }

    @Test
    void lineOverLimitThrows() {
        assertThrows(MessageTooLargeException.class,
                () -> reader("a".repeat(4097) + "\n", 4096).readLine());
    }

    @Test
    void frameAppendsNewlineUtf8() {
        assertArrayEquals("chào\n".getBytes(UTF_8), Protocol.frame("chào"));
    }

    @Test
    void fitsLineCountsBytesNotChars() {
        // "ệ" is 3 bytes in UTF-8: 1365 * 3 = 4095 fits, 1366 * 3 = 4098 does not.
        assertTrue(Protocol.fitsLine("ệ".repeat(1365)));
        assertFalse(Protocol.fitsLine("ệ".repeat(1366)));
    }
}
