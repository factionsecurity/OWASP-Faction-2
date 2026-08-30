package com.faction.clientportal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final S3Client s3Client;

    @Value("${storage.bucket}")
    private String bucket;

    /**
     * An open stream over a stored object, paired with the filename to serve it
     * under. Returned by the services that resolve a storage key from a domain
     * record, so controllers never handle raw keys.
     *
     * <p>The holder owns {@code stream} and must close it.
     */
    public record StoredFile(ResponseInputStream<GetObjectResponse> stream, String fileName) {}

    @PostConstruct
    public void init() {
        try {
            s3Client.headBucket(r -> r.bucket(bucket));
            log.info("Storage bucket '{}' already exists", bucket);
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(r -> r.bucket(bucket));
            log.info("Created storage bucket '{}'", bucket);
        } catch (Exception e) {
            log.warn("Could not verify/create storage bucket '{}': {}", bucket, e.getMessage());
        }
    }

    /**
     * Open a streaming handle to an object so the caller can pipe it straight to
     * an HTTP response without buffering the whole file on the heap.
     *
     * <p>The caller owns the returned stream and <strong>must</strong> close it —
     * an unclosed stream holds a connection out of the SDK's pool until it times
     * out, so a leak here starves every other storage read.
     *
     * @param key Object key in the bucket
     * @return An open stream over the object's bytes, carrying its S3 metadata
     */
    public ResponseInputStream<GetObjectResponse> openStream(String key) {
        return s3Client.getObject(r -> r.bucket(bucket).key(key));
    }

    /**
     * Stream bytes into storage without buffering them in memory.
     *
     * <p>{@code contentLength} must be the exact byte count — the SDK needs it up
     * front to sign the request, and a mismatch fails the upload rather than
     * silently truncating.
     *
     * @param key           Object key (path) in the bucket
     * @param in            Source of the object's bytes; closed by the SDK
     * @param contentLength Exact length of {@code in}, in bytes
     * @param contentType   MIME type of the file
     */
    public void uploadStream(String key, InputStream in, long contentLength, String contentType) {
        s3Client.putObject(
                r -> r.bucket(bucket).key(key).contentType(contentType),
                RequestBody.fromInputStream(in, contentLength)
        );
        log.debug("Streamed {} bytes to key: {}", contentLength, key);
    }

    /**
     * Upload bytes directly from the backend (used for inline images).
     */
    public void uploadBytes(String key, byte[] bytes, String contentType) {
        s3Client.putObject(
                r -> r.bucket(bucket).key(key).contentType(contentType),
                RequestBody.fromBytes(bytes)
        );
        log.debug("Uploaded {} bytes to key: {}", bytes.length, key);
    }

    /**
     * Download an object's bytes from storage.
     *
     * @param key Object key in the bucket
     * @return Raw bytes of the stored object
     */
    public byte[] downloadBytes(String key) {
        return s3Client.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }

    /**
     * Permanently delete an object from storage.
     *
     * @param key Object key in the bucket
     */
    public void deleteObject(String key) {
        s3Client.deleteObject(r -> r.bucket(bucket).key(key));
        log.info("Deleted object: {}", key);
    }

    /**
     * Build a deterministic storage key for an assessment file.
     */
    public String buildKey(String assessmentId, String fileId, String fileName) {
        return String.format("%s/%s/%s", assessmentId, fileId, fileName);
    }
}
