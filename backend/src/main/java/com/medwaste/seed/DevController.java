package com.medwaste.seed;

import com.medwaste.common.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 开发/验收用：重置播种、模拟器读数。 */
@RestController
@RequestMapping("/api")
public class DevController {
    private final SeedService seed;
    private final Db db;

    @Value("${app.weight-tolerance-kg:0.5}")
    private double tolerance;

    public DevController(SeedService seed, Db db) {
        this.seed = seed;
        this.db = db;
    }

    @PostMapping("/dev/reset")
    public Map<String, Object> reset() {
        seed.reset();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("message", "已重置为虚构基线数据（机构/账号/资质/C1-C6/P1-P2/称重仪）");
        return r;
    }

    @GetMapping("/meta/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "medwaste-backend", "time", System.currentTimeMillis());
    }

    @GetMapping("/meta/rules")
    public Map<String, Object> rules() {
        return seed.ruleInfo(tolerance);
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return com.medwaste.common.Views.list(
                db.list("SELECT code, name, fictional_rule FROM categories ORDER BY id"));
    }

    @GetMapping("/orgs")
    public List<Map<String, Object>> orgs(@RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return com.medwaste.common.Views.list(db.list("""
                    SELECT o.id, o.code, o.name, o.org_type,
                      (SELECT json_agg(json_build_object(
                          'certNo', q.cert_no, 'qualName', q.qual_name, 'scopeNote', q.scope_note,
                          'validFrom', q.valid_from, 'validTo', q.valid_to, 'status', q.status)
                        ORDER BY q.id)
                       FROM qualifications q WHERE q.org_id = o.id) AS qualifications
                    FROM orgs o WHERE o.org_type = ? ORDER BY o.id
                    """, type));
        }
        return com.medwaste.common.Views.list(db.list("""
                SELECT o.id, o.code, o.name, o.org_type,
                  (SELECT count(*) FROM qualifications q WHERE q.org_id = o.id AND q.status='VALID') AS valid_qual_count
                FROM orgs o ORDER BY o.id
                """));
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles(@RequestParam(required = false) String orgCode) {
        if (orgCode != null) {
            return com.medwaste.common.Views.list(db.list("""
                    SELECT v.id, v.plate_no, v.driver_name, v.status,
                           o.name AS org_name, o.code AS org_code
                    FROM vehicles v LEFT JOIN orgs o ON o.id = v.transporter_org_id
                    WHERE o.code = ? ORDER BY v.id
                    """, orgCode));
        }
        return com.medwaste.common.Views.list(db.list("""
                SELECT v.id, v.plate_no, v.driver_name, v.status,
                       o.name AS org_name, o.code AS org_code
                FROM vehicles v LEFT JOIN orgs o ON o.id = v.transporter_org_id ORDER BY v.id
                """));
    }
}
