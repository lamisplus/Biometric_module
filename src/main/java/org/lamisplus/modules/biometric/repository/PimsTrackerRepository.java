package org.lamisplus.modules.biometric.repository;

import org.lamisplus.modules.biometric.domain.PimsTracker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PimsTrackerRepository extends JpaRepository<PimsTracker, String> {
	Optional<PimsTracker> getPimsTrackerByPersonUuidAndFacilityIdAndArchived(String uuid, Long facilityId, int archived);

	@Query("SELECT p FROM PimsTracker p WHERE p.isVerified = ?1")
	List<PimsTracker> findAllByVerification(Boolean isVerified);
}
