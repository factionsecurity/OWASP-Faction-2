package com.faction.clientportal.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Generic API response wrapper
 * Provides consistent response structure across all endpoints
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Generic API response wrapper")
public class JsonApiResponse<T> {
    
    @Schema(description = "Indicates if the operation was successful", example = "true")
    private boolean success;
    
    @Schema(description = "Response message", example = "Operation completed successfully")
    private String message;
    
    @Schema(description = "Response data")
    private T data;
    
    @Schema(description = "Error details if operation failed")
    private ErrorDetails error;

    @Schema(description = "Pagination metadata for paginated responses")
    private PageMetadata pagination;

    @Schema(description = "Response timestamp", example = "2024-01-01T12:00:00")
    private LocalDateTime timestamp;
    
    // Constructors
    public JsonApiResponse() {
        this.timestamp = LocalDateTime.now();
    }
    
    public JsonApiResponse(boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }
    
    public JsonApiResponse(boolean success, String message, T data) {
        this();
        this.success = success;
        this.message = message;
        this.data = data;
    }
    
    public JsonApiResponse(boolean success, String message, ErrorDetails error) {
        this();
        this.success = success;
        this.message = message;
        this.error = error;
    }
    
    // Static factory methods for success responses
    public static <T> JsonApiResponse<T> success(T data) {
        return new JsonApiResponse<>(true, "Operation completed successfully", data);
    }
    
    public static <T> JsonApiResponse<T> success(String message, T data) {
        return new JsonApiResponse<>(true, message, data);
    }
    
    public static <T> JsonApiResponse<T> success(String message) {
        return new JsonApiResponse<>(true, message);
    }
    
    // Static factory methods for error responses
    public static <T> JsonApiResponse<T> error(String message) {
        JsonApiResponse<T> response = new JsonApiResponse<>();
        response.success = false;
        response.message = message;
        return response;
    }
    
    public static <T> JsonApiResponse<T> error(String message, ErrorDetails error) {
        return new JsonApiResponse<>(false, message, error);
    }
    
    public static <T> JsonApiResponse<T> error(String message, String errorCode, String errorDetails) {
        ErrorDetails error = new ErrorDetails(errorCode, errorDetails);
        return new JsonApiResponse<>(false, message, error);
    }

    // Static factory methods for paginated responses
    public static <T> JsonApiResponse<T> paginated(T data, PageMetadata pagination) {
        JsonApiResponse<T> response = new JsonApiResponse<>(true, "Data retrieved successfully", data);
        response.pagination = pagination;
        return response;
    }

    public static <T> JsonApiResponse<T> paginated(String message, T data, PageMetadata pagination) {
        JsonApiResponse<T> response = new JsonApiResponse<>(true, message, data);
        response.pagination = pagination;
        return response;
    }
    
    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public T getData() {
        return data;
    }
    
    public void setData(T data) {
        this.data = data;
    }
    
    public ErrorDetails getError() {
        return error;
    }
    
    public void setError(ErrorDetails error) {
        this.error = error;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public PageMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PageMetadata pagination) {
        this.pagination = pagination;
    }
    
    /**
     * Error details nested class
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Error details")
    public static class ErrorDetails {
        
        @Schema(description = "Error code", example = "VALIDATION_ERROR")
        private String code;
        
        @Schema(description = "Detailed error description", example = "Invalid input parameters")
        private String details;
        
        @Schema(description = "Field-specific validation errors")
        private Object fieldErrors;
        
        public ErrorDetails() {}
        
        public ErrorDetails(String code, String details) {
            this.code = code;
            this.details = details;
        }
        
        public ErrorDetails(String code, String details, Object fieldErrors) {
            this.code = code;
            this.details = details;
            this.fieldErrors = fieldErrors;
        }
        
        // Getters and Setters
        public String getCode() {
            return code;
        }
        
        public void setCode(String code) {
            this.code = code;
        }
        
        public String getDetails() {
            return details;
        }
        
        public void setDetails(String details) {
            this.details = details;
        }
        
        public Object getFieldErrors() {
            return fieldErrors;
        }
        
        public void setFieldErrors(Object fieldErrors) {
            this.fieldErrors = fieldErrors;
        }
    }
    
    @Override
    public String toString() {
        return "ApiResponse{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", error=" + error +
                ", pagination=" + pagination +
                ", timestamp=" + timestamp +
                '}';
    }
}
