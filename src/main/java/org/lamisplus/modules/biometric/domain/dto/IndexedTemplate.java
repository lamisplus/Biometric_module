package org.lamisplus.modules.biometric.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IndexedTemplate {
    private String biometricId;
    private String personUuid;
    private String templateType;
    private Integer recapture;
    private byte[] template;
}
