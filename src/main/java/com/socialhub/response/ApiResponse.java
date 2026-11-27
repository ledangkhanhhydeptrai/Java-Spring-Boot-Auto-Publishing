
package com.socialhub.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(
        int status,
        String message,
        T data
) {

    public ApiResponse {
        message = ViApiMessages.response(status, message);
    }

    public static <T> ApiResponse<T> success(
            int status,
            String message,
            T data
    ) {
        return new ApiResponse<>(status, message, data);
    }

    public static <T> ApiResponse<T> success(
            String message,
            T data
    ) {
        return success(200, message, data);
    }

    public static ApiResponse<Void> error(
            int status,
            String message
    ) {
        return new ApiResponse<>(status, message, null);
    }
}
