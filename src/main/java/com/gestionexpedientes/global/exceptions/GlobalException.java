package com.gestionexpedientes.global.exceptions;

import com.gestionexpedientes.global.dto.MessageDto;
import com.gestionexpedientes.global.utils.Operations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalException {

    private static final Logger logger = LoggerFactory.getLogger(GlobalException.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<MessageDto> throwNotFoundException(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MessageDto(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(AttributeException.class)
    public ResponseEntity<MessageDto> throwAttributeException(AttributeException e) {
        return ResponseEntity.badRequest()
                .body(new MessageDto(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<MessageDto> conflictException(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageDto(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(WorkflowNotConfiguredException.class)
    public ResponseEntity<MessageDto> workflowNotConfiguredException(WorkflowNotConfiguredException e) {
        return ResponseEntity.badRequest()
                .body(new MessageDto(HttpStatus.BAD_REQUEST, e.getMessage(), WorkflowNotConfiguredException.CODE));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<MessageDto> tooManyRequestsException(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(new MessageDto(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<MessageDto> maxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new MessageDto(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera el tamaño máximo de 2 MB."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MessageDto> generalException(Exception e) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        logger.error("[{}] error no controlado", errorId, e);
        return ResponseEntity.internalServerError()
                .body(new MessageDto(HttpStatus.INTERNAL_SERVER_ERROR,
                        "No se pudo completar la operación por un error del servidor. Intente nuevamente en unos minutos; "
                                + "si el problema persiste, informe el código " + errorId + " al administrador."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageDto> validationException(MethodArgumentNotValidException e){
        List<String> messages = new ArrayList<>();
        e.getBindingResult().getAllErrors().forEach((err) -> {
            messages.add(err.getDefaultMessage());
        });
        return ResponseEntity.badRequest()
                .body(new MessageDto(HttpStatus.BAD_REQUEST, Operations.trimBrackets(messages.toString())));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<MessageDto> badCredentialsException(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageDto(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<MessageDto> accessDeniedException(AccessDeniedException accessDeniedException) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new MessageDto(HttpStatus.FORBIDDEN, "No tiene permiso para realizar esta operación."));
    }

}
