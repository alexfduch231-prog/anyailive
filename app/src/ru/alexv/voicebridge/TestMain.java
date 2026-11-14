package ru.alexv.voicebridge;

/**
 * Headless-проверки логики: запускается на обычной JVM, без Android.
 * Никаких обращений к android.* здесь быть не должно.
 *   java -cp "build/classes;app/libs/nv-websocket-client-2.14.jar" ru.alexv.voicebridge.TestMain
 */
public class TestMain {

    private static int fails = 0;
    private static int total = 0;

    static void check(String name, boolean ok) {
        total++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    static void fail(String name, String why) {
        total++;
        fails++;
        System.out.println("FAIL " + name + " (" + why + ")");
    }

    static void section(String name) {
        System.out.println("--- " + name);
    }

    public static void main(String[] args) {
        section("skeleton");
        check("build.smoke", true);
        check("strings.brand", "VoiceBridge".equals("VoiceBridge"));

        System.out.println();
        System.out.println(fails == 0 ? ("PASS " + total + " checks") : ("FAILURES " + fails + "/" + total));
        System.exit(fails == 0 ? 0 : 1);
    }
}
