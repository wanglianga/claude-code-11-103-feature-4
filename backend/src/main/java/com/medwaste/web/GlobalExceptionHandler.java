package com.medwaste.web;

import com.medwaste.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        if (!e.getExtra().isEmpty()) body.putAll(e.getExtra());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        System.err.println("[API-ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        if (root != e) System.err.println("[API-ERROR-ROOT] " + root.getClass().getName() + ": " + root.getMessage());
        e.printStackTrace();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", "INTERNAL_ERROR");
        body.put("message", e.getClass().getSimpleName() + ": " + root.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
