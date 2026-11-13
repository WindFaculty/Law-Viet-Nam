package vn.edu.hsf302.lawrag.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.dto.AuditRow;

/** Append-only record of privileged actions. */
@Repository
public class AdminAuditRepository {

    private static final RowMapper<AuditRow> ROW_MAPPER = (resultSet, rowNumber) -> new AuditRow(
            resultSet.getString("actor"),
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getObject("entity_id", UUID.class),
            resultSet.getString("details"),
            resultSet.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AdminAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String actor, String action, String entityType, UUID entityId, String details) {
        jdbcTemplate.update("""
                insert into admin_audit_log (actor, action, entity_type, entity_id, details)
                values (?, ?, ?, ?, ?)
                """, actor, action, entityType, entityId, details);
    }

    public List<AuditRow> recent(int limit) {
        return jdbcTemplate.query("""
                select actor, action, entity_type, entity_id, details, created_at
                from admin_audit_log
                order by created_at desc
                limit ?
                """, ROW_MAPPER, limit);
    }
}
