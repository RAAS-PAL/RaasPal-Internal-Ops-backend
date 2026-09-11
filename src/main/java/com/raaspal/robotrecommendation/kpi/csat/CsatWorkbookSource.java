package com.raaspal.robotrecommendation.kpi.csat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Where the survey workbooks come from: an S3 bucket the RE team uploads to
 * ({@link BucketCsatWorkbookSource}) wherever one is configured, and otherwise a
 * folder on the machine running the backend ({@link FolderCsatWorkbookSource}),
 * which is how a developer works on this without credentials.
 *
 * <p>The contract is the whole current set, every call: the team replaces all
 * four workbooks each month, so the source is a snapshot, not a stream of
 * changes. The service decides whether anything actually changed by comparing
 * names, sizes and timestamps.
 */
public interface CsatWorkbookSource {

    /** Where this reads from, for messages — a path, later a bucket and prefix. */
    String describe();

    /**
     * Every workbook currently in the source. Throws a
     * {@code BadRequestException} when the source is not configured or holds no
     * workbooks: the console shows the reason, and it is not the caller's fault.
     */
    List<WorkbookFile> list();

    /**
     * Drops whatever the source is holding, so the next {@link #list()} asks the
     * real thing again. The console's reload button calls this: a remote source
     * reuses a listing for a few seconds, and "re-read files" has to mean it.
     * A folder holds nothing, so for it this is nothing.
     */
    default void refresh() {
    }

    /** Opens the workbook's bytes; the caller closes the stream. */
    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    record WorkbookFile(String name, long size, Instant lastModified, Opener opener) {

        public InputStream open() throws IOException {
            return opener.open();
        }

        /** Part of the change signature: same name, size and mtime means same content, near enough. */
        public String fingerprint() {
            return name + "|" + size + "|" + lastModified.toEpochMilli();
        }
    }
}
