package com.example.securechat.model;

/** Thrown when a received line is not a valid protocol message. */
public class InvalidMessageException extends Exception {
    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
