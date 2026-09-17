package com.raaspal.robotrecommendation.robotunit.service;

/**
 * Where contract PDFs' bytes live. S3 in production; the interface exists so the
 * service and its tests do not know that, and so the bucket could be swapped for
 * another store without touching either.
 */
public interface ContractDocumentStore {

    /** Stores the bytes under the key, replacing anything already there. */
    void put(String key, byte[] bytes, String contentType);

    /**
     * A link a browser can open to read the object for a few minutes, with the
     * download filename the reader should see. The bucket is private; this is the
     * only way out of it.
     */
    String temporaryUrl(String key, String downloadFileName);

    /** Removes the object. Removing a key that is already gone is not an error. */
    void delete(String key);
}
