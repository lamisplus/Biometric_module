package org.lamisplus.modules.biometric.services;

import lombok.extern.slf4j.Slf4j;
import org.lamisplus.modules.biometric.domain.Biometric;
import org.lamisplus.modules.biometric.domain.dto.IndexedTemplate;
import org.lamisplus.modules.biometric.repository.BiometricRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BiometricTemplateIndex {
    static final int LENGTH_TOLERANCE = 200;

    private static final String INDEX_URL =
            "jdbc:h2:mem:biometric_template_index;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final long SYNCHRONISE_INTERVAL_MILLIS = 2000L;
    private static final long WATERMARK_OVERLAP_SECONDS = 2L;

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS facility_template (" +
                    "biometric_id VARCHAR(64) NOT NULL PRIMARY KEY, " +
                    "facility_id BIGINT NOT NULL, " +
                    "person_uuid VARCHAR(64) NOT NULL, " +
                    "recapture INT NOT NULL, " +
                    "template_type VARCHAR(64), " +
                    "template_length INT NOT NULL, " +
                    "template VARBINARY(8192) NOT NULL)";
    private static final String CREATE_LENGTH_INDEX =
            "CREATE INDEX IF NOT EXISTS ix_facility_template_length ON facility_template (facility_id, template_length)";
    private static final String CREATE_PERSON_INDEX =
            "CREATE INDEX IF NOT EXISTS ix_facility_template_person ON facility_template (facility_id, person_uuid, recapture, template_length)";

    private static final String UPSERT =
            "MERGE INTO facility_template (biometric_id, facility_id, person_uuid, recapture, template_type, " +
                    "template_length, template) KEY (biometric_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE_BY_ID = "DELETE FROM facility_template WHERE biometric_id = ?";
    private static final String DELETE_BY_FACILITY = "DELETE FROM facility_template WHERE facility_id = ?";

    private static final String SELECT_COLUMNS =
            "SELECT biometric_id, person_uuid, template_type, recapture, template FROM facility_template ";
    private static final String FACILITY_CANDIDATES = SELECT_COLUMNS +
            "WHERE facility_id = ? AND template_length <= ? ORDER BY ABS(template_length - ?), person_uuid, biometric_id";
    private static final String PERSON_CANDIDATES = SELECT_COLUMNS +
            "WHERE facility_id = ? AND person_uuid = ? AND recapture = ? AND template_length <= ? " +
            "ORDER BY CASE WHEN template_type = ? THEN 0 ELSE 1 END, ABS(template_length - ?), biometric_id";

    private final BiometricRepository biometricRepository;
    private final Map<Long, Long> lastSynchronisedAt = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> watermark = new ConcurrentHashMap<>();

    private Connection keepAlive;

    @Autowired
    public BiometricTemplateIndex(BiometricRepository biometricRepository) {
        this.biometricRepository = biometricRepository;
    }

    @PostConstruct
    void open() {
        try {
            Class.forName("org.h2.Driver");
            keepAlive = DriverManager.getConnection(INDEX_URL);
            try (Statement statement = keepAlive.createStatement()) {
                statement.execute(CREATE_TABLE);
                statement.execute(CREATE_LENGTH_INDEX);
                statement.execute(CREATE_PERSON_INDEX);
            }
        } catch (SQLException | ClassNotFoundException exception) {
            log.error("Could not open the biometric template index", exception);
        }
    }

    @PreDestroy
    void close() {
        closeQuietly(keepAlive);
        keepAlive = null;
    }

    public void synchronise(Long facilityId) {
        if (facilityId == null || keepAlive == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastSynchronisedAt.get(facilityId);
        LocalDateTime since = watermark.get(facilityId);
        if (since != null && previous != null && now - previous < SYNCHRONISE_INTERVAL_MILLIS) {
            return;
        }
        try {
            List<Object[]> rows = since == null
                    ? biometricRepository.findTemplatesForIndex(facilityId)
                    : biometricRepository.findTemplatesForIndexModifiedSince(facilityId, since);
            apply(facilityId, rows);
            lastSynchronisedAt.put(facilityId, now);
            watermark.put(facilityId, LocalDateTime.now().minusSeconds(WATERMARK_OVERLAP_SECONDS));
        } catch (Exception exception) {
            log.error("Could not synchronise the biometric template index for facility {}", facilityId, exception);
            invalidate(facilityId);
        }
    }

    public void invalidate(Long facilityId) {
        if (facilityId == null) {
            return;
        }
        lastSynchronisedAt.remove(facilityId);
        watermark.remove(facilityId);
        try (Connection connection = DriverManager.getConnection(INDEX_URL);
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_FACILITY)) {
            statement.setLong(1, facilityId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            log.error("Could not invalidate the biometric template index for facility {}", facilityId, exception);
        }
    }

    public void index(Collection<Biometric> biometrics) {
        if (biometrics == null || biometrics.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(INDEX_URL)) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT);
                 PreparedStatement delete = connection.prepareStatement(DELETE_BY_ID)) {
                for (Biometric biometric : biometrics) {
                    if (biometric.getId() == null || biometric.getFacilityId() == null) {
                        continue;
                    }
                    if (isIndexable(biometric)) {
                        bindUpsert(upsert, biometric.getFacilityId(), biometric.getId(), biometric.getPersonUuid(),
                                biometric.getRecapture(), biometric.getTemplateType(), biometric.getTemplate());
                        upsert.addBatch();
                    } else {
                        delete.setString(1, biometric.getId());
                        delete.addBatch();
                    }
                }
                upsert.executeBatch();
                delete.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            log.error("Could not index captured biometrics", exception);
        }
    }

    public void remove(Collection<String> biometricIds) {
        if (biometricIds == null || biometricIds.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(INDEX_URL);
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_ID)) {
            for (String biometricId : biometricIds) {
                if (biometricId != null) {
                    statement.setString(1, biometricId);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            log.error("Could not remove biometrics from the template index", exception);
        }
    }

    public List<IndexedTemplate> facilityCandidates(Long facilityId, byte[] scannedTemplate) {
        if (facilityId == null || scannedTemplate == null) {
            return new ArrayList<>();
        }
        int scannedLength = scannedTemplate.length;
        try (Connection connection = DriverManager.getConnection(INDEX_URL);
             PreparedStatement statement = connection.prepareStatement(FACILITY_CANDIDATES)) {
            statement.setLong(1, facilityId);
            statement.setInt(2, scannedLength + LENGTH_TOLERANCE);
            statement.setInt(3, scannedLength);
            return read(statement);
        } catch (SQLException exception) {
            log.error("Could not read facility match candidates for facility {}", facilityId, exception);
            return new ArrayList<>();
        }
    }

    public List<IndexedTemplate> personCandidates(Long facilityId, String personUuid, Integer recapture,
                                                  byte[] scannedTemplate, String expectedTemplateType) {
        if (facilityId == null || personUuid == null || recapture == null || scannedTemplate == null) {
            return new ArrayList<>();
        }
        int scannedLength = scannedTemplate.length;
        try (Connection connection = DriverManager.getConnection(INDEX_URL);
             PreparedStatement statement = connection.prepareStatement(PERSON_CANDIDATES)) {
            statement.setLong(1, facilityId);
            statement.setString(2, personUuid);
            statement.setInt(3, recapture);
            statement.setInt(4, scannedLength + LENGTH_TOLERANCE);
            statement.setString(5, expectedTemplateType);
            statement.setInt(6, scannedLength);
            return read(statement);
        } catch (SQLException exception) {
            log.error("Could not read match candidates for person {}", personUuid, exception);
            return new ArrayList<>();
        }
    }

    private void apply(Long facilityId, List<Object[]> rows) throws SQLException {
        try (Connection connection = DriverManager.getConnection(INDEX_URL)) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT);
                 PreparedStatement delete = connection.prepareStatement(DELETE_BY_ID)) {
                for (Object[] row : rows) {
                    String biometricId = (String) row[0];
                    byte[] template = (byte[]) row[4];
                    Integer archived = toInteger(row[5]);
                    if (biometricId == null) {
                        continue;
                    }
                    if (template != null && template.length > 0 && (archived == null || archived == 0)) {
                        bindUpsert(upsert, facilityId, biometricId, (String) row[1], toInteger(row[3]),
                                (String) row[2], template);
                        upsert.addBatch();
                    } else {
                        delete.setString(1, biometricId);
                        delete.addBatch();
                    }
                }
                upsert.executeBatch();
                delete.executeBatch();
            }
            connection.commit();
        }
    }

    private void bindUpsert(PreparedStatement statement, Long facilityId, String biometricId, String personUuid,
                            Integer recapture, String templateType, byte[] template) throws SQLException {
        statement.setString(1, biometricId);
        statement.setLong(2, facilityId);
        statement.setString(3, personUuid);
        statement.setInt(4, recapture == null ? 0 : recapture);
        statement.setString(5, templateType);
        statement.setInt(6, template.length);
        statement.setBytes(7, template);
    }

    private List<IndexedTemplate> read(PreparedStatement statement) throws SQLException {
        List<IndexedTemplate> candidates = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                candidates.add(new IndexedTemplate(resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getInt(4), resultSet.getBytes(5)));
            }
        }
        return candidates;
    }

    private boolean isIndexable(Biometric biometric) {
        return biometric.getTemplate() != null
                && biometric.getTemplate().length > 0
                && biometric.getPersonUuid() != null
                && (biometric.getArchived() == null || biometric.getArchived() == 0);
    }

    private Integer toInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                log.warn("Could not close the biometric template index connection", exception);
            }
        }
    }
}
