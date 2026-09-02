package org.lamisplus.modules.biometric.domain.dto;

import java.util.*;

public class BiometricStoreDTO {
    private static final int MAX_PENDING_PATIENTS = 64;

    private static final Map<Long, List<CapturedBiometricDto>> patientBiometricStore =
            Collections.synchronizedMap(new LinkedHashMap<Long, List<CapturedBiometricDto>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, List<CapturedBiometricDto>> eldest) {
                    return size() > MAX_PENDING_PATIENTS;
                }
            });

    private BiometricStoreDTO() {
    }

    public static List<CapturedBiometricDto> addCapturedBiometrics(Long patientId, CapturedBiometricDto capturedBiometric) {
        if (patientId == null || capturedBiometric == null) {
            return getCapturedBiometrics(patientId);
        }
        synchronized (patientBiometricStore) {
            List<CapturedBiometricDto> capturedBiometrics =
                    patientBiometricStore.computeIfAbsent(patientId, key -> new ArrayList<>());
            removeByTemplateType(capturedBiometrics, capturedBiometric.getTemplateType());
            capturedBiometrics.add(capturedBiometric);
            return new ArrayList<>(capturedBiometrics);
        }
    }

    public static List<CapturedBiometricDto> mergeCapturedBiometrics(Long patientId, Collection<CapturedBiometricDto> capturedBiometrics) {
        if (patientId == null || capturedBiometrics == null || capturedBiometrics.isEmpty()) {
            return getCapturedBiometrics(patientId);
        }
        synchronized (patientBiometricStore) {
            List<CapturedBiometricDto> pending =
                    patientBiometricStore.computeIfAbsent(patientId, key -> new ArrayList<>());
            for (CapturedBiometricDto capturedBiometric : capturedBiometrics) {
                if (capturedBiometric != null && !containsTemplateType(pending, capturedBiometric.getTemplateType())) {
                    pending.add(capturedBiometric);
                }
            }
            return new ArrayList<>(pending);
        }
    }

    public static List<CapturedBiometricDto> getCapturedBiometrics(Long patientId) {
        if (patientId == null) {
            return new ArrayList<>();
        }
        synchronized (patientBiometricStore) {
            List<CapturedBiometricDto> capturedBiometrics = patientBiometricStore.get(patientId);
            return capturedBiometrics == null ? new ArrayList<>() : new ArrayList<>(capturedBiometrics);
        }
    }

    public static boolean hasCapturedBiometrics(Long patientId) {
        return !getCapturedBiometrics(patientId).isEmpty();
    }

    public static boolean removePatient(Long patientId) {
        if (patientId == null) {
            return false;
        }
        synchronized (patientBiometricStore) {
            return patientBiometricStore.remove(patientId) != null;
        }
    }

    public static void removeTemplateType(Long patientId, String templateType) {
        if (patientId == null || templateType == null) {
            return;
        }
        synchronized (patientBiometricStore) {
            List<CapturedBiometricDto> capturedBiometrics = patientBiometricStore.get(patientId);
            if (capturedBiometrics != null) {
                removeByTemplateType(capturedBiometrics, templateType);
                if (capturedBiometrics.isEmpty()) {
                    patientBiometricStore.remove(patientId);
                }
            }
        }
    }

    private static boolean containsTemplateType(List<CapturedBiometricDto> capturedBiometrics, String templateType) {
        for (CapturedBiometricDto capturedBiometric : capturedBiometrics) {
            if (Objects.equals(capturedBiometric.getTemplateType(), templateType)) {
                return true;
            }
        }
        return false;
    }

    private static void removeByTemplateType(List<CapturedBiometricDto> capturedBiometrics, String templateType) {
        if (templateType == null) {
            return;
        }
        capturedBiometrics.removeIf(capturedBiometric -> Objects.equals(capturedBiometric.getTemplateType(), templateType));
    }
}
