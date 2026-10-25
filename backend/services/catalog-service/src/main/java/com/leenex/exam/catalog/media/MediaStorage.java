package com.leenex.exam.catalog.media;

import java.util.Optional;

/**
 * Where image bytes live (ADR-012). Keys are random and never derived from user input. The filesystem store is used
 * locally and on the single-host deployment; an S3 store can replace it without changing callers.
 */
public interface MediaStorage {

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);
}
