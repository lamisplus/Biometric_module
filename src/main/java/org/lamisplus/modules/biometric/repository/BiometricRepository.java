package org.lamisplus.modules.biometric.repository;

import org.lamisplus.modules.biometric.domain.Biometric;
import org.lamisplus.modules.biometric.domain.ClientIdentificationProject;
import org.lamisplus.modules.biometric.domain.dto.BiometricPerson;
import org.lamisplus.modules.biometric.domain.dto.GroupedCapturedBiometric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BiometricRepository extends JpaRepository<Biometric, String> {
    List<Biometric> findAllByPersonUuid(String personUuid);

    @Query(value ="SELECT DISTINCT ON (person_uuid) person_uuid, replace_date FROM biometric WHERE replace_date IS NOT NULL AND person_uuid=?1 AND archived = 0", nativeQuery = true)
    Optional<String> findNotNullReplaceDate (String personUuid);
    @Query(value ="SELECT DISTINCT recapture FROM biometric WHERE person_uuid=?1", nativeQuery = true)
    List<String> findRecapturesByPersonUuidAndRecaptures(String personUuid);

    @Query(value ="SELECT MAX(recapture) FROM biometric WHERE person_uuid=?1 AND archived = 0", nativeQuery = true)
    Optional<Integer> findMaxRecapture(String personUuid);
    
    @Query(value ="SELECT COUNT(person_uuid) FROM biometric WHERE person_uuid=?1 AND enrollment_date=?2 AND archived=0", nativeQuery = true)
    Integer getBiometricByDate(String personUuid, LocalDate enrollmentDate);
    
    List<Biometric> findAllByPersonUuidAndRecapture(String personUuid, String recapture);
    @Query(value ="SELECT * FROM biometric WHERE last_modified_date > ?1 AND facility_id=?2", nativeQuery = true)
    public List<Biometric> getAllDueForServerUpload(LocalDateTime dateLastSync, Long facilityId);

    List<Biometric> findAllByFacilityId(Long facilityId);

    @Query(value="SELECT id, person_uuid, template_type, recapture, template, archived " +
            "FROM biometric WHERE facility_id=?1 AND archived=0", nativeQuery = true)
    List<Object[]> findTemplatesForIndex(Long facilityId);

    @Query(value="SELECT id, person_uuid, template_type, recapture, template, archived " +
            "FROM biometric WHERE facility_id=?1 AND last_modified_date > ?2", nativeQuery = true)
    List<Object[]> findTemplatesForIndexModifiedSince(Long facilityId, LocalDateTime since);

    @Query(value="SELECT COUNT(*) FROM biometric WHERE facility_id=?1 AND archived=0", nativeQuery = true)
    long countIndexableForFacility(Long facilityId);

    @Query(value="SELECT id FROM biometric WHERE facility_id=?1 AND archived=0", nativeQuery = true)
    List<String> findIndexableIdsForFacility(Long facilityId);

    @Query(value="SELECT uuid FROM patient_person WHERE id=?1", nativeQuery = true)
    Optional<String> getPersonUuid(Long patientId);

    @Query(value="SELECT template FROM biometric WHERE person_uuid=?1 AND template_type=?2 AND recapture=?3", nativeQuery = true)
    Optional<byte[]> getPersonUuidTemplateRecapture(String personUuid, String templateType, Integer recapture);

    @Query(value="SELECT template FROM biometric WHERE person_uuid=?1 AND recapture=?2", nativeQuery = true)
    List<byte[]> getPersonUuidTemplatesForRecapture(String personUuid, Integer recapture);

    // count is derived per round, not read from the stored column, which drifts once a print is deleted
    @Query(value="SELECT b.recapture AS recapture, " +
            "MAX(b.enrollment_date) AS captureDate, b.person_uuid AS personUuid, " +
            "COUNT(*) AS count, MIN(b.archived) AS archived, MAX(b.replace_date) AS replaceDate " +
            "FROM biometric b " +
            "INNER JOIN patient_person pp ON pp.uuid=b.person_uuid " +
            "WHERE pp.id=?1 AND b.archived != 1 AND pp.archived=0 " +
            "GROUP BY b.recapture, b.person_uuid " +
            "ORDER BY b.recapture DESC", nativeQuery = true)
    List<GroupedCapturedBiometric> getGroupedPersonBiometric (Long patientId);
    
    List<Biometric> findAllByPersonUuidAndRecapture(String personUuid, Integer recapture);

    List<Biometric> findAllByPersonUuidAndRecaptureAndArchived(String personUuid, Integer recapture, Integer archive);
    List<Biometric> findAllByPersonUuidAndDateAndArchived(String personUuid, LocalDate captureDate, Integer archive);

    @Query(value="SELECT id, first_name AS firstName, surname FROM patient_person WHERE uuid=?1", nativeQuery = true)
    Optional<BiometricPerson> getBiometricPerson(String personUuid);

    @Query(value="SELECT id, first_name AS firstName, surname AS surname, other_name AS otherName, " +
            "hospital_number AS hospitalNumber, sex " +
            "FROM patient_person WHERE uuid=?1", nativeQuery = true)
    Optional<ClientIdentificationProject> getBiometricPersonData(String personUuid);

    @Modifying
    @Transactional
    @Query("UPDATE Biometric b SET b.recapture = 0 WHERE b.recapture IS NULL")
    void updateRecaptureNullField();

    @Modifying
    @Transactional
    @Query(value="UPDATE biometric SET count=(SELECT COUNT(*) FROM biometric x " +
            "WHERE x.person_uuid=?1 AND x.recapture=?2 AND x.archived=0) " +
            "WHERE person_uuid=?1 AND recapture=?2 AND archived=0", nativeQuery = true)
    void refreshCapturedCount(String personUuid, Integer recapture);

    @Query(value = "SELECT recapture AS recapture, COUNT(*) AS count FROM biometric " +
            "WHERE person_uuid = ?1 AND archived = 0 GROUP BY recapture", nativeQuery = true)
    List<GroupedCapturedBiometric> getPatientBiometricCount(String personUuid);
}
