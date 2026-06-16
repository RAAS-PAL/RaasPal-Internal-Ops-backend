package com.raaspal.robotrecommendation.report.storage;

/** Thrown when a Supabase Storage upload or signed-URL request fails. */
public class SupabaseStorageException extends RuntimeException {

    public SupabaseStorageException(String message) {
        super(message);
    }

    public SupabaseStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
