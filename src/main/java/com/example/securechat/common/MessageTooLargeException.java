package com.example.securechat.common;

import java.io.IOException;

/** Thrown when an incoming line exceeds the configured byte limit before its newline arrives. */
public class MessageTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public MessageTooLargeException(String message) {
        super(message);
    }
}
