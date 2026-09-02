package org.lamisplus.modules.biometric.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lamisplus.modules.biometric.domain.dto.CapturedBiometricDto;
import org.lamisplus.modules.biometric.domain.dto.FingerprintMatch;
import org.lamisplus.modules.biometric.domain.dto.IndexedTemplate;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FingerprintMatcher {
    private final SecugenManager secugenManager;
    private final BiometricTemplateIndex templateIndex;

    public FingerprintMatch matchAcrossFacility(Long facilityId, byte[] scannedTemplate) {
        templateIndex.synchronise(facilityId);
        return firstMatch(templateIndex.facilityCandidates(facilityId, scannedTemplate), scannedTemplate);
    }

    public FingerprintMatch matchAgainstPerson(Long facilityId, String personUuid, Integer recapture,
                                               byte[] scannedTemplate, String expectedTemplateType) {
        templateIndex.synchronise(facilityId);
        List<IndexedTemplate> candidates =
                templateIndex.personCandidates(facilityId, personUuid, recapture, scannedTemplate, expectedTemplateType);
        return firstMatch(candidates, scannedTemplate);
    }

    public boolean matchesPendingCapture(Collection<CapturedBiometricDto> pendingCaptures, byte[] scannedTemplate) {
        if (pendingCaptures == null || scannedTemplate == null) {
            return false;
        }
        for (CapturedBiometricDto pendingCapture : pendingCaptures) {
            if (secugenManager.matchTemplate(pendingCapture.getTemplate(), scannedTemplate)) {
                return true;
            }
        }
        return false;
    }

    private FingerprintMatch firstMatch(List<IndexedTemplate> candidates, byte[] scannedTemplate) {
        for (IndexedTemplate candidate : candidates) {
            if (secugenManager.matchTemplate(candidate.getTemplate(), scannedTemplate)) {
                return FingerprintMatch.of(candidate);
            }
        }
        return FingerprintMatch.NO_MATCH;
    }
}
