package com.example.securechat.server;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Prints a {@code pbkdf2$...} hash for one password read from the console (hidden) or stdin. */
public final class HashPasswordTool {
    private HashPasswordTool() {}

    public static void main(String[] args) throws IOException {
        char[] password = readPassword();
        if (password == null || password.length == 0) {
            System.err.println("No password provided");
            System.exit(1);
            return;
        }
        System.out.println(PasswordHasher.hash(password, PasswordHasher.DEFAULT_ITERATIONS));
        Arrays.fill(password, '\0');
    }

    private static char[] readPassword() throws IOException {
        Console console = System.console();
        if (console != null) {
            // Prompt goes to stderr so stdout carries only the hash (JDK 22+ returns a console even when piped).
            System.err.print("Password: ");
            System.err.flush();
            return console.readPassword();
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = in.readLine();
        return line == null ? null : line.toCharArray();
    }
}
