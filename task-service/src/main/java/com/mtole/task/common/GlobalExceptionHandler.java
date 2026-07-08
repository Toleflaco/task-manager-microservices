package com.mtole.task.common;


import com.mtole.task.tasks.InvalidTaskStateException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Resource Not Found");
        pd.setType(URI.create("https://taskmanager.mtole.com/errors/not-found"));
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach((fieldError) -> {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        });
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid data provided");
        pd.setTitle("Invalid Data");
        pd.setProperty("fields", errors);
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("Missing required header: {}", ex.getHeaderName());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing"
        );
        pd.setTitle("Missing Required Header");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }

    @ExceptionHandler(InvalidTaskStateException.class)
    public ProblemDetail handleInvalidTaskStateException(InvalidTaskStateException ex) {
        log.warn("Invalid task state: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Invalid Task State");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid value for parameter '{}': {}", ex.getName(), ex.getValue());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' has an invalid value. Expected type: " + ex.getRequiredType().getSimpleName());
        pd.setTitle("Invalid Query Parameter");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;

    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        log.warn("Login failed: invalid credentials");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password"
        );
        pd.setTitle("Authentication failed");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }


    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type: {}", ex.getContentType());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type '" + ex.getContentType() + "' is not supported. Use application/json."
        );
        pd.setTitle("Unsupported Media Type");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String causeMessage = ex.getMostSpecificCause().getMessage();

        // PostgreSQL incluye "duplicate key" en el mensaje de la SQLState 23505.
        // Hibernate 7 traduce al padre DataIntegrityViolationException
        // (no al subtipo DuplicateKeyException), por eso inspeccionamos el mensaje.
        // Patrón portable: si en el futuro se cambia el motor, el mensaje cambia
        // pero el handler se ajusta en un solo punto.
        boolean isUniqueViolation = causeMessage != null
                && causeMessage.contains("duplicate key");

        if (isUniqueViolation) {
            log.warn("Unique constraint violation: {}", causeMessage);
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Resource already exists"
            );
            pd.setTitle("Resource Conflict");
            pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
            return pd;
        }

        // Cualquier otra violación de integridad → 500 + log con stacktrace.
        log.error("Unhandled data integrity violation", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred"
        );
        pd.setTitle("Internal Server Error");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }


    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The resource was modified by another request. Please reload and try again."
        );
        pd.setTitle("Conflict");
        pd.setType(URI.create("https://taskmanager.mtole.com/errors/conflict"));
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleException(Exception ex) {
        //log.error("Error not controlled");
        log.error("Unhandled exception", ex); //incluir exception para ver el stacktrace
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle("Internal Server Error");
        pd.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return pd;
    }


}
