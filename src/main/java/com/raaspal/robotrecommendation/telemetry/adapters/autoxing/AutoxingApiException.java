package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

/**
 * Raised when a call to the AutoXing Cloud Platform API fails. {@code authFailure}
 * flags the documented 400 "Authentication failed" case (or an invalid/expired
 * token) so callers can re-authenticate and retry once.
 */
public class AutoxingApiException extends RuntimeException {

    private final boolean authFailure;

    public AutoxingApiException(String message) {
        this(message, false);
    }

    public AutoxingApiException(String message, boolean authFailure) {
        super(message);
        this.authFailure = authFailure;
    }

    public AutoxingApiException(String message, Throwable cause) {
        super(message, cause);
        this.authFailure = false;
    }

    public boolean isAuthFailure() {
        return authFailure;
    }

    /**
     * AutoXing's 50403 "all deviceIds are outside allowed businesses": the robot id is not
     * on this account. In practice a mistyped id - the serials mix lowercase {@code l} with
     * capital {@code I} ({@code 2382410c042997l}), and the two look identical in most fonts.
     */
    public boolean isUnknownRobot() {
        String m = getMessage();
        return m != null && (m.contains("50403") || m.contains("outside allowed businesses"));
    }

    /** The message a person should see for {@link #isUnknownRobot()}. */
    public static String unknownRobotMessage(String robotId) {
        return "Robot ID \"" + robotId + "\" is not on the RAAS PAL AutoXing account. Check the ID — "
                + "AutoXing serials mix a lowercase \"l\" and a capital \"I\", which look alike.";
    }
}
