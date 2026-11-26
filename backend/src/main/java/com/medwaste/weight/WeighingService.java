package com.medwaste.weight;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import com.medwaste.discrepancy.DiscrepancyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 交接两路称重、到厂核实称、差异补称的写入与判定。 */
@Service
public class WeighingService {
    private final Db db;
    private final WeightService weights;
    private final DiscrepancyService discrepancies;

    public WeighingService(Db db, WeightService weights, DiscrepancyService discrepancies) {
        this.db = db;
        this.weights = weights;
        this.discrepancies = discrepancies;
    }

    private Map<String, Object> manifestByCode(String code) {
        return Views.of(db.one("SELECT * FROM manifests WHERE code=?", code));
    }

    private Map<String, Object> roundForManifestItem(long manifestId, String boxQr) {
        Map<String, Object> round;
        if (boxQr != null && boxQr.contains("#R")) {
            round = Views.of(db.one("SELECT * FROM box_rounds WHERE qr_code=?", boxQr.toUpperCase()));
        } else {
            String boxCode = boxQr.toUpperCase();
            round = Views.of(db.one("""
                    SELECT br.* FROM manifest_items mi JOIN box_rounds br ON br.id=mi.box_round_id
                    WHERE mi.manifest_id=? AND mi.box_code=?
                    """, manifestId, boxCode));
        }
        boolean member = db.maybeOne("SELECT id FROM manifest_items WHERE manifest_id=? AND box_round_id=?",
                manifestId, ((Number) round.get("id")).longValue()).isPresent();
        if (!member) throw ApiException.badRequest("BOX_NOT_ON_MANIFEST", "该箱不在本交接单清单内");
        return round;
    }

    /** 交接逐箱复称：先由收运员/一方录入 PROPOSED，再由另一方 confirm。 */
    @Transactional
    public Map<String, Object> proposeHandoverBox(String manifestCode, String boxQr,
                                                  BigDecimal grossKg, String deviceCode, AppUser user) {
        Map<String, Object> m = manifestByCode(manifestCode);
        if (!List.of("DRAFT", "SUBMITTED").contains(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_LOCKED", "交接单当前状态不能再录入交接复称");
        }
        long mid = ((Number) m.get("id")).longValue();
        Map<String, Object> round = roundForManifestItem(mid, boxQr);
        BigDecimal tare = WeightService.bd(round.get("actualTareKg"));
        BigDecimal expected = WeightService.bd(round.get("recognizedNetKg"));
        Map<String, Object> ev = weights.evaluate(grossKg, tare, BigDecimal.ZERO, expected);

        WeightService.W w = new WeightService.W();
        w.status = "PROPOSED";
        w.scope = "BOX";
        w.stage = "HANDOVER_JOINT";
        w.mode = "BOX_DIRECT";
        w.boxRoundId = ((Number) round.get("id")).longValue();
        w.boxCode = (String) round.get("boxCode");
        w.manifestId = mid;
        w.subjectLabel = round.get("qrCode") + " 交接逐箱复称";
        w.gross = grossKg;
        w.tareBox = tare;
        w.net = (BigDecimal) ev.get("net");
        w.expectedNet = expected;
        w.diff = (BigDecimal) ev.get("diff");
        w.bagCount = round.get("bags") == null ? null
                : ((List<?>) round.get("bags")).size();
        w.deviceCode = deviceCode;
        w.recordedBy = user.displayName();
        long id = weights.create(w);
        return weights.get(id);
    }

    /** 合托合称：只形成托总量证据；完整性在共同确认时判定。 */
    @Transactional
    public Map<String, Object> proposePallet(long loadId, BigDecimal grossKg,
                                             String deviceCode, AppUser user) {
        Map<String, Object> load = Views.of(db.one("SELECT * FROM pallet_loads WHERE id=?", loadId));
        long mid = ((Number) load.get("manifestId")).longValue();
        Map<String, Object> m = Views.of(db.one("SELECT * FROM manifests WHERE id=?", mid));
        if (!List.of("DRAFT", "SUBMITTED").contains(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_LOCKED", "交接单当前状态不能再录入合称");
        }
        @SuppressWarnings("unchecked")
        List<Object> declared = (List<Object>) load.get("declaredRoundIds");

        BigDecimal tareBox = BigDecimal.ZERO;
        BigDecimal expectedNet = BigDecimal.ZERO;
        for (Object o : declared) {
            long rid = ((Number) o).longValue();
            Map<String, Object> r = db.one("SELECT actual_tare_kg, recognized_net_kg FROM box_rounds WHERE id=?", rid);
            tareBox = tareBox.add(WeightService.nz(WeightService.bd(r.get("actual_tare_kg"))));
            expectedNet = expectedNet.add(WeightService.nz(WeightService.bd(r.get("recognized_net_kg"))));
        }
        BigDecimal tarePallet = WeightService.bd(load.get("tarePalletKg"));
        Map<String, Object> ev = weights.evaluate(grossKg, tareBox, tarePallet, expectedNet);

        WeightService.W w = new WeightService.W();
        w.status = "PROPOSED";
        w.scope = "PALLET";
        w.stage = "HANDOVER_JOINT";
        w.mode = "PALLET_COMBINED";
        w.palletLoadId = loadId;
        w.manifestId = mid;
        w.subjectLabel = load.get("code") + " 封托合称（仅总净重）";
        w.gross = grossKg;
        w.tareBox = tareBox;
        w.tarePallet = tarePallet;
        w.net = (BigDecimal) ev.get("net");
        w.expectedNet = expectedNet;
        w.diff = (BigDecimal) ev.get("diff");
        w.deviceCode = deviceCode;
        w.recordedBy = user.displayName();
        long id = weights.create(w);
        return weights.get(id);
    }

    /** 共同确认重量；超 0.5kg 挂差异；合托清单不完整则阻止确认并挂缺箱差异。 */
    @Transactional
    public Map<String, Object> confirm(long weightId, AppUser user) {
        Map<String, Object> wRow = db.one("SELECT * FROM weight_records WHERE id=? FOR UPDATE", weightId);
        if (!"PROPOSED".equals(wRow.get("status"))) {
            throw ApiException.conflict("WEIGHT_NOT_PROPOSABLE",
                    "该称量记录状态为 " + wRow.get("status") + "，不能确认（修订请走修订流程）");
        }
        String mode = (String) wRow.get("mode");
        long manifestId = ((Number) wRow.get("manifest_id")).longValue();

        // 合托合称：先验证封箱清单完整（声明 == 实扫）
        if ("PALLET_COMBINED".equals(mode)) {
            long loadId = ((Number) wRow.get("pallet_load_id")).longValue();
            Map<String, Object> load = Views.of(db.one("SELECT * FROM pallet_loads WHERE id=?", loadId));
            @SuppressWarnings("unchecked")
            List<Object> declared = new ArrayList<>((List<Object>) load.get("declaredRoundIds"));
            @SuppressWarnings("unchecked")
            List<Object> scanned = new ArrayList<>((List<Object>) load.getOrDefault("scannedRoundIds", List.of()));
            List<Object> missing = new ArrayList<>(declared);
            missing.removeAll(scanned);
            if (!missing.isEmpty() || scanned.size() != declared.size()) {
                db.update("UPDATE weight_records SET status='HELD' WHERE id=?", weightId);
                db.update("UPDATE pallet_loads SET status='BLOCKED' WHERE id=?", loadId);
                List<String> missingQrs = new ArrayList<>();
                for (Object rid : missing) {
                    Map<String, Object> r = db.one("SELECT qr_code FROM box_rounds WHERE id=?",
                            ((Number) rid).longValue());
                    missingQrs.add((String) r.get("qr_code"));
                }
                Map<String, Object> disc = discrepancies.raise("MISSING_BOX", "PALLET_LOAD",
                        (String) load.get("code"), manifestId, null, loadId,
                        BigDecimal.valueOf(declared.size()), BigDecimal.valueOf(scanned.size()),
                        BigDecimal.valueOf(missing.size()), null,
                        "合托清单缺箱，不能按完整一托共同确认",
                        "声明 " + declared.size() + " 箱，实扫 " + scanned.size() +
                                " 箱，缺少: " + String.join(", ", missingQrs) +
                                "。合称只证明托内总量，须找箱/补扫或安排逐箱补称，禁止按袋数比例摊造箱净重",
                        user.displayName());
                return Map.of("blocked", true, "code", "PALLET_LIST_INCOMPLETE",
                        "message", "封托清单不完整，已转缺箱差异核实；不能完成合托共同确认",
                        "missingQrs", missingQrs,
                        "weight", weights.get(weightId), "discrepancy", disc);
            }
        }

        BigDecimal diff = WeightService.bd(wRow.get("diff_kg"));
        boolean within = diff == null || diff.abs().compareTo(weights.tolerance()) <= 0;

        db.update("""
                UPDATE weight_records SET confirmed_by_name=?, confirmed_at=now(),
                  status=? WHERE id=?
                """, user.displayName(), within ? "EFFECTIVE" : "DISPUTED", weightId);

        Map<String, Object> discrepancy = null;
        if (within) {
            if ("BOX_DIRECT".equals(mode)) {
                long roundId = ((Number) wRow.get("box_round_id")).longValue();
                db.update("UPDATE box_rounds SET recognized_net_kg=? WHERE id=?",
                        wRow.get("net_kg"), roundId);
            } else if ("PALLET_COMBINED".equals(mode)) {
                long loadId = ((Number) wRow.get("pallet_load_id")).longValue();
                db.update("UPDATE pallet_loads SET recognized_total_net_kg=?, status='COMPLETE' WHERE id=?",
                        wRow.get("net_kg"), loadId);
            }
            weights.recomputeManifestTotal(manifestId);
        } else {
            String scope = (String) wRow.get("scope");
            String subjectCode = (String) wRow.get("subject_label");
            discrepancy = discrepancies.raise("WEIGHT_MISMATCH",
                    "PALLET".equals(scope) ? "PALLET_LOAD" : "MANIFEST",
                    "PALLET".equals(scope)
                            ? (String) Views.of(db.one("SELECT code FROM pallet_loads WHERE id=?",
                                    wRow.get("pallet_load_id"))).get("code")
                            : (String) Views.of(db.one("SELECT code FROM manifests WHERE id=?", manifestId)).get("code"),
                    manifestId,
                    wRow.get("box_round_id") == null ? null : ((Number) wRow.get("box_round_id")).longValue(),
                    wRow.get("pallet_load_id") == null ? null : ((Number) wRow.get("pallet_load_id")).longValue(),
                    WeightService.bd(wRow.get("expected_net_kg")),
                    WeightService.bd(wRow.get("net_kg")),
                    diff, weights.tolerance(),
                    ("PALLET".equals(scope) ? "合托总净重" : "逐箱净重") + "复称差 " + diff + " kg 超阈值",
                    "称量项: " + subjectCode + "；认可基准 " + wRow.get("expected_net_kg") +
                            " kg，复称净重 " + wRow.get("net_kg") + " kg，差 " + diff +
                            " kg（阈值 " + weights.tolerance() + " kg），转差异核实",
                    user.displayName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", within);
        result.put("code", within ? "WEIGHT_EFFECTIVE" : "WEIGHT_DISPUTED");
        result.put("weight", weights.get(weightId));
        result.put("manifestTotal", weights.recomputeManifestTotal(manifestId));
        if (discrepancy != null) result.put("discrepancy", discrepancy);
        return result;
    }

    public record PlantReading(String boxQr, Long boxRoundId, BigDecimal grossKg, String deviceCode) {}
    public record PlantWeighReq(List<PlantReading> readings, String deviceCode) {}

    /** 到厂核实称（可逐箱），合计与交接认可总量比较；|差|>0.5 挂重量差异。 */
    @Transactional
    public Map<String, Object> plantWeigh(String manifestCode, PlantWeighReq req, AppUser user) {
        Map<String, Object> m = manifestByCode(manifestCode);
        if (!List.of("AT_PLANT", "IN_PLANT_PENDING_CHECK", "READY_TO_SIGN").contains(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_NOT_AT_PLANT", "只有到厂后的交接单能做厂内核实称量");
        }
        long mid = ((Number) m.get("id")).longValue();
        if (req.readings() == null || req.readings().isEmpty()) {
            throw ApiException.badRequest("READINGS_REQUIRED", "至少提供一箱的到厂称量读数");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        BigDecimal plantTotal = BigDecimal.ZERO;
        for (PlantReading r : req.readings()) {
            Map<String, Object> round;
            if (r.boxRoundId() != null) {
                round = Views.of(db.one("SELECT * FROM box_rounds WHERE id=?", r.boxRoundId()));
            } else {
                round = roundForManifestItem(mid, r.boxQr());
            }
            BigDecimal tare = WeightService.bd(round.get("actualTareKg"));
            Map<String, Object> ev = weights.evaluate(r.grossKg(), tare, BigDecimal.ZERO,
                    WeightService.bd(round.get("recognizedNetKg")));
            WeightService.W w = new WeightService.W();
            w.status = "EFFECTIVE";
            w.scope = "BOX";
            w.stage = "PLANT_CHECK";
            w.mode = "BOX_DIRECT";
            w.boxRoundId = ((Number) round.get("id")).longValue();
            w.boxCode = (String) round.get("boxCode");
            w.manifestId = mid;
            w.subjectLabel = round.get("qrCode") + " 到厂核实称";
            w.gross = r.grossKg();
            w.tareBox = tare;
            w.net = (BigDecimal) ev.get("net");
            w.expectedNet = WeightService.bd(round.get("recognizedNetKg"));
            w.diff = (BigDecimal) ev.get("diff");
            w.deviceCode = r.deviceCode() != null ? r.deviceCode() : req.deviceCode();
            w.recordedBy = user.displayName();
            long id = weights.create(w);
            records.add(weights.get(id));
            plantTotal = plantTotal.add((BigDecimal) ev.get("net"));
        }
        plantTotal = plantTotal.setScale(3, java.math.RoundingMode.HALF_UP);

        BigDecimal expectedTotal = WeightService.bd(m.get("totalRecognizedNetKg"));
        if (expectedTotal == null) {
            expectedTotal = WeightService.bd(db.jdbc().queryForObject("""
                    SELECT COALESCE(sum(br.recognized_net_kg),0) FROM manifest_items mi
                    JOIN box_rounds br ON br.id=mi.box_round_id WHERE mi.manifest_id=?
                    """, BigDecimal.class, mid));
        }
        BigDecimal diff = plantTotal.subtract(expectedTotal).setScale(3, java.math.RoundingMode.HALF_UP);
        Map<String, Object> discrepancy = null;
        if (diff.abs().compareTo(weights.tolerance()) > 0) {
            discrepancy = discrepancies.raise("WEIGHT_MISMATCH", "MANIFEST", manifestCode, mid,
                    null, null, expectedTotal, plantTotal, diff, weights.tolerance(),
                    "到厂核实总净重少 " + diff.abs() + " kg（差 " + diff + "）超 0.5 kg 阈值",
                    "交接认可总净重 " + expectedTotal + " kg，到厂逐箱核实合计 " + plantTotal +
                            " kg，差 " + diff + " kg；实物已在厂，转厂内待核，须安排双方补称",
                    user.displayName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("plantTotalNetKg", plantTotal);
        result.put("expectedTotalNetKg", expectedTotal);
        result.put("diffKg", diff);
        result.put("withinTolerance", diff.abs().compareTo(weights.tolerance()) <= 0);
        if (discrepancy != null) result.put("discrepancy", discrepancy);
        return result;
    }

    public record SupplementReq(List<PlantReading> readings, String note) {}

    /** 差异补称：为每个相关箱留下真实 SUPPLEMENT 证据；不按袋数比例估算。 */
    @Transactional
    public Map<String, Object> supplement(String discrepancyCode, SupplementReq req, AppUser user) {
        Map<String, Object> d = db.one("SELECT * FROM discrepancies WHERE code=? FOR UPDATE", discrepancyCode);
        if (!"OPEN".equals(d.get("status"))) {
            throw ApiException.conflict("DISCREPANCY_NOT_OPEN", "差异已结案，不能补称");
        }
        long mid = ((Number) d.get("manifest_id")).longValue();
        if (req.readings() == null || req.readings().isEmpty()) {
            throw ApiException.badRequest("READINGS_REQUIRED", "补称必须提供逐箱真实读数");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (PlantReading r : req.readings()) {
            Map<String, Object> round = r.boxRoundId() != null
                    ? Views.of(db.one("SELECT * FROM box_rounds WHERE id=?", r.boxRoundId()))
                    : roundForManifestItem(mid, r.boxQr());
            BigDecimal tare = WeightService.bd(round.get("actualTareKg"));
            Map<String, Object> ev = weights.evaluate(r.grossKg(), tare, BigDecimal.ZERO,
                    WeightService.bd(round.get("recognizedNetKg")));
            WeightService.W w = new WeightService.W();
            w.status = "EFFECTIVE";
            w.scope = "BOX";
            w.stage = "SUPPLEMENT";
            w.mode = "BOX_DIRECT";
            w.boxRoundId = ((Number) round.get("id")).longValue();
            w.boxCode = (String) round.get("boxCode");
            w.manifestId = mid;
            w.subjectLabel = round.get("qrCode") + " 差异补称";
            w.gross = r.grossKg();
            w.tareBox = tare;
            w.net = (BigDecimal) ev.get("net");
            w.deviceCode = r.deviceCode();
            w.recordedBy = user.displayName();
            long id = weights.create(w);
            records.add(weights.get(id));
        }
        return Map.of("ok", true, "records", records,
                "hint", "补称证据已保存；请调用差异结案 ACCEPT_MEASURED 更新认可重量");
    }

    public Map<String, Object> revise(long weightId, BigDecimal grossKg, String reason, AppUser user) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("REVISION_REASON_REQUIRED", "修订旧称量必须填写修订原因");
        }
        Map<String, Object> revised = weights.revise(weightId, grossKg, reason, user.displayName());
        if ("DISPUTED".equals(revised.get("status"))) {
            Long mid = revised.get("manifestId") == null ? null : ((Number) revised.get("manifestId")).longValue();
            if (mid != null && revised.get("diff") != null) {
                String manifestCode = (String) db.one("SELECT code FROM manifests WHERE id=?", mid).get("code");
                discrepancies.raise("WEIGHT_MISMATCH", "MANIFEST", manifestCode, mid,
                        revised.get("boxRoundId") == null ? null : ((Number) revised.get("boxRoundId")).longValue(),
                        revised.get("palletLoadId") == null ? null : ((Number) revised.get("palletLoadId")).longValue(),
                        WeightService.bd(revised.get("expectedNet")), WeightService.bd(revised.get("net")),
                        WeightService.bd(revised.get("diff")), weights.tolerance(),
                        "旧称量修订后差值 " + revised.get("diff") + " kg 超阈值",
                        "修订原因: " + reason, user.displayName());
            }
        }
        return revised;
    }
}
