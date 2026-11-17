package com.example.drive;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(org.springframework.web.server.ResponseStatusException failure) {
        var response=ResponseEntity.status(failure.getStatusCode());
        if(failure.getStatusCode().value()==429)response.header("Retry-After","60");
        return response.body(ProblemDetail.forStatusAndDetail(failure.getStatusCode(),failure.getReason()==null?"Request rejected":failure.getReason()));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable() {return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid JSON request"));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict() {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Name or integrity constraint conflict"));
    }

    @ExceptionHandler(SdkException.class)
    ResponseEntity<ProblemDetail> storage() {
        return ResponseEntity.status(503).body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Object storage unavailable; retry with the same upload ID"));
    }
}
