package com.dataanalyzer.web;

import com.dataanalyzer.service.DatasetParser;
import com.dataanalyzer.service.DatasetStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DatasetParser.FileValidationException.class)
    ResponseEntity<Map<String, Object>> invalidFile(RuntimeException exception) { return response(HttpStatus.BAD_REQUEST, exception.getMessage()); }

    @ExceptionHandler(DatasetStore.DatasetNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() { return response(HttpStatus.NOT_FOUND, "Dataset tidak ditemukan atau sesi sudah berakhir. Unggah ulang file untuk melanjutkan."); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) { return response(HttpStatus.BAD_REQUEST, exception.getMessage()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge() { return response(HttpStatus.PAYLOAD_TOO_LARGE, "Ukuran file melewati batas unggahan. Gunakan file yang lebih kecil."); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internal() { return response(HttpStatus.INTERNAL_SERVER_ERROR, "Kami tidak dapat memproses permintaan ini. Coba lagi atau unggah ulang dataset."); }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "message", message));
    }
}
