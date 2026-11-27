package com.medwaste.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JdbcTemplate 薄封装，统一查询与自增主键回填。 */
@Component
public class Db {
    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() { return jdbc; }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Optional<Map<String, Object>> maybeOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> one(String sql, Object... args) {
        return maybeOne(sql, args).orElseThrow(() ->
                ApiException.notFound("ROW_NOT_FOUND", "数据不存在: " + sql));
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** 插入并返回 BIGSERIAL 主键（PostgreSQL RETURNING）。 */
    public long insert(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
        if (id == null) throw new IllegalStateException("插入未返回主键");
        return id;
    }

    /** 行锁：必须在事务内调用。 */
    public Map<String, Object> lockBox(String boxCode) {
        return one("SELECT * FROM boxes WHERE code = ? FOR UPDATE", boxCode);
    }

    public Map<String, Object> lockManifest(String code) {
        return one("SELECT * FROM manifests WHERE code = ? FOR UPDATE", code);
    }
}
