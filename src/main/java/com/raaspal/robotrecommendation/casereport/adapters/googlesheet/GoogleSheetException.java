package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

/**
 * A Google Sheets failure: a key that is missing or malformed, a sheet not shared with
 * the service account, a tab that does not exist, or Google being unreachable.
 *
 * <p>The counterpart of {@code MondayApiException}, and answered the same way - 502, with
 * the message kept - so the console can say "the sheet is unavailable" rather than "the
 * backend crashed".
 */
public class GoogleSheetException extends RuntimeException {

    public GoogleSheetException(String message) {
        super(message);
    }

    public GoogleSheetException(String message, Throwable cause) {
        super(message, cause);
    }
}
