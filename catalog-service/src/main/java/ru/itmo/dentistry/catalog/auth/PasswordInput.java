package ru.itmo.dentistry.catalog.auth;

import java.nio.charset.StandardCharsets;

public final class PasswordInput {

    private PasswordInput() {
    }

    public static boolean fitsBCrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
