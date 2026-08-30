package com.faction.clientportal.util;

import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.dto.common.PageMetadata;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Utility class for building standardized API responses
 */
public class ResponseUtil {

    private ResponseUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Build a successful response with data
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> success(T data) {
        return ResponseEntity.ok(JsonApiResponse.success(data));
    }

    /**
     * Build a successful response with custom message and data
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> success(String message, T data) {
        return ResponseEntity.ok(JsonApiResponse.success(message, data));
    }

    /**
     * Build a successful response with only a message
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> success(String message) {
        return ResponseEntity.ok(JsonApiResponse.success(message));
    }

    /**
     * Build a paginated response from Spring Data Page object
     */
    public static <T> ResponseEntity<JsonApiResponse<List<T>>> paginated(Page<T> page) {
        PageMetadata metadata = PageMetadata.from(page);
        JsonApiResponse<List<T>> response = JsonApiResponse.paginated(page.getContent(), metadata);
        return ResponseEntity.ok(response);
    }

    /**
     * Build a paginated response with custom message
     */
    public static <T> ResponseEntity<JsonApiResponse<List<T>>> paginated(String message, Page<T> page) {
        PageMetadata metadata = PageMetadata.from(page);
        JsonApiResponse<List<T>> response = JsonApiResponse.paginated(message, page.getContent(), metadata);
        return ResponseEntity.ok(response);
    }

    /**
     * Build a created response (HTTP 201)
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> created(T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponse.success("Resource created successfully", data));
    }

    /**
     * Build a created response with custom message
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> created(String message, T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponse.success(message, data));
    }

    /**
     * Build an error response
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(JsonApiResponse.error(message));
    }

    /**
     * Build an error response with error code and details
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> error(HttpStatus status, String message,
                                                           String errorCode, String errorDetails) {
        return ResponseEntity.status(status)
                .body(JsonApiResponse.error(message, errorCode, errorDetails));
    }

    /**
     * Build a bad request response (HTTP 400)
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> badRequest(String message) {
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Build a not found response (HTTP 404)
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, message);
    }

    /**
     * Build a forbidden response (HTTP 403)
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> forbidden(String message) {
        return error(HttpStatus.FORBIDDEN, message);
    }

    /**
     * Build an unauthorized response (HTTP 401)
     */
    public static <T> ResponseEntity<JsonApiResponse<T>> unauthorized(String message) {
        return error(HttpStatus.UNAUTHORIZED, message);
    }
}
