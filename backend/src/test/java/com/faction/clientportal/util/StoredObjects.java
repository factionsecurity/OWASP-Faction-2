package com.faction.clientportal.util;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the stream-plus-metadata handle that {@code StorageService.openStream}
 * returns, so tests can stub a stored object without a live storage backend.
 */
public final class StoredObjects {

    private StoredObjects() {
    }

    /** A stored object with the given bytes and content type. */
    public static ResponseInputStream<GetObjectResponse> of(byte[] bytes, String contentType) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder()
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    /** A stored object holding the given text as {@code application/octet-stream}. */
    public static ResponseInputStream<GetObjectResponse> of(String content) {
        return of(content.getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }
}
