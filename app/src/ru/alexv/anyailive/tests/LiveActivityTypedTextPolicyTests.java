package ru.alexv.anyailive.tests;

import ru.alexv.anyailive.ui.LiveActivity;

/** Regression checks for typed turns at the native/cascade boundary. */
public final class LiveActivityTypedTextPolicyTests {
    public static void main(String[] args) {
        check("native typed turn is persisted", LiveActivity.shouldPersistTypedUser(false));
        check("cascade typed turn waits for callback", !LiveActivity.shouldPersistTypedUser(true));
        System.out.println("LiveActivityTypedTextPolicyTests: 2 passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }
}
