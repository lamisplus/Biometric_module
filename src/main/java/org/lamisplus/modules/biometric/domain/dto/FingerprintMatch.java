package org.lamisplus.modules.biometric.domain.dto;

import lombok.Getter;

@Getter
public class FingerprintMatch {
    public static final FingerprintMatch NO_MATCH = new FingerprintMatch(null, null, null, null);

    private final String personUuid;
    private final String biometricId;
    private final String templateType;
    private final Integer recapture;

    public FingerprintMatch(String personUuid, String biometricId, String templateType, Integer recapture) {
        this.personUuid = personUuid;
        this.biometricId = biometricId;
        this.templateType = templateType;
        this.recapture = recapture;
    }

    public static FingerprintMatch of(IndexedTemplate indexedTemplate) {
        return new FingerprintMatch(indexedTemplate.getPersonUuid(), indexedTemplate.getBiometricId(),
                indexedTemplate.getTemplateType(), indexedTemplate.getRecapture());
    }

    public boolean isMatched() {
        return personUuid != null;
    }
}
