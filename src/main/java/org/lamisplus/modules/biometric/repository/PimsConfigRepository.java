package org.lamisplus.modules.biometric.repository;

import org.lamisplus.modules.biometric.domain.PimsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PimsConfigRepository extends JpaRepository<PimsConfig, Long> {
	List<PimsConfig> findAllByArchived(Integer archived);

	Optional<PimsConfig> findFirstByArchived(Integer archived);
}
