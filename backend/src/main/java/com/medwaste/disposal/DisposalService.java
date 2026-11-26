package com.medwaste.disposal;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Jsonx;
import com.medwaste.common.Views;
import com.medwaste.custody.CustodyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 处置批次与完成回执：
 * 只有对应批次存在 VALID 回执时，委托才算完成；
 * 回执撤回/丢失不删历史，新增复核任务，委托回到 REVIEW_REQUIRED。
 */
@Service
public class DisposalService {
    private final Db db;
    private final CustodyService custody;

    public DisposalService(Db db, CustodyService custody) {
        this.db = db;
        this.custody = custody;
    }

    public List<Map<String, Object>> batches() {
        return Views.list(db.list("SELECT * FROM disposal_batches ORDER BY id DESC"));
    }

    public Map<String, Object> batch(String code) {
        Map<String, Object> b = Views.of(db.one("SELECT * FROM disposal_batches WHERE code=?", code));
        long id = ((Number) b.get("id")).longValue();
        b.put("receipts", Views.list(db.list(
                "SELECT * FROM disposal_receipts WHERE batch_id=? ORDER BY id", id)));
        return b;
    }

    public List<Map<String, Object>> receipts(String batchCode) {
        if (batchCode != null && !batchCode.isBlank()) {
            return Views.list(db.list("""
                    SELECT r.* FROM disposal_receipts r JOIN disposal_batches b ON b.id=r.batch_id
                    WHERE b.code=? ORDER BY r.id
                    """, batchCode));
        }
        return Views.list(db.list("SELECT * FROM disposal_receipts ORDER BY id DESC"));
    }

    public List<Map<String, Object>> reviewTasks(String status) {
        if ("OPEN".equalsIgnoreCase(status)) {
            return Views.list(db.list("SELECT * FROM review_tasks WHERE status='OPEN' ORDER BY id DESC"));
        }
        return Views.list(db.list("SELECT * FROM review_tasks ORDER BY id DESC"));
    }

    public List<Map<String, Object>> mandates() {
        return Views.list(db.list("SELECT * FROM mandates ORDER BY id DESC"));
    }

    @Transactional
    public Map<String, Object> createBatch(List<String> manifestCodes, AppUser user) {
        if (manifestCodes == null || manifestCodes.isEmpty()) {
            throw ApiException.badRequest("MANIFESTS_REQUIRED", "至少选择一张已签收交接单组批");
        }
        List<Long> ids = new ArrayList<>();
        for (String code : manifestCodes) {
            Map<String, Object> m = db.one("SELECT * FROM manifests WHERE code=?", code);
            if (!"SIGNED".equals(m.get("status"))) {
                throw ApiException.conflict("MANIFEST_NOT_SIGNED", "交接单 " + code + " 尚未签收，不能组批处置");
            }
            ids.add(((Number) m.get("id")).longValue());
        }
        String code = Codes.next("PC");
        long id = db.insert("""
                INSERT INTO disposal_batches(code,status,manifest_ids,manifest_codes,created_by_name)
                VALUES (?, 'CREATED', ?::jsonb, ?::jsonb, ?)
                """, code, Jsonx.write(ids), Jsonx.write(manifestCodes), user.displayName());
        return batch(code);
    }

    @Transactional
    public Map<String, Object> perform(String batchCode, String deviceCode, String method, AppUser user) {
        Map<String, Object> b = db.one("SELECT * FROM disposal_batches WHERE code=?", batchCode);
        db.update("""
                UPDATE disposal_batches SET status='DISPOSED', device_code=?, disposal_method=?,
                  performed_by_name=?, performed_at=now()
                WHERE id=?
                """, deviceCode, method, user.displayName(), b.get("id"));
        custody.event("BATCH", batchCode, "DISPOSAL_PERFORMED", "PLANT", "PLANT",
                user.orgId(), user.displayName(),
                "处置模拟器执行处置：" + method + " / 设备 " + deviceCode);
        return batch(batchCode);
    }

    @Transactional
    public Map<String, Object> issueReceipt(String batchCode, AppUser user) {
        Map<String, Object> b = db.one("SELECT * FROM disposal_batches WHERE code=? FOR UPDATE", batchCode);
        if (!"DISPOSED".equals(b.get("status"))) {
            throw ApiException.conflict("BATCH_NOT_DISPOSED", "批次尚未通过处置设备模拟器完成处置，不能签发完成回执");
        }
        int seq = db.jdbc().queryForObject(
                "SELECT count(*) FROM disposal_receipts WHERE batch_id=?", Integer.class, b.get("id")) + 1;
        String receiptCode = Codes.next("HZ");
        long receiptId = db.insert("""
                INSERT INTO disposal_receipts(code,batch_id,batch_code,status,seq_in_batch,
                  disposal_method,issued_by_name)
                VALUES (?,?,?, 'VALID', ?, ?, ?)
                """, receiptCode, b.get("id"), batchCode, seq, b.get("disposal_method"), user.displayName());

        refreshMandates(((Number) b.get("id")).longValue());
        custody.event("RECEIPT", receiptCode, "RECEIPT_ISSUED", "PLANT", "PLANT",
                user.orgId(), user.displayName(), "签发处置完成回执（批次 " + batchCode + "）");
        return Views.of(db.one("SELECT * FROM disposal_receipts WHERE id=?", receiptId));
    }

    /** 撤回或丢失：回执失效 + 新增复核任务 + 委托状态重算；历史行保留。 */
    @Transactional
    public Map<String, Object> invalidateReceipt(String receiptCode, boolean lost, String reason, AppUser user) {
        Map<String, Object> r = db.one("SELECT * FROM disposal_receipts WHERE code=? FOR UPDATE", receiptCode);
        if (!"VALID".equals(r.get("status"))) {
            throw ApiException.conflict("RECEIPT_NOT_VALID",
                    "回执当前状态 " + r.get("status") + "，不能再次" + (lost ? "登记丢失" : "撤回"));
        }
        String newStatus = lost ? "LOST" : "WITHDRAWN";
        String taskType = lost ? "RECEIPT_LOST" : "RECEIPT_WITHDRAWN";
        db.update("UPDATE disposal_receipts SET status=?, state_changed_at=now(), reason=? WHERE id=?",
                newStatus, reason, r.get("id"));

        String taskCode = Codes.next("FH");
        db.insert("""
                INSERT INTO review_tasks(code,task_type,status,receipt_id,receipt_code,batch_id,batch_code,reason)
                VALUES (?,?,'OPEN',?,?,?,?,?)
                """, taskCode, taskType, r.get("id"), receiptCode, r.get("batch_id"), r.get("batch_code"), reason);

        refreshMandates(((Number) r.get("batch_id")).longValue());
        custody.event("RECEIPT", receiptCode, lost ? "RECEIPT_LOST" : "RECEIPT_WITHDRAWN",
                "PLANT", "PLANT", user.orgId(), user.displayName(),
                (lost ? "回执丢失" : "回执撤回") + "，已新增复核任务 " + taskCode + "；历史回执与真实交接事实保留");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receipt", Views.of(db.one("SELECT * FROM disposal_receipts WHERE id=?", r.get("id"))));
        result.put("reviewTask", Views.of(db.one("SELECT * FROM review_tasks WHERE code=?", taskCode)));
        return result;
    }

    @Transactional
    public Map<String, Object> handleReview(String taskCode, String note, AppUser user) {
        Map<String, Object> t = db.one("SELECT * FROM review_tasks WHERE code=? FOR UPDATE", taskCode);
        if ("DONE".equals(t.get("status"))) {
            throw ApiException.conflict("TASK_DONE", "复核任务已办结");
        }
        db.update("""
                UPDATE review_tasks SET status='DONE', handled_at=now(), handled_by_name=?, handle_note=?
                WHERE id=?
                """, user.displayName(), note, t.get("id"));
        return Views.of(db.one("SELECT * FROM review_tasks WHERE code=?", taskCode));
    }

    /**
     * 重算批次内各交接单对应委托：
     * 仅当存在覆盖该交接单的 VALID 回执时 COMPLETED，否则 REVIEW_REQUIRED。
     */
    private void refreshMandates(long batchId) {
        Map<String, Object> batch = db.one("SELECT manifest_ids FROM disposal_batches WHERE id=?", batchId);
        List<Object> manifestIds = Jsonx.readList(
                batch.get("manifest_ids") instanceof String s ? s : pgText(batch.get("manifest_ids")));

        for (Object midObj : manifestIds) {
            long manifestId = ((Number) midObj).longValue();
            boolean covered = hasValidReceiptForManifest(manifestId);
            List<Map<String, Object>> mandates = db.list("SELECT * FROM mandates WHERE manifest_id=?", manifestId);
            for (Map<String, Object> mandate : mandates) {
                if (covered) {
                    Long validId = latestValidReceiptId(manifestId);
                    db.update("UPDATE mandates SET status='COMPLETED', valid_receipt_id=?, updated_at=now() WHERE id=?",
                            validId, mandate.get("id"));
                } else {
                    db.update("UPDATE mandates SET status='REVIEW_REQUIRED', valid_receipt_id=NULL, updated_at=now() WHERE id=?",
                            mandate.get("id"));
                }
            }
        }
    }

    private boolean hasValidReceiptForManifest(long manifestId) {
        return latestValidReceiptId(manifestId) != null;
    }

    private Long latestValidReceiptId(long manifestId) {
        List<Map<String, Object>> valid = db.list("""
                SELECT r.id, b.manifest_ids FROM disposal_receipts r
                JOIN disposal_batches b ON b.id = r.batch_id
                WHERE r.status='VALID'
                """);
        for (Map<String, Object> r : valid) {
            List<Object> ids = Jsonx.readList(pgText(r.get("manifest_ids")));
            for (Object id : ids) {
                if (((Number) id).longValue() == manifestId) {
                    return ((Number) r.get("id")).longValue();
                }
            }
        }
        return null;
    }

    private String pgText(Object pg) {
        if (pg == null) return "[]";
        if ("org.postgresql.util.PGobject".equals(pg.getClass().getName())) {
            try {
                return (String) pg.getClass().getMethod("getValue").invoke(pg);
            } catch (Exception e) {
                return "[]";
            }
        }
        return pg.toString();
    }
}
