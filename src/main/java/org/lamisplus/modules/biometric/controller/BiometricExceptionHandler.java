package org.lamisplus.modules.biometric.controller;

import lombok.extern.slf4j.Slf4j;
import org.lamisplus.modules.base.controller.apierror.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Returns the message the operator should read, in the {apierror:{message}} shape the UI already
 * reads. Scoped to this module so other modules keep the base handler.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = {BiometricController.class, SecugenController.class, PIMSController.class})
public class BiometricExceptionHandler {

    @ExceptionHandler(BiometricOperationException.class)
    public ResponseEntity<Object> handleBiometricOperation(BiometricOperationException exception) {
        log.warn("Biometric request rejected: {}", exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getStatus(), exception.getMessage(), exception));
    }
}
