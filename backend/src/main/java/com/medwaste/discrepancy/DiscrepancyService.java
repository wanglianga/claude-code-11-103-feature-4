package com.medwaste.discrepancy;

import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import com.medwaste.weight.WeightService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiscrepancyService {
    private final Db db;
    private final WeightService weights;

    public DiscrepancyService(Db db, WeightService weights) {
        this.db = db;
        this.weights = weights;
    }

    public List<Map<String, Object>> list(String status) {
        if ("OPEN".equalsIgnoreCase(status)) {
            return Views.list(db.list(
                    "SELECT * FROM discrepancies WHERE status='OPEN' ORDER BY id DESC"));
        }
        return Views.list(db.list("SELECT * FROM discrepancies ORDER BY id DESC"));
    }

    public Map<String, Object> getByCode(String code) {
        return Views.of(db.one("SELECT * FROM discrepancies WHERE code=?", code));
    }

    public List<Map<String, Object>> openForManifest(long manifestId) {
        return Views.list(db.list(
                "SELECT * FROM discrepancies WHERE manifest_id=? AND status='OPEN' ORDER BY id", manifestId));
    }

    public List<Map<String, Object>> openForSubject(String subjectType, String subjectCode) {
        return Views.list(db.list(
                "SELECT * FROM discrepancies WHERE subject_type=? AND subject_code=? AND status='OPEN'",
                subjectType, subjectCode));
    }

    /** 同主体同类型的未结差异幂等复用，避免重复挂案。 */
    @Transactional
    public Map<String, Object> raise(String type, String subjectType, String subjectCode,
                                    Long manifestId, Long boxRoundId, Long palletLoadId,
                                    BigDecimal expected, BigDecimal observed, BigDecimal diff,
                                    BigDecimal threshold, String title, String description,
                                    String creatorName) {
        var existing = db.maybeOne("""
                SELECT * FROM discrepancies
                WHERE type=? AND status='OPEN' AND subject_type=? AND subject_code=?
                ORDER BY id DESC LIMIT 1
                """, type, subjectType, subjectCode);
        long id;
        if (existing.isPresent()) {
            id = ((Number) existing.get().get("id")).longValue();
            db.update("""
                    UPDATE discrepancies SET expected_value=?, observed_value=?, diff_value=?,
                      threshold_kg=?, description=?
                    WHERE id=?
                    """, expected, observed, diff, threshold, description, id);
        } else {
            id = db.insert("""
                    INSERT INTO discrepancies(code,type,status,subject_type,subject_code,
                      manifest_id,box_round_id,pallet_load_id,expected_value,observed_value,diff_value,
                      threshold_kg,title,description,created_by_name)
                    VALUES (?,?, 'OPEN', ?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Codes.next("DI"), type, subjectType, subjectCode,
                    manifestId, boxRoundId, palletLoadId, expected, observed, diff,
                    threshold, title, description, creatorName);
        }
        return getById(id);
    }

    public Map<String, Object> getById(long id) {
        return Views.of(db.one("SELECT * FROM discrepancies WHERE id=?", id));
    }

    @Transactional
    public Map<String, Object> resolve(String code, String resolutionType, String note, String userName) {
        Map<String, Object> d = db.one("SELECT * FROM discrepancies WHERE code=? FOR UPDATE", code);
        if ("RESOLVED".equals(d.get("status"))) {
            throw ApiException.conflict("DISCREPANCY_ALREADY_RESOLVED", "差异已结案，不能重复处理");
        }
        // 明确禁止按袋数比例摊造箱净重
        if (resolutionType != null && resolutionType.contains("RATIO")) {
            throw WeightService.fabricationForbidden();
        }
        if (resolutionType == null || resolutionType.isBlank()) {
            throw ApiException.badRequest("RESOLUTION_TYPE_REQUIRED", "必须选择差异处理方式");
        }

        Long manifestId = d.get("manifest_id") == null ? null : ((Number) d.get("manifest_id")).longValue();

        if ("ACCEPT_MEASURED".equals(resolutionType)) {
            if (!"WEIGHT_MISMATCH".equals(d.get("type"))) {
                throw ApiException.badRequest("RESOLUTION_TYPE_INVALID", "只有重量差异可以按实测认可结案");
            }
            if (manifestId == null) throw ApiException.badRequest("MANIFEST_MISSING", "差异未关联交接单");
            BigDecimal total = weights.applyMeasuredToRecognized(manifestId);
            if (note == null || note.isBlank()) note = "双方补称/复称后，认可实测总净重 " + total + " kg";
        } else if ("KEEP_DECLARED".equals(resolutionType)) {
            if (note == null || note.isBlank()) note = "经核实维持原认可重量，记录仪表/操作原因";
        } else if ("BOX_HANDLED_SEPARATELY".equals(resolutionType)) {
            if (note == null || note.isBlank()) {
                throw ApiException.badRequest("NOTE_REQUIRED", "分单处理缺箱必须注明另办交接单号/找回情况");
            }
        } else if ("OTHER".equals(resolutionType)) {
            if (note == null || note.isBlank()) {
                throw ApiException.badRequest("NOTE_REQUIRED", "其他处理方式必须填写说明");
            }
        } else {
            throw ApiException.badRequest("UNKNOWN_RESOLUTION_TYPE", "未知处理方式: " + resolutionType);
        }

        db.update("""
                UPDATE discrepancies SET status='RESOLVED', resolution_type=?, resolution_note=?,
                  resolved_by_name=?, resolved_at=now()
                WHERE code=?
                """, resolutionType, note, userName, code);

        // 该单全部未结差异清零且处于厂内待核 → 转为待签收
        if (manifestId != null) {
            Integer open = db.jdbc().queryForObject(
                    "SELECT count(*) FROM discrepancies WHERE manifest_id=? AND status='OPEN'",
                    Integer.class, manifestId);
            if (open != null && open == 0) {
                db.update("""
                        UPDATE manifests SET status='READY_TO_SIGN'
                        WHERE id=? AND status='IN_PLANT_PENDING_CHECK'
                        """, manifestId);
            }
        }
        return getByCode(code);
    }

    /** 追踪页用：未结差异精简结构。 */
    public List<Map<String, Object>> publicOpen() {
        List<Map<String, Object>> rows = Views.list(db.list("""
                SELECT code, type, status, subject_type, subject_code,
                       title, description, expected_value,
                       observed_value, diff_value,
                       threshold_kg, created_at, manifest_id
                FROM discrepancies WHERE status='OPEN' ORDER BY id DESC
                """));
        return rows;
    }

    public static String typeLabel(String type) {
        return switch (type) {
            case "WEIGHT_MISMATCH" -> "重量差异";
            case "MISSING_BOX" -> "合托缺箱/清单不符";
            case "DUPLICATE_BAG" -> "袋码重复";
            case "SEAL_BROKEN" -> "封条异常";
            case "QUALIFICATION_INVALID" -> "资质异常";
            default -> type;
        };
    }

    public Map<String, Object> summary(long manifestId) {
        List<Map<String, Object>> open = openForManifest(manifestId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("openCount", open.size());
        m.put("openItems", open);
        return m;
    }
}
