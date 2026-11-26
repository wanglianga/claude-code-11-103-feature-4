package com.medwaste.container;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Db;
import com.medwaste.common.Jsonx;
import com.medwaste.common.Views;
import com.medwaste.custody.CustodyService;
import com.medwaste.weight.WeightService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 袋 / 箱轮次 / 封箱 规则。 */
@Service
public class ContainerService {
    private final Db db;
    private final WeightService weights;
    private final CustodyService custody;

    public ContainerService(Db db, WeightService weights, CustodyService custody) {
        this.db = db;
        this.weights = weights;
        this.custody = custody;
    }

    // ---------------- 查询 ----------------

    public List<Map<String, Object>> listBoxes() {
        List<Map<String, Object>> rows = Views.list(db.list("""
                SELECT b.*, o.name AS custody_org_name,
                       r.id AS current_round_id, r.status AS round_status,
                       r.recognized_net_kg AS recognized_net_kg,
                       jsonb_array_length(COALESCE(r.bags,'[]'::jsonb)) AS bag_count
                FROM boxes b
                LEFT JOIN orgs o ON o.id = b.custody_org_id
                LEFT JOIN box_rounds r ON r.box_id = b.id AND r.round_no = b.current_round_no
                ORDER BY b.code
                """));
        return rows;
    }

    public Map<String, Object> boxDetail(String code) {
        Map<String, Object> box = Views.of(db.one("""
                SELECT b.*, o.name AS custody_org_name
                FROM boxes b LEFT JOIN orgs o ON o.id=b.custody_org_id
                WHERE b.code=?
                """, code));
        List<Map<String, Object>> rounds = Views.list(db.list(
                "SELECT * FROM box_rounds WHERE box_code=? ORDER BY round_no DESC", code));
        for (Map<String, Object> r : rounds) {
            r.put("weights", weights.forRound(((Number) r.get("id")).longValue()));
        }
        box.put("rounds", rounds);
        if (!rounds.isEmpty()) box.put("currentRound", rounds.get(0));
        box.put("custodyEvents", Views.list(db.list(
                "SELECT * FROM custody_events WHERE target_code=? ORDER BY id", code)));
        return box;
    }

    public Map<String, Object> bagDetail(String sealCode) {
        Map<String, Object> bag = Views.of(db.one("SELECT * FROM bags WHERE seal_code=?", sealCode));
        if (bag.get("currentBoxCode") != null) {
            bag.put("box", boxDetail((String) bag.get("currentBoxCode")));
        }
        return bag;
    }

    public List<Map<String, Object>> listBags() {
        return Views.list(db.list("SELECT * FROM bags ORDER BY id DESC LIMIT 200"));
    }

    public List<Map<String, Object>> listPallets() {
        return Views.list(db.list("""
                SELECT p.*, o.name AS custody_org_name FROM pallets p
                LEFT JOIN orgs o ON o.id=p.custody_org_id ORDER BY p.code
                """));
    }

    // ---------------- 创建 ----------------

    @Transactional
    public Map<String, Object> createBox(String code, AppUser user) {
        if (code == null || code.isBlank()) code = "BX" + (db.list("SELECT 1").size() + System.nanoTime() % 100000);
        code = code.trim().toUpperCase();
        if (db.maybeOne("SELECT id FROM boxes WHERE code=?", code).isPresent()) {
            throw ApiException.conflict("BOX_CODE_EXISTS", "箱编号已存在: " + code);
        }
        long orgId = user.orgId() == null ? 1L : user.orgId();
        db.insert("""
                INSERT INTO boxes(code,status,custody_party,custody_org_id,custody_user_name)
                VALUES (?,'AVAILABLE_CLEAN','HOSPITAL',?,?)
                """, code, orgId, user.displayName());
        custody.event("BOX", code, "BOX_REGISTERED", null, "HOSPITAL", orgId, user.displayName(), "登记周转箱（清洁可用）");
        return boxDetail(code);
    }

    @Transactional
    public Map<String, Object> createBag(String sealCode, String categoryCode, AppUser user) {
        if (sealCode == null || sealCode.isBlank()) {
            sealCode = "FD-SEAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        sealCode = sealCode.trim().toUpperCase();
        if (db.maybeOne("SELECT id FROM bags WHERE seal_code=?", sealCode).isPresent()) {
            throw ApiException.conflict("SEAL_CODE_EXISTS", "封口码已存在: " + sealCode);
        }
        if (categoryCode == null) categoryCode = "FW-INF";
        long orgId = user.orgId() == null ? 1L : user.orgId();
        db.insert("INSERT INTO bags(seal_code,category_code,hospital_org_id,status) VALUES (?,?,?,'UNUSED')",
                sealCode, categoryCode, orgId);
        return Views.of(db.one("SELECT * FROM bags WHERE seal_code=?", sealCode));
    }

    @Transactional
    public Map<String, Object> createPallet(String code, BigDecimal tareKg, AppUser user) {
        if (code == null || code.isBlank()) code = "PL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        code = code.trim().toUpperCase();
        if (db.maybeOne("SELECT id FROM pallets WHERE code=?", code).isPresent()) {
            throw ApiException.conflict("PALLET_CODE_EXISTS", "托盘编号已存在: " + code);
        }
        long orgId = user.orgId() == null ? 1L : user.orgId();
        db.insert("INSERT INTO pallets(code,tare_kg,custody_party,custody_org_id,custody_user_name) VALUES (?,?,'HOSPITAL',?,?)",
                code, tareKg == null ? BigDecimal.ZERO : tareKg, orgId, user.displayName());
        return Views.of(db.one("SELECT * FROM pallets WHERE code=?", code));
    }

    @Transactional
    public Map<String, Object> updatePalletTare(String code, BigDecimal tareKg) {
        db.one("SELECT id FROM pallets WHERE code=?", code);
        db.update("UPDATE pallets SET tare_kg=? WHERE code=?", tareKg, code);
        return Views.of(db.one("SELECT * FROM pallets WHERE code=?", code));
    }

    // ---------------- 轮次 ----------------

    /** 开始新一轮封装：只有清洁可用箱可以；轮次号 +1，QR 必须带本轮封装号。 */
    @Transactional
    public Map<String, Object> startRound(String boxCode, BigDecimal actualTareKg,
                                          String deviceCode, AppUser user) {
        Map<String, Object> box = db.lockBox(boxCode.toUpperCase());
        String status = (String) box.get("status");
        if ("PENDING_CLEANING".equals(status)) {
            throw ApiException.conflict("BOX_PENDING_CLEANING",
                    "空箱 " + boxCode + " 取回后只在待清洁区，必须完成清洁/检查并称量新空箱重后才能开始新一轮");
        }
        if (!"AVAILABLE_CLEAN".equals(status)) {
            throw ApiException.conflict("BOX_NOT_AVAILABLE",
                    "箱 " + boxCode + " 当前状态 " + status + "，不能开始新封装轮次");
        }
        if (actualTareKg == null || actualTareKg.signum() < 0) {
            throw ApiException.badRequest("TARE_REQUIRED", "必须记录本轮实际空箱重（千克）");
        }
        int nextRound = ((Number) box.get("current_round_no")).intValue() + 1;
        String qr = box.get("code") + "#R" + nextRound;
        long roundId = db.insert("""
                INSERT INTO box_rounds(box_id,box_code,round_no,qr_code,actual_tare_kg,tare_device_code,
                  status,created_by_name)
                VALUES (?,?,?,?,?,?,'DRAFT',?)
                """, box.get("id"), boxCode.toUpperCase(), nextRound, qr, actualTareKg, deviceCode,
                user == null ? null : user.displayName());
        db.update("UPDATE boxes SET current_round_no=?, status='PACKING' WHERE id=?", nextRound, box.get("id"));
        custody.event("BOX", boxCode.toUpperCase(), "ROUND_STARTED", "HOSPITAL", "HOSPITAL",
                user == null ? null : user.orgId(), user == null ? null : user.displayName(),
                "开始第 " + nextRound + " 轮封装，实际空箱重 " + actualTareKg + " kg，扫码标识 " + qr);
        return Views.of(db.one("SELECT * FROM box_rounds WHERE id=?", roundId));
    }

    /**
     * 扫码领取/绑定袋码到当前有效箱清单。
     * boxQr 形如 C1#R3；旧轮次扫码不能领取新袋。
     */
    @Transactional
    public Map<String, Object> claimBag(String boxQr, String sealCode, AppUser user) {
        if (boxQr == null || !boxQr.contains("#R")) {
            throw ApiException.badRequest("QR_FORMAT_INVALID",
                    "箱扫码必须是带本轮封装号的形式，如 C1#R1；裸箱编号不能用于领袋");
        }
        String[] parts = boxQr.trim().toUpperCase().split("#R");
        String boxCode = parts[0];
        int qrRound;
        try { qrRound = Integer.parseInt(parts[1]); }
        catch (Exception e) { throw ApiException.badRequest("QR_FORMAT_INVALID", "封装号无法识别: " + boxQr); }

        Map<String, Object> box = db.maybeOne("SELECT * FROM boxes WHERE code=?", boxCode)
                .orElseThrow(() -> ApiException.notFound("BOX_NOT_FOUND", "不存在的箱编号: " + boxCode));
        int currentRound = ((Number) box.get("current_round_no")).intValue();
        if (qrRound < currentRound) {
            throw ApiException.conflict("OLD_ROUND_QR",
                    "扫码 " + boxQr + " 是旧封装轮次；该箱当前为第 R" + currentRound +
                    " 轮，旧轮次码不能领取新袋");
        }
        if (qrRound > currentRound) {
            throw ApiException.badRequest("ROUND_NOT_FOUND", "封装轮次尚未建立: " + boxQr);
        }
        Map<String, Object> round = db.one("SELECT * FROM box_rounds WHERE box_id=? AND round_no=?",
                box.get("id"), qrRound);
        if (!"DRAFT".equals(round.get("status"))) {
            throw ApiException.conflict("ROUND_NOT_DRAFT",
                    boxQr + " 本轮清单已封箱，不能再加入袋码；如需调整请按差异/异常流程处理");
        }

        sealCode = sealCode.trim().toUpperCase();
        final String seal = sealCode;
        Map<String, Object> bag = db.maybeOne("SELECT * FROM bags WHERE seal_code=?", sealCode)
                .orElseThrow(() -> ApiException.notFound("BAG_NOT_FOUND",
                        "封口码 " + seal + " 未登记，不能领取"));

        // 任一袋只能在一个当前有效箱清单中
        if ("ACTIVE_IN_LIST".equals(bag.get("status")) && bag.get("current_box_code") != null) {
            String curBox = (String) bag.get("current_box_code");
            if (!curBox.equals(boxCode) || ((Number) bag.get("current_round_no")).intValue() != qrRound) {
                throw ApiException.conflict("BAG_ALREADY_IN_LIST",
                        "封口码 " + sealCode + " 已在当前有效箱清单 " + curBox + "#R" +
                        bag.get("current_round_no") + " 中，不能跨箱重复加入")
                        .with("currentBoxCode", curBox)
                        .with("currentRoundNo", bag.get("current_round_no"));
            }
        }
        // 兜底：查其它未失效轮次清单的 JSONB
        List<Map<String, Object>> dup = db.list("""
                SELECT br.box_code, br.round_no FROM box_rounds br
                WHERE br.id <> ? AND br.status IN ('DRAFT','SEALED')
                  AND br.bags @> jsonb_build_array(jsonb_build_object('sealCode', ?))
                """, round.get("id"), sealCode);
        if (!dup.isEmpty()) {
            Map<String, Object> d = dup.get(0);
            throw ApiException.conflict("BAG_ALREADY_IN_LIST",
                    "封口码 " + sealCode + " 已存在于箱清单 " + d.get("box_code") + "#R" + d.get("round_no"))
                    .with("currentBoxCode", d.get("box_code"))
                    .with("currentRoundNo", d.get("round_no"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bags = Jsonx.read(jsonText(round.get("bags")), List.class);
        boolean exists = bags.stream().anyMatch(b -> seal.equals(String.valueOf(b.get("sealCode"))));
        if (!exists) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sealCode", sealCode);
            entry.put("categoryCode", bag.get("category_code"));
            entry.put("addedByName", user == null ? null : user.displayName());
            entry.put("addedAt", new java.util.Date().toInstant().toString());
            bags.add(entry);
            db.update("UPDATE box_rounds SET bags=?::jsonb WHERE id=?", Jsonx.write(bags), round.get("id"));
        }
        db.update("""
                UPDATE bags SET status='ACTIVE_IN_LIST', current_box_code=?, current_round_no=?
                WHERE seal_code=?
                """, boxCode, qrRound, sealCode);
        return Map.of("ok", true, "boxQr", boxQr, "sealCode", sealCode,
                "round", Views.of(db.one("SELECT * FROM box_rounds WHERE id=?", round.get("id"))));
    }

    private String jsonText(Object pgObject) {
        if (pgObject == null) return "[]";
        if ("org.postgresql.util.PGobject".equals(pgObject.getClass().getName())) {
            try {
                return (String) pgObject.getClass().getMethod("getValue").invoke(pgObject);
            } catch (Exception e) {
                return "[]";
            }
        }
        return pgObject.toString();
    }

    @Transactional
    public Map<String, Object> removeBag(long roundId, String sealCode) {
        Map<String, Object> round = db.one("SELECT * FROM box_rounds WHERE id=?", roundId);
        if (!"DRAFT".equals(round.get("status"))) {
            throw ApiException.conflict("ROUND_NOT_DRAFT", "已封箱清单不能删袋");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bags = Jsonx.read(jsonText(round.get("bags")), List.class);
        bags.removeIf(b -> sealCode.equals(String.valueOf(b.get("sealCode"))));
        db.update("UPDATE box_rounds SET bags=?::jsonb WHERE id=?", Jsonx.write(bags), roundId);
        db.update("UPDATE bags SET status='UNUSED', current_box_code=NULL, current_round_no=NULL WHERE seal_code=?",
                sealCode);
        return Map.of("ok", true);
    }

    /** 封箱：记录封箱毛重，生成 SEAL 重量证据与初始认可净重。封箱 ≠ 已交车。 */
    @Transactional
    public Map<String, Object> seal(long roundId, BigDecimal grossKg, String deviceCode, AppUser user) {
        Map<String, Object> round = db.one("SELECT * FROM box_rounds WHERE id=? FOR UPDATE", roundId);
        if (!"DRAFT".equals(round.get("status"))) {
            throw ApiException.conflict("ROUND_NOT_DRAFT", "该轮次已封箱，不能重复封箱");
        }
        List<Object> bags = Jsonx.readList(jsonText(round.get("bags")));
        if (bags.isEmpty()) {
            throw ApiException.badRequest("EMPTY_BOX", "箱清单至少包含一个袋码才能封箱");
        }
        if (grossKg == null || grossKg.signum() <= 0) {
            throw ApiException.badRequest("GROSS_REQUIRED", "必须通过称重仪或手工录入封箱毛重");
        }
        BigDecimal tare = WeightService.bd(round.get("actual_tare_kg"));
        Map<String, Object> ev = weights.evaluate(grossKg, tare, BigDecimal.ZERO, null);
        BigDecimal net = (BigDecimal) ev.get("net");

        WeightService.W w = new WeightService.W();
        w.status = "EFFECTIVE";
        w.scope = "BOX";
        w.stage = "SEAL";
        w.mode = "BOX_DIRECT";
        w.boxRoundId = roundId;
        w.boxCode = (String) round.get("box_code");
        w.subjectLabel = round.get("qr_code") + " 封箱称量";
        w.gross = grossKg;
        w.tareBox = tare;
        w.net = net;
        w.bagCount = bags.size();
        w.deviceCode = deviceCode;
        w.recordedBy = user == null ? null : user.displayName();
        long weightId = weights.create(w);

        db.update("""
                UPDATE box_rounds SET status='SEALED', seal_gross_kg=?, seal_device_code=?,
                  recognized_net_kg=?, seal_weight_id=?, sealed_by_name=?, sealed_at=now()
                WHERE id=?
                """, grossKg, deviceCode, net, weightId, user == null ? null : user.displayName(), roundId);
        db.update("UPDATE boxes SET status='SEALED' WHERE id=?", round.get("box_id"));
        db.update("UPDATE bags SET status='ACTIVE_IN_LIST', sealed_at=now() WHERE current_box_code=?",
                round.get("box_code"));
        custody.event("BOX", (String) round.get("box_code"), "BOX_SEALED", "HOSPITAL", "HOSPITAL",
                user == null ? null : user.orgId(), user == null ? null : user.displayName(),
                "封箱 " + round.get("qr_code") + "，袋数 " + bags.size() + "，毛重 " + grossKg +
                "，空箱重 " + tare + "，净重 " + net + " kg（封箱不等于已交车）");
        return boxDetail((String) round.get("box_code"));
    }

    /** 清洁完成：待清洁区 → 清洁可用，必须给出清洁后实际空箱重；不直接变为新一轮。 */
    @Transactional
    public Map<String, Object> cleanBox(String code, BigDecimal newTareKg, String deviceCode, AppUser user) {
        Map<String, Object> box = db.lockBox(code.toUpperCase());
        if (!"PENDING_CLEANING".equals(box.get("status"))) {
            throw ApiException.conflict("BOX_NOT_PENDING_CLEANING",
                    "只有待清洁区的空箱需要清洁确认，当前状态: " + box.get("status"));
        }
        if (newTareKg == null || newTareKg.signum() < 0) {
            throw ApiException.badRequest("TARE_REQUIRED", "清洁后必须复称实际空箱重");
        }
        db.update("""
                UPDATE boxes SET status='AVAILABLE_CLEAN', cleaning_tare_kg=?,
                  custody_party='HOSPITAL', custody_org_id=?, custody_user_name=?
                WHERE id=?
                """, newTareKg, user.orgId() == null ? box.get("custody_org_id") : user.orgId(),
                user.displayName(), box.get("id"));
        custody.event("BOX", code.toUpperCase(), "CLEANING_DONE", "CLEANING", "HOSPITAL",
                user.orgId(), user.displayName(),
                "空箱清洁/检查完成，清洁后空箱重 " + newTareKg + " kg，转为清洁可用；下一轮封装号将递增");
        return boxDetail(code.toUpperCase());
    }
}
