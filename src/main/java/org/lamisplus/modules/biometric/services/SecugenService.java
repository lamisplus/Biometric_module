package org.lamisplus.modules.biometric.services;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lamisplus.modules.base.controller.apierror.EntityNotFoundException;
import org.lamisplus.modules.biometric.domain.Biometric;
import org.lamisplus.modules.biometric.domain.ClientIdentificationProject;
import org.lamisplus.modules.biometric.domain.dto.*;
import org.lamisplus.modules.biometric.enumeration.ErrorCode;
import org.lamisplus.modules.biometric.enumeration.MatchTypes;
import org.lamisplus.modules.biometric.repository.BiometricRepository;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecugenService {
    public static final String ERROR_MESSAGE = "ERROR";
    public static final String MATCH = "match";
    public static final String RECAPTURE_MESSAGE = "RECAPTURE_MESSAGE";
    public static final int RECAPTURE = 0;
    public static final String FINGERPRINT_ALREADY_CAPTURED = "Fingerprint already captured";
    public static final int IMAGE_QUALITY = 61;
    public static final int MINIMUM_TEMPLATE_LENGTH = 200;
    private static final String SUCCESS_MATCH_FOUND = "SUCCESS_MATCH_FOUND";
    private static final String SUCCESS_NO_MATCH_FOUND = "SUCCESS_NO_MATCH_FOUND";
    private final SecugenManager secugenManager;
    private final FingerprintMatcher fingerprintMatcher;
    private final BiometricRepository biometricRepository;
    private final CurrentUserOrganizationService facility;

    /**
     * Biometric enrollment
     * @param reader
     * @param isNew
     * @param recapture
     * @param captureRequestDTO
     * @return BiometricEnrollmentDto
     */
    public BiometricEnrollmentDto enrollment(String reader, Boolean identify, Boolean isNew, Boolean recapture, CaptureRequestDTO captureRequestDTO){
        if(Boolean.TRUE.equals(isNew)){
            //clear store
            this.emptyStoreByPersonId(captureRequestDTO.getPatientId());
        }
        BiometricEnrollmentDto biometric = getBiometricEnrollmentDto(captureRequestDTO);

        if(biometric.getMessage() == null) biometric.setMessage(new HashMap<>());
            // checks if the secugen device is active
        if (this.scannerIsNotSet(reader)) {
            biometric.getMessage().put(ERROR_MESSAGE, "READER NOT AVAILABLE");
            biometric.setType(BiometricEnrollmentDto.Type.ERROR);
            return biometric;
        }

        biometric.setDeviceName(reader);
        biometric.getMessage().put("STARTED CAPTURING", "PROCEEDING...");

        Long error = secugenManager.boot(secugenManager.getDeviceId(reader));
        if (error > 0L) {
            ErrorCode errorCode = ErrorCode.getErrorCode(error);
            biometric.getMessage().put(ERROR_MESSAGE, errorCode.getErrorName() + ": " + errorCode.getErrorMessage());
            biometric.setType(BiometricEnrollmentDto.Type.ERROR);
            return biometric;
        }

        try {
            biometric = secugenManager.captureFingerPrint(biometric);
            if (biometric.getType() == BiometricEnrollmentDto.Type.ERROR) {
                return biometric;
            }

            if(Boolean.TRUE.equals(identify)) {
                biometric.setClientIdentificationDTO(identify(reader, biometric));
                return biometric;
            }

            BiometricStoreDTO.mergeCapturedBiometrics(captureRequestDTO.getPatientId(),
                    captureRequestDTO.getCapturedBiometricsList());

            if (!isUsableCapture(biometric)) {
                return this.addMessage(ERROR_MESSAGE, biometric, null);
            }

            byte[] scannedTemplate = biometric.getTemplate();
            if(Boolean.TRUE.equals(recapture)) {
                recaptureAgainstBaseline(captureRequestDTO.getPatientId(), biometric);
            } else {
                FingerprintMatch facilityMatch =
                        fingerprintMatcher.matchAcrossFacility(facility.getCurrentUserOrganization(), scannedTemplate);
                if (facilityMatch.isMatched()) {
                    log.info(FINGERPRINT_ALREADY_CAPTURED);
                    biometric.setMatchPersonUuid(facilityMatch.getPersonUuid());
                    biometric.setMatchBiometricId(facilityMatch.getBiometricId());
                    return this.addMessage(ERROR_MESSAGE, biometric, FINGERPRINT_ALREADY_CAPTURED);
                }

                List<CapturedBiometricDto> pendingCaptures =
                        BiometricStoreDTO.getCapturedBiometrics(biometric.getPatientId());
                if (fingerprintMatcher.matchesPendingCapture(pendingCaptures, scannedTemplate)) {
                    return this.addMessage(ERROR_MESSAGE, biometric, FINGERPRINT_ALREADY_CAPTURED);
                }
            }

            biometric.getMessage().put("CAPTURING", "PROCEEDING...");
            if(biometric.getType() == null) {
                biometric.setType(BiometricEnrollmentDto.Type.SUCCESS);
            }
            CapturedBiometricDto capturedBiometrics = new CapturedBiometricDto();
            capturedBiometrics.setTemplate(scannedTemplate);
            capturedBiometrics.setTemplateType(biometric.getTemplateType());
            capturedBiometrics.setHashed(bcryptHash(scannedTemplate));
            capturedBiometrics.setImageQuality(biometric.getMainImageQuality());
            capturedBiometrics.setMatchType(biometric.getMatchType());
            capturedBiometrics.setMatchPersonUuid(biometric.getMatchPersonUuid());
            capturedBiometrics.setMatchBiometricId(biometric.getMatchBiometricId());
            capturedBiometrics.setMatchTemplateType(biometric.getMatchTemplateType());

            biometric.setCapturedBiometricsList(
                    BiometricStoreDTO.addCapturedBiometrics(biometric.getPatientId(), capturedBiometrics));
            biometric.setTemplate(scannedTemplate);

        } catch (Exception exception) {
            log.error("Error while capturing a fingerprint", exception);
            return this.addMessage(ERROR_MESSAGE, biometric, exception.getMessage());
        }
        return biometric;
    }

    private boolean isUsableCapture(BiometricEnrollmentDto biometric) {
        return biometric.getTemplate() != null
                && biometric.getTemplate().length > MINIMUM_TEMPLATE_LENGTH
                && biometric.getMainImageQuality() >= IMAGE_QUALITY;
    }

    /**
     * Checking if scanner is set
     * @param reader
     * @return boolean
     */
    private boolean scannerIsNotSet(String reader) {
        Long readerId = secugenManager.getDeviceId(reader);
        for (DeviceDTO deviceDTO : secugenManager.getDevices()) {
            if (deviceDTO.getId().equals(String.valueOf(readerId))) {
                secugenManager.boot(readerId);
                return false;
            }
        }
        return true;
    }

    /**
     * Booting secugen scanner
     * @param reader
     * @return ErrorCodeDTO
     */
    public ErrorCodeDTO boot(String reader) {
        ErrorCode errorCode = ErrorCode.getErrorCode(secugenManager.boot(secugenManager.getDeviceId(reader)));
        return ErrorCodeDTO.builder()
                .errorID(errorCode.getErrorID())
                .errorName(errorCode.getErrorName())
                .errorMessage(errorCode.getErrorMessage())
                .errorType(errorCode.getType())
                .build();
    }

    /**
     * Setting BiometricEnrollmentDto from CaptureRequestDTO
     * @param captureRequestDTO
     * @return BiometricEnrollmentDto
     */
    public BiometricEnrollmentDto getBiometricEnrollmentDto(CaptureRequestDTO captureRequestDTO){
        BiometricEnrollmentDto biometricEnrollmentDto = new BiometricEnrollmentDto();
        biometricEnrollmentDto.setBiometricType(captureRequestDTO.getBiometricType());
        biometricEnrollmentDto.setTemplateType(captureRequestDTO.getTemplateType());
        biometricEnrollmentDto.setPatientId(captureRequestDTO.getPatientId());
        return biometricEnrollmentDto;
    }

    /**
     * Creating a custom error message
     * @param messageKey
     * @param biometricEnrollmentDto
     * @Param customMessage
     * @return BiometricEnrollmentDto
     */
    private BiometricEnrollmentDto addMessage(String messageKey,BiometricEnrollmentDto biometricEnrollmentDto, String customMessage){
        if (biometricEnrollmentDto.getMessage() == null) {
            biometricEnrollmentDto.setMessage(new HashMap<>());
        }
        int imageQuality = biometricEnrollmentDto.getMainImageQuality();
        int templateLength = biometricEnrollmentDto.getTemplate() == null ? 0 : biometricEnrollmentDto.getTemplate().length;
        biometricEnrollmentDto.getMessage().put(messageKey, "ERROR WHILE CAPTURING... " +
                "\nImage Quality: " + (imageQuality < IMAGE_QUALITY ? "Bad - " + imageQuality : "Good - " + imageQuality) +
                "\nTemplate Length: " + (templateLength < MINIMUM_TEMPLATE_LENGTH ? "Bad - " + templateLength : "Good - " + templateLength) +
                "\n" + (customMessage != null ? customMessage : "")
        );
        biometricEnrollmentDto.setType(BiometricEnrollmentDto.Type.ERROR);
        return biometricEnrollmentDto;
    }

    /**
     * emptying biometric store based on PersonId
     * @param personId
     * @return Boolean
     */
    public Boolean emptyStoreByPersonId(Long personId){
        return BiometricStoreDTO.removePatient(personId);
    }

    /**
     * Get person biometric by person uuid and recapture.
     * @param template
     * @return a hashed value of the base 64 template
     */
    public String bcryptHash(byte[] template) {
        String encoded = Base64.getEncoder().encodeToString(template);
        return BCrypt.hashpw(encoded, "$2a$12$MklNDNgs4Agd50cSasj91O");
    }

    /**
     * Get Client Identification
     * @param reader
     * @return ClientIdentificationDTO
     */
    public ClientIdentificationDTO identify(String reader, BiometricEnrollmentDto biometricEnrollmentDto){
        if (this.scannerIsNotSet(reader)) {
            throw new EntityNotFoundException(Biometric.class, "Scanner", "Scanner");
        }
        BiometricEnrollmentDto biometric = biometricEnrollmentDto != null
                ? biometricEnrollmentDto
                : secugenManager.captureFingerPrint(new BiometricEnrollmentDto());

        if (biometric.getTemplate() == null || biometric.getTemplate().length == 0) {
            return noMatchIdentification();
        }

        FingerprintMatch match =
                fingerprintMatcher.matchAcrossFacility(facility.getCurrentUserOrganization(), biometric.getTemplate());
        if (match.isMatched()) {
            Optional<ClientIdentificationProject> clientId = biometricRepository.getBiometricPersonData(match.getPersonUuid());
            if (clientId.isPresent()) {
                ClientIdentificationDTO clientIdentification = setClientDetails(clientId.get());
                clientIdentification.setMessageType(SUCCESS_MATCH_FOUND);
                clientIdentification.setMessage("Client identified");
                clientIdentification.setPersonUuid(match.getPersonUuid());
                return clientIdentification;
            }
            log.warn("Fingerprint matched person {} but no patient record was found", match.getPersonUuid());
        }
        return noMatchIdentification();
    }

    private ClientIdentificationDTO noMatchIdentification() {
        ClientIdentificationDTO clientIdentificationDTO = new ClientIdentificationDTO();
        clientIdentificationDTO.setMessageType(SUCCESS_NO_MATCH_FOUND);
        clientIdentificationDTO.setMessage("Could not identify clients");
        return clientIdentificationDTO;
    }

    private ClientIdentificationDTO setClientDetails(ClientIdentificationProject clientIdentificationProject) {
        ClientIdentificationDTO clientIdentificationDTO = new ClientIdentificationDTO();
        clientIdentificationDTO.setId(clientIdentificationProject.getId());
        clientIdentificationDTO.setFirstName(clientIdentificationProject.getFirstName());
        clientIdentificationDTO.setSurname(clientIdentificationProject.getSurname());
        clientIdentificationDTO.setOtherName(clientIdentificationProject.getOtherName());
        clientIdentificationDTO.setHospitalNumber(clientIdentificationProject.getHospitalNumber());
        clientIdentificationDTO.setSex(clientIdentificationProject.getSex());
        return clientIdentificationDTO;
    }

    private void recaptureAgainstBaseline(Long patientId, BiometricEnrollmentDto biometricEnrollmentDto){
        Optional<String> optionalPersonUuid = biometricRepository.getPersonUuid(patientId);
        if (!optionalPersonUuid.isPresent()) {
            throw new EntityNotFoundException(Biometric.class, "patientId", "" + patientId);
        }
        String personUuid = optionalPersonUuid.get();
        biometricEnrollmentDto.setMatchPersonUuid(personUuid);

        FingerprintMatch match = fingerprintMatcher.matchAgainstPerson(facility.getCurrentUserOrganization(),
                personUuid, RECAPTURE, biometricEnrollmentDto.getTemplate(), biometricEnrollmentDto.getTemplateType());

        if (!match.isMatched()) {
            log.info("no match...");
            biometricEnrollmentDto.setMatch(false);
            biometricEnrollmentDto.setType(BiometricEnrollmentDto.Type.WARNING);
            biometricEnrollmentDto.getMessage().put(MATCH, "Biometric not found...");
            biometricEnrollmentDto.getMessage().put(RECAPTURE_MESSAGE, "NO MATCH...");
            biometricEnrollmentDto.setMatchType(MatchTypes.NoMatch.getMatchType());
            return;
        }

        biometricEnrollmentDto.setMatch(true);
        biometricEnrollmentDto.setMatchBiometricId(match.getBiometricId());
        biometricEnrollmentDto.setMatchTemplateType(match.getTemplateType());
        boolean sameFinger = match.getTemplateType() != null
                && match.getTemplateType().equalsIgnoreCase(biometricEnrollmentDto.getTemplateType());

        if (sameFinger) {
            log.info("Perfect match...");
            biometricEnrollmentDto.setType(BiometricEnrollmentDto.Type.SUCCESS);
            biometricEnrollmentDto.getMessage().put(MATCH, "Perfect...");
            biometricEnrollmentDto.getMessage().put(RECAPTURE_MESSAGE, "SUCCESSFULLY RECAPTURED, PERFECT MATCH");
            biometricEnrollmentDto.setMatchType(MatchTypes.PerfectMatch.getMatchType());
        } else {
            log.info("Imperfect match...");
            biometricEnrollmentDto.setType(BiometricEnrollmentDto.Type.WARNING);
            biometricEnrollmentDto.getMessage().put(MATCH, "Imperfect...");
            biometricEnrollmentDto.getMessage().put(RECAPTURE_MESSAGE, "SUCCESSFULLY RECAPTURED, IMPERFECT MATCH");
            biometricEnrollmentDto.setMatchType(MatchTypes.ImperfectMatch.getMatchType());
        }
    }
}
