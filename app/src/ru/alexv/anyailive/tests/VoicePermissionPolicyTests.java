package ru.alexv.anyailive.tests;

import ru.alexv.anyailive.ui.VoicePermissionPolicy;

/**
 * Standalone JVM regressions for the RECORD_AUDIO request lifecycle in ChatActivity.
 *
 * The system permission dialog pauses the activity, so a pending request must survive
 * that pause. On the normal path the grant is delivered before onResume: the policy
 * defers and the grant is consumed once the screen is resumed. A grant that belongs to
 * a closed screen or to another activity instance (no pending request) never starts
 * the microphone.
 */
public final class VoicePermissionPolicyTests {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // Normal flow: tap mic -> dialog -> onPause -> grant -> (before onResume) -> resume.
        VoicePermissionPolicy dialog = new VoicePermissionPolicy();
        check("request accepted on live screen", dialog.request());
        dialog.onPause();
        check("dialog pause keeps the request pending",
                dialog.onResult(true) == VoicePermissionPolicy.DEFER);
        dialog.onResume();
        check("grant received before onResume is consumed on resume", dialog.consumePendingGrant());
        check("consumed grant is not replayed twice", !dialog.consumePendingGrant());

        // The system may deliver the grant without a pause in between.
        VoicePermissionPolicy immediate = new VoicePermissionPolicy();
        immediate.request();
        check("grant while resumed starts immediately",
                immediate.onResult(true) == VoicePermissionPolicy.START);
        check("immediately started grant is not consumed again",
                !immediate.consumePendingGrant());

        // Grant arriving only after the screen came back.
        VoicePermissionPolicy afterResume = new VoicePermissionPolicy();
        afterResume.request();
        afterResume.onPause();
        afterResume.onResume();
        check("pending request alone does not start voice on resume",
                !afterResume.consumePendingGrant());
        check("grant after resume starts normally",
                afterResume.onResult(true) == VoicePermissionPolicy.START);

        // Denial clears the request: a stale grant for it must not start anything.
        VoicePermissionPolicy denied = new VoicePermissionPolicy();
        denied.request();
        denied.onPause();
        check("denial is ignored", denied.onResult(false) == VoicePermissionPolicy.IGNORE);
        denied.onResume();
        check("denial does not defer anything", !denied.consumePendingGrant());
        check("late grant after denial is ignored",
                denied.onResult(true) == VoicePermissionPolicy.IGNORE);

        // Closed screen: the permission dialog was dismissed by leaving the activity.
        VoicePermissionPolicy closed = new VoicePermissionPolicy();
        closed.request();
        closed.onPause();
        check("grant before resume is deferred", closed.onResult(true) == VoicePermissionPolicy.DEFER);
        closed.onClose();
        check("closing drops the deferred grant", !closed.consumePendingGrant());
        check("closed screen cannot request permissions", !closed.request());
        check("new request after close is not accepted", !closed.request());

        VoicePermissionPolicy closedFirst = new VoicePermissionPolicy();
        closedFirst.request();
        closedFirst.onPause();
        closedFirst.onClose();
        check("grant delivered after onDestroy is ignored",
                closedFirst.onResult(true) == VoicePermissionPolicy.IGNORE);

        // Recreated activity instance: no pending request of its own.
        VoicePermissionPolicy recreated = new VoicePermissionPolicy();
        recreated.onResume();
        check("grant of another instance is ignored",
                recreated.onResult(true) == VoicePermissionPolicy.IGNORE);
        check("foreign grant is not consumed on resume", !recreated.consumePendingGrant());

        // Grant without any request at all.
        VoicePermissionPolicy fresh = new VoicePermissionPolicy();
        check("grant without request is ignored",
                fresh.onResult(true) == VoicePermissionPolicy.IGNORE);

        // Repeated taps while the dialog is opening must not produce two starts.
        VoicePermissionPolicy repeated = new VoicePermissionPolicy();
        repeated.request();
        check("repeated request is accepted", repeated.request());
        repeated.onPause();
        repeated.onResume();
        check("single start for one grant",
                repeated.onResult(true) == VoicePermissionPolicy.START);
        check("second grant of the same request is ignored",
                repeated.onResult(true) == VoicePermissionPolicy.IGNORE);

        if (failures != 0) throw new AssertionError(failures + " of " + checks + " checks failed");
        System.out.println("VoicePermissionPolicyTests: " + checks + " passed");
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("FAIL: " + label);
        }
    }
}
