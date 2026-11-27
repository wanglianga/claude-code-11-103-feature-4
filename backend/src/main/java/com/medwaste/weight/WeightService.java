package com.medwaste.weight;

import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 重量证据组织：
 * - SEAL 封箱自重 / HANDOVER_JOINT 交接复称 / PLANT_CHECK 到厂核实 / SUPPLEMENT 差异补称
 * - BOX_DIRECT 逐箱证据保留各箱重量；PALLET_COMBINED 仅托总量，不产生分箱净重
 * - 同一 logicalId 多版本：旧称量修订只新增版本，旧记录保留为 SUPERSEDED
 */
@Service
public class WeightService {
    private final Db db;

    @Value("${app.weight-tolerance-kg:0.5}")
    private double tolerance;

    public WeightService(Db db) {
        this.db = db;
    }

    public BigDecimal tolerance() {
        return BigDecimal.valueOf(tolerance);
    }

    public static BigDecimal bd(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    public static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    /** 一条重量记录的写入参数（字段较多，用公有字段对象）。 */
    public static class W {
        public String status = "PROPOSED";
        public String scope, stage, mode;
        public Long boxRoundId, palletLoadId, manifestId;
        public String boxCode, subjectLabel, deviceCode, recordedBy, confirmedBy;
        public BigDecimal gross, tareBox, tarePallet, net, expectedNet, diff;
        public Integer bagCount, version;
        public String logicalId, revisionReason;
    }

    public Map<String, Object> evaluate(BigDecimal gross, BigDecimal tareBox,
                                        BigDecimal tarePallet, BigDecimal expectedNet) {
        BigDecimal net = nz(gross).subtract(nz(tareBox)).subtract(nz(tarePallet))
                .setScale(3, RoundingMode.HALF_UP);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("net", net);
        if (expectedNet != null) {
            BigDecimal diff = net.subtract(expectedNet).setScale(3, RoundingMode.HALF_UP);
            r.put("diff", diff);
            r.put("withinTolerance", diff.abs().compareTo(tolerance()) <= 0);
        } else {
            r.put("diff", null);
            r.put("withinTolerance", true);
        }
        return r;
    }

    public long create(W w) {
        String logicalId = w.logicalId != null ? w.logicalId : Codes.next("WG");
        int version = w.version != null ? w.version : 1;
        return db.insert("""
                INSERT INTO weight_records(logical_id,version,status,scope,stage,mode,
                  box_round_id,box_code,pallet_load_id,manifest_id,subject_label,
                  gross_kg,tare_box_kg,tare_pallet_kg,net_kg,expected_net_kg,diff_kg,bag_count,
                  device_code,revision_reason,recorded_by_name)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                logicalId, version, w.status, w.scope, w.stage, w.mode,
                w.boxRoundId, w.boxCode, w.palletLoadId, w.manifestId, w.subjectLabel,
                w.gross, w.tareBox, w.tarePallet, w.net, w.expectedNet, w.diff, w.bagCount,
                w.deviceCode, w.revisionReason, w.recordedBy);
    }

    public Map<String, Object> get(long id) {
        return Views.of(db.one("SELECT * FROM weight_records WHERE id = ?", id));
    }

    public List<Map<String, Object>> forRound(long roundId) {
        return Views.list(db.list(
                "SELECT * FROM weight_records WHERE box_round_id = ? ORDER BY id", roundId));
    }

    public List<Map<String, Object>> forManifest(long manifestId) {
        return Views.list(db.list(
                "SELECT * FROM weight_records WHERE manifest_id = ? ORDER BY id", manifestId));
    }

    public List<Map<String, Object>> forPalletLoad(long loadId) {
        return Views.list(db.list(
                "SELECT * FROM weight_records WHERE pallet_load_id = ? ORDER BY id", loadId));
    }

    /**
     * 旧称量修订：新增版本，不覆盖/删除历史。
     * 容差内：旧版本 SUPERSEDED，新版本 EFFECTIVE，认可重量更新。
     * 超容差：旧 EFFECTIVE 保留，新版本 DISPUTED，认可重量不变，由调用方挂差异。
     */
    public Map<String, Object> revise(long weightId, BigDecimal newGross, String reason, String userName) {
        Map<String, Object> old = db.one("SELECT * FROM weight_records WHERE id = ?", weightId);
        String logicalId = (String) old.get("logical_id");
        Map<String, Object> latest = db.one(
                "SELECT * FROM weight_records WHERE logical_id = ? ORDER BY version DESC LIMIT 1", logicalId);
        int nextVersion = ((Number) latest.get("version")).intValue() + 1;

        BigDecimal tareBox = bd(latest.get("tare_box_kg"));
        BigDecimal tarePallet = bd(latest.get("tare_pallet_kg"));
        BigDecimal expected = bd(latest.get("expected_net_kg"));
        Map<String, Object> ev = evaluate(newGross, tareBox, tarePallet, expected);
        BigDecimal net = (BigDecimal) ev.get("net");
        BigDecimal diff = (BigDecimal) ev.get("diff");
        boolean within = (boolean) ev.get("withinTolerance");

        W w = new W();
        w.logicalId = logicalId;
        w.version = nextVersion;
        w.status = within ? "EFFECTIVE" : "DISPUTED";
        w.scope = (String) latest.get("scope");
        w.stage = (String) latest.get("stage");
        w.mode = (String) latest.get("mode");
        w.boxRoundId = latest.get("box_round_id") == null ? null : ((Number) latest.get("box_round_id")).longValue();
        w.boxCode = (String) latest.get("box_code");
        w.palletLoadId = latest.get("pallet_load_id") == null ? null : ((Number) latest.get("pallet_load_id")).longValue();
        w.manifestId = latest.get("manifest_id") == null ? null : ((Number) latest.get("manifest_id")).longValue();
        w.subjectLabel = (String) latest.get("subject_label");
        w.gross = newGross;
        w.tareBox = tareBox;
        w.tarePallet = tarePallet;
        w.net = net;
        w.expectedNet = expected;
        w.diff = diff;
        w.bagCount = latest.get("bag_count") == null ? null : ((Number) latest.get("bag_count")).intValue();
        w.deviceCode = (String) latest.get("device_code");
        w.revisionReason = reason;
        w.recordedBy = userName;
        Long newId = create(w);

        if (within) {
            db.update("UPDATE weight_records SET status='SUPERSEDED' WHERE logical_id=? AND id<>? AND status='EFFECTIVE'",
                    logicalId, newId);
            if (w.boxRoundId != null && "BOX".equals(w.scope)) {
                db.update("UPDATE box_rounds SET recognized_net_kg=? WHERE id=?", net, w.boxRoundId);
            }
            if (w.palletLoadId != null && "PALLET".equals(w.scope)) {
                db.update("UPDATE pallet_loads SET recognized_total_net_kg=? WHERE id=?", net, w.palletLoadId);
            }
            if (w.manifestId != null) recomputeManifestTotal(w.manifestId);
        }
        return get(newId);
    }

    /**
     * 按有效重量证据重算交接认可总净重，杜绝袋/箱/托重复累计：
     * 有效合托记录覆盖的箱不再累加其逐箱重量。
     */
    public Map<String, Object> recomputeManifestTotal(long manifestId) {
        List<Map<String, Object>> rows = db.list("""
                SELECT mode, pallet_load_id, box_round_id, net_kg
                FROM weight_records
                WHERE manifest_id=? AND stage='HANDOVER_JOINT' AND status='EFFECTIVE'
                """, manifestId);

        BigDecimal palletSum = BigDecimal.ZERO;
        BigDecimal boxSum = BigDecimal.ZERO;
        Set<Long> palletLoadIds = new HashSet<>();
        Set<Long> coveredRounds = new HashSet<>();

        for (Map<String, Object> r : rows) {
            String mode = (String) r.get("mode");
            BigDecimal net = bd(r.get("net_kg"));
            if ("PALLET_COMBINED".equals(mode)) {
                palletSum = palletSum.add(nz(net));
                Long loadId = ((Number) r.get("pallet_load_id")).longValue();
                palletLoadIds.add(loadId);
                Map<String, Object> load = db.one(
                        "SELECT declared_round_ids FROM pallet_loads WHERE id=?", loadId);
                Object ids = load.get("declared_round_ids");
                if (ids != null) {
                    try {
                        List<Object> list = JsonxRead(ids);
                        for (Object o : list) coveredRounds.add(((Number) o).longValue());
                    } catch (Exception ignore) {}
                }
            }
        }
        for (Map<String, Object> r : rows) {
            if ("BOX_DIRECT".equals(r.get("mode"))) {
                Long roundId = ((Number) r.get("box_round_id")).longValue();
                if (!coveredRounds.contains(roundId)) boxSum = boxSum.add(nz(bd(r.get("net_kg"))));
            }
        }
        BigDecimal total = palletSum.add(boxSum).setScale(3, RoundingMode.HALF_UP);
        String basis;
        if (palletSum.signum() > 0 && boxSum.signum() > 0) basis = "MIXED";
        else if (palletSum.signum() > 0) basis = "PALLET_COMBINED";
        else if (boxSum.signum() > 0) basis = "BOX_DIRECT";
        else basis = null;

        if (basis == null) {
            db.update("UPDATE manifests SET total_recognized_net_kg=NULL, weight_basis=NULL WHERE id=?", manifestId);
        } else {
            db.update("UPDATE manifests SET total_recognized_net_kg=?, weight_basis=? WHERE id=?",
                    total, basis, manifestId);
        }
        return Map.of("totalNetKg", total, "basis", basis == null ? "NONE" : basis,
                "palletCount", palletLoadIds.size(), "coveredRounds", coveredRounds.size());
    }

    @SuppressWarnings("unchecked")
    private List<Object> JsonxRead(Object pg) throws Exception {
        String s;
        if ("org.postgresql.util.PGobject".equals(pg.getClass().getName())) {
            s = (String) pg.getClass().getMethod("getValue").invoke(pg);
        } else {
            s = pg.toString();
        }
        return com.medwaste.common.Jsonx.MAPPER.readValue(s, List.class);
    }

    /**
     * 差异结案“认可实测”：取每箱最近一次补称(优先)或到厂核实记录，
     * 更新分箱认可重量，并把委托单认可总量改为实测合计（不回改历史交接证据）。
     */
    public BigDecimal applyMeasuredToRecognized(long manifestId) {
        List<Map<String, Object>> items = db.list(
                "SELECT box_round_id FROM manifest_items WHERE manifest_id=? ORDER BY id", manifestId);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            long roundId = ((Number) it.get("box_round_id")).longValue();
            Map<String, Object> m = db.maybeOne("""
                    SELECT net_kg FROM weight_records
                    WHERE box_round_id=? AND stage='SUPPLEMENT'
                    ORDER BY id DESC LIMIT 1
                    """, roundId).or(() -> db.maybeOne("""
                    SELECT net_kg FROM weight_records
                    WHERE box_round_id=? AND stage='PLANT_CHECK'
                    ORDER BY id DESC LIMIT 1
                    """, roundId)).orElse(null);
            if (m != null) {
                BigDecimal net = bd(m.get("net_kg"));
                db.update("UPDATE box_rounds SET recognized_net_kg=? WHERE id=?", net, roundId);
                total = total.add(nz(net));
            } else {
                Map<String, Object> r = db.one("SELECT recognized_net_kg FROM box_rounds WHERE id=?", roundId);
                total = total.add(nz(bd(r.get("recognized_net_kg"))));
            }
        }
        total = total.setScale(3, RoundingMode.HALF_UP);
        db.update("UPDATE manifests SET total_recognized_net_kg=?, weight_basis='PLANT_ADJUSTED' WHERE id=?",
                total, manifestId);
        return total;
    }

    /** 防按袋数比例摊造：任何“比例估算每箱净重”的请求都直接拒绝。 */
    public static ApiException fabricationForbidden() {
        return ApiException.badRequest("FABRICATION_FORBIDDEN",
                "合托合称只证明托内总量；出现差异必须逐箱补称取得真实重量，禁止按袋数比例摊造每箱净重");
    }
}
