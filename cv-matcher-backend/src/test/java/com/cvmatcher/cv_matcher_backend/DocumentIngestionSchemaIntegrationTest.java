package com.cvmatcher.cv_matcher_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentIngestionSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test void appliesV10DocumentTablesAndSafeJobCounters() {
        assertEquals(1L, jdbc.queryForObject("select count(*) from flyway_schema_history where version='10' and success", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from information_schema.tables where table_name='candidate_document'", Long.class));
        assertEquals(4L, jdbc.queryForObject("select count(*) from information_schema.columns where table_name='matching_job' and column_name in ('accepted_document_count','ignored_document_count','quarantined_document_count','ingestion_completed_at')", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_name='candidate_document' and constraint_name='chk_candidate_document_available'", Long.class));
        var fields = java.util.Arrays.stream(com.cvmatcher.cv_matcher_backend.job.application.JobService.JobDetail.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertTrue(fields.containsAll(java.util.Set.of("acceptedDocumentCount", "ignoredDocumentCount", "quarantinedDocumentCount", "ingestionCompletedAt")));
        assertFalse(fields.stream().anyMatch(field -> field.toLowerCase().contains("storage") || field.toLowerCase().contains("hash") || field.toLowerCase().contains("attachment")));
    }
}
