package com.socialhub.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Normalizes legacy /api JSON controller responses without modifying service behavior. */
@RestControllerAdvice(basePackages = "com.socialhub")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        Class<?> controller = returnType.getContainingClass();
        String name = controller.getName();
        return name.startsWith("com.socialhub.")
                && !name.startsWith("com.socialhub.grouppublishingtask.worker.")
                && !name.startsWith("com.socialhub.common.")
                && !name.startsWith("com.socialhub.response.");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
            MediaType selectedContentType, Class selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof ApiResponse<?> || body instanceof String
                || body instanceof byte[]) {
            return body;
        }
        String path = request.getURI().getPath();
        if (!path.startsWith("/api/")) return body;
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) return body;
        int status = response instanceof org.springframework.http.server.ServletServerHttpResponse servlet
                ? servlet.getServletResponse().getStatus() : 200;
        if (status == 204 || status == 304 || status >= 400) return body;
        return ApiResponse.success(status, "Success", body);
    }
}
