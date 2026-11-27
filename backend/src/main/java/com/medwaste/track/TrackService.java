package com.medwaste.track;

import com.medwaste.common.ApiException;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import com.medwaste.discrepancy.DiscrepancyService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 任何人可查：包件现在由谁保管、认可重量、哪些差异未结、各角色下一步。
 * 输入可以是封口码、箱码/带轮次箱码、交接单号、托号、委托/回执/批次号。
 */
@Service
public class TrackService {
    private final Db db;
    private final DiscrepancyService discrepancyService;

    public TrackService(Db db, DiscrepancyService discrepancyService) {
        this.db = db;
        this.discrepancyService = discrepancyService;
    }

    private static final Map<String, String> PARTY_LABEL = Map.of(
            "HOSPITAL", "医院（交接员）",
            "TRANSPORTER", "收运方（收运员/车辆）",
            "PLANT", "处置厂",
            "CLEANING", "院内待清洁区");

    private static final Map<String, String> BOX_STATUS_LABEL = Map.ofEntries(
            Map.entry("AVAILABLE_CLEAN", "清洁可用箱"),
            Map.entry("PACKING", "本轮封装中"),
            Map.entry("SEALED", "已封箱（未交车）"),
            Map.entry("IN_TRANSIT", "已交车·在途（收运保管）"),
            Map.entry("AT_PLANT", "已到厂"),
            Map.entry("PLANT_PENDING_CHECK", "厂内待核（差异未结）"),
            Map.entry("PLANT_RECEIVED", "处置厂已签收"),
            Map.entry("PENDING_CLEANING", "空箱待清洁区"));

    private static final Map<String, String> MANIFEST_STATUS_LABEL = Map.ofEntries(
            Map.entry("DRAFT", "收运员扫码建单中"),
            Map.entry("SUBMITTED", "待医院交接员确认"),
            Map.entry("HANDED_OVER", "已交车·在途"),
            Map.entry("AT_PLANT", "车辆到厂·待实接"),
            Map.entry("IN_PLANT_PENDING_CHECK", "厂内待核（已实接，差异未结，不可退回在途）"),
            Map.entry("READY_TO_SIGN", "核验通过·待签收"),
            Map.entry("SIGNED", "处置厂已签收"),
            Map.entry("CANCELLED", "已取消"));

    public Map<String, Object> track(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw ApiException.badRequest("CODE_REQUIRED", "请输入要查询的封口码/箱码/交接单号");
        }
        String code = rawCode.trim().toUpperCase();

        if (db.maybeOne("SELECT id FROM bags WHERE seal_code=?", code).isPresent()) {
            return trackBag(code);
        }
        if (code.contains("#R")) {
            String boxCode = code.split("#R")[0];
            if (db.maybeOne("SELECT id FROM boxes WHERE code=?", boxCode).isPresent()) return trackBox(boxCode, code);
        }
        if (db.maybeOne("SELECT id FROM boxes WHERE code=?", code).isPresent()) return trackBox(code, null);
        if (db.maybeOne("SELECT id FROM manifests WHERE code=?", code).isPresent()) return trackManifest(code);
        if (db.maybeOne("SELECT id FROM pallet_loads WHERE code=?", code).isPresent()) {
            Map<String, Object> load = Views.of(db.one("SELECT * FROM pallet_loads WHERE code=?", code));
            long mid = ((Number) load.get("manifestId")).longValue();
            String mc = (String) db.one("SELECT code FROM manifests WHERE id=?", mid).get("code");
            Map<String, Object> r = trackManifest(mc);
            r.put("palletLoad", load);
            return r;
        }
        if (db.maybeOne("SELECT id FROM pallets WHERE code=?", code).isPresent()) return trackPallet(code);
        if (db.maybeOne("SELECT id FROM mandates WHERE code=?", code).isPresent()) {
            Map<String, Object> md = Views.of(db.one("SELECT * FROM mandates WHERE code=?", code));
            return trackManifest((String) md.get("manifestCode"));
        }
        if (db.maybeOne("SELECT id FROM disposal_receipts WHERE code=?", code).isPresent()) {
            Map<String, Object> rc = Views.of(db.one("SELECT * FROM disposal_receipts WHERE code=?", code));
            Map<String, Object> b = Views.of(db.one("SELECT * FROM disposal_batches WHERE id=?", rc.get("batchId")));
            @SuppressWarnings("unchecked")
            List<String> mcs = (List<String>) b.get("manifestCodes");
            Map<String, Object> r = trackManifest(mcs.get(0));
            r.put("receipt", rc);
            r.put("batch", b);
            return r;
        }
        throw ApiException.notFound("CODE_NOT_FOUND", "未找到与 " + code + " 相关的包件/单据");
    }

    private Map<String, Object> trackBag(String sealCode) {
        Map<String, Object> bag = Views.of(db.one("SELECT * FROM bags WHERE seal_code=?", sealCode));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("query", sealCode);
        r.put("type", "BAG");
        r.put("typeLabel", "一次性袋/封口码");
        r.put("bag", bag);
        if (bag.get("currentBoxCode") != null) {
            Map<String, Object> boxTrack = trackBox((String) bag.get("currentBoxCode"), null);
            r.putAll(boxTrack);
            r.put("type", "BAG");
            r.put("sealCode", sealCode);
            r.put("bagStatus", bag.get("status"));
        } else {
            r.put("status", bag.get("status"));
            r.put("statusLabel", "封口码未使用/未封入箱清单");
            r.put("nextSteps", List.of(step("HOSPITAL", "医院交接员", "START_ROUND_AND_CLAIM",
                    "在周转箱本轮封装中扫描箱QR与该封口码入清单", true, null)));
            r.put("openDiscrepancies", List.of());
            r.put("timeline", List.of());
        }
        return r;
    }

    private Map<String, Object> trackBox(String boxCode, String highlightQr) {
        Map<String, Object> box = Views.of(db.one("""
                SELECT b.*, o.name AS custody_org_name FROM boxes b
                LEFT JOIN orgs o ON o.id=b.custody_org_id WHERE b.code=?
                """, boxCode));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("query", boxCode);
        r.put("type", "BOX");
        r.put("typeLabel", "可重复周转箱");
        r.put("box", box);
        r.put("status", box.get("status"));
        r.put("statusLabel", BOX_STATUS_LABEL.getOrDefault(box.get("status"), String.valueOf(box.get("status"))));
        r.put("custodian", custodian((String) box.get("custodyParty"), (String) box.get("custodyOrgName"),
                (String) box.get("custodyUserName"), box.get("custodySince")));

        Map<String, Object> round = Views.of(db.one(
                "SELECT * FROM box_rounds WHERE box_code=? ORDER BY round_no DESC LIMIT 1", boxCode));
        r.put("currentRound", round);
        r.put("recognizedNetKg", round.get("recognizedNetKg"));

        List<Map<String, Object>> discs = Views.list(db.list("""
                SELECT * FROM discrepancies
                WHERE box_round_id=? OR (manifest_id = ?) AND status='OPEN'
                ORDER BY id DESC
                """, round.get("id"), box.get("activeManifestId")));
        // 上面 OR 优先级问题在 SQL 内已用括号规避；仅保留未结
        discs.removeIf(d -> !"OPEN".equals(d.get("status")));
        r.put("openDiscrepancies", discs);

        List<Map<String, Object>> timeline = Views.list(db.list(
                "SELECT * FROM custody_events WHERE target_code=? ORDER BY id", boxCode));
        r.put("timeline", timeline);

        if (box.get("activeManifestId") != null) {
            Map<String, Object> m = Views.of(db.one("SELECT code FROM manifests WHERE id=?",
                    box.get("activeManifestId")));
            String mc = (String) m.get("code");
            r.put("manifestCode", mc);
            Map<String, Object> full = trackManifest(mc);
            r.put("manifestStatus", full.get("status"));
            r.put("manifestStatusLabel", full.get("statusLabel"));
            r.put("nextSteps", full.get("nextSteps"));
        } else {
            r.put("nextSteps", boxSteps((String) box.get("status")));
        }
        return r;
    }

    private Map<String, Object> trackManifest(String code) {
        Map<String, Object> m = Views.of(db.one("SELECT * FROM manifests WHERE code=?", code));
        long id = ((Number) m.get("id")).longValue();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("query", code);
        r.put("type", "MANIFEST");
        r.put("typeLabel", "收运交接单/批次");
        r.put("manifest", m);
        r.put("status", m.get("status"));
        r.put("statusLabel", MANIFEST_STATUS_LABEL.getOrDefault(m.get("status"), String.valueOf(m.get("status"))));

        List<Map<String, Object>> items = Views.list(db.list("""
                SELECT mi.box_code, mi.round_no, br.qr_code,
                       br.recognized_net_kg, b.status AS box_status,
                       b.custody_party, o.name AS custody_org_name,
                       b.custody_user_name, b.custody_since
                FROM manifest_items mi
                JOIN box_rounds br ON br.id=mi.box_round_id
                JOIN boxes b ON b.code=mi.box_code
                LEFT JOIN orgs o ON o.id=b.custody_org_id
                WHERE mi.manifest_id=? ORDER BY mi.id
                """, id));
        r.put("items", items);
        if (!items.isEmpty()) {
            Map<String, Object> first = items.get(0);
            r.put("custodian", custodian((String) first.get("custodyParty"),
                    (String) first.get("custodyOrgName"), (String) first.get("custodyUserName"),
                    first.get("custodySince")));
        }

        Map<String, Object> weight = new LinkedHashMap<>();
        weight.put("totalRecognizedNetKg", m.get("totalRecognizedNetKg"));
        weight.put("basis", m.get("weightBasis") == null ? "NONE" : m.get("weightBasis"));
        weight.put("basisLabel", basisLabel((String) m.get("weightBasis")));
        r.put("weight", weight);

        r.put("openDiscrepancies", discrepancyService.openForManifest(id));
        r.put("discrepancies", discrepancyService.list("ALL").stream()
                .filter(d -> Objects.equals(d.get("manifestId"), id)).toList());

        db.maybeOne("SELECT * FROM mandates WHERE manifest_id=?", id)
                .ifPresent(md -> r.put("mandate", Views.of(md)));

        // 委托复核任务
        List<Map<String, Object>> reviews = new ArrayList<>();
        boolean mandateReview = db.maybeOne(
                "SELECT id FROM mandates WHERE manifest_id=? AND status='REVIEW_REQUIRED'", id).isPresent();
        if (mandateReview) {
            List<Map<String, Object>> batches = db.list(
                    "SELECT code FROM disposal_batches WHERE manifest_codes @> ?::jsonb",
                    "[\"" + code + "\"]");
            for (Map<String, Object> b : batches) {
                reviews.addAll(Views.list(db.list(
                        "SELECT * FROM review_tasks WHERE batch_code=? AND status='OPEN' ORDER BY id",
                        b.get("code"))));
            }
        }
        r.put("openReviewTasks", reviews);

        // 时间线：交接单 + 各箱 + 托盘
        List<String> targetCodes = new ArrayList<>();
        targetCodes.add(code);
        items.forEach(i -> targetCodes.add((String) i.get("boxCode")));
        db.list("SELECT pallet_code FROM pallet_loads WHERE manifest_id=?", id)
                .forEach(p -> targetCodes.add((String) p.get("pallet_code")));
        String placeholders = String.join(",", Collections.nCopies(targetCodes.size(), "?"));
        r.put("timeline", Views.list(db.list(
                "SELECT * FROM custody_events WHERE target_code IN (" + placeholders + ") ORDER BY id",
                targetCodes.toArray())));

        r.put("nextSteps", manifestSteps(m, r));
        return r;
    }

    private Map<String, Object> trackPallet(String code) {
        Map<String, Object> p = Views.of(db.one("""
                SELECT p.*, o.name AS custody_org_name FROM pallets p
                LEFT JOIN orgs o ON o.id=p.custody_org_id WHERE p.code=?
                """, code));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("query", code);
        r.put("type", "PALLET");
        r.put("typeLabel", "托盘（另计皮重）");
        r.put("pallet", p);
        r.put("status", p.get("status"));
        r.put("statusLabel", "托盘 " + p.get("status"));
        r.put("custodian", custodian((String) p.get("custodyParty"), (String) p.get("custodyOrgName"),
                (String) p.get("custodyUserName"), p.get("custodySince")));
        r.put("timeline", Views.list(db.list(
                "SELECT * FROM custody_events WHERE target_code=? ORDER BY id", code)));
        r.put("openDiscrepancies", List.of());
        r.put("nextSteps", List.of());
        return r;
    }

    private Map<String, Object> custodian(String party, String orgName, String person, Object since) {
        if (party == null) return null;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("party", party);
        c.put("partyLabel", PARTY_LABEL.getOrDefault(party, party));
        c.put("orgName", orgName);
        c.put("person", person);
        c.put("since", since);
        c.put("text", (orgName == null ? "" : orgName + " · ") +
                PARTY_LABEL.getOrDefault(party, party) +
                (person != null ? "（" + person + "）" : ""));
        return c;
    }

    private String basisLabel(String basis) {
        if (basis == null) return "尚无共同认可重量";
        return switch (basis) {
            case "BOX_DIRECT" -> "逐箱复称：保留各箱净重";
            case "PALLET_COMBINED" -> "合托合称：仅有托总净重，无分箱 joint 证据";
            case "MIXED" -> "逐箱+合托混合";
            case "PLANT_ADJUSTED" -> "差异补称后认可实测总量";
            default -> basis;
        };
    }

    private List<Map<String, Object>> boxSteps(String status) {
        return switch (status) {
            case "AVAILABLE_CLEAN" -> List.of(
                    step("HOSPITAL", "医院交接员", "START_ROUND", "称实际空箱重并开始新一轮封装（轮次号+1）", true, null));
            case "PACKING" -> List.of(
                    step("HOSPITAL", "医院交接员", "ADD_BAGS_SEAL", "扫入本轮袋码、录入封箱毛重并封箱", true, null),
                    step("TRANSPORTER", "收运员", "WAIT_SEALED", "封箱后再扫码建单", false, "箱尚未封"));
            case "SEALED" -> List.of(
                    step("TRANSPORTER", "收运员", "SCAN_CREATE_MANIFEST", "扫描当前封口码/箱QR、车辆并建立交接清单", true, null),
                    step("HOSPITAL", "医院交接员", "WAIT_HANDOVER_CONFIRM", "收运员建单后逐箱复称/合托合称并确认交车", true, null));
            case "PENDING_CLEANING" -> List.of(
                    step("HOSPITAL", "医院/清洁岗", "CLEAN_AND_WEIGH", "在待清洁区完成清洁检查、称新空箱重后转为可用", true, null),
                    step("TRANSPORTER", "收运员", "NO_DIRECT_REUSE", "不能直接领取新袋", false, "必须先清洁确认"));
            default -> List.of();
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> manifestSteps(Map<String, Object> m, Map<String, Object> full) {
        String status = (String) m.get("status");
        long openCount = full.get("openDiscrepancies") == null ? 0
                : ((List<?>) full.get("openDiscrepancies")).size();
        List<Map<String, Object>> steps = new ArrayList<>();

        switch (status) {
            case "DRAFT" -> {
                steps.add(step("TRANSPORTER", "收运员", "SCAN_BOX_VEHICLE",
                        "扫描当前封口码/带轮次箱QR与车辆，补全箱清单后提交", true, null));
                steps.add(step("HOSPITAL", "医院交接员", "WAIT_SUBMIT", "等待收运员提交清单", false, "单据尚未提交"));
            }
            case "SUBMITTED" -> {
                steps.add(step("HOSPITAL", "医院交接员", "REWEIGH_AND_HANDOVER",
                        "选择逐箱复称确认各箱重量，或对完整一托合称确认总净重，然后确认交车", true, null));
                steps.add(step("TRANSPORTER", "收运员", "WAIT_HOSPITAL_CONFIRM", "等待医院交接员确认，保管尚未转移", false, null));
            }
            case "HANDED_OVER" -> steps.add(step("TRANSPORTER", "收运员", "REGISTER_ARRIVAL",
                    "车辆到处置厂后登记到厂（到厂不等于处置完成）", true, null));
            case "AT_PLANT" -> steps.add(step("PLANT", "处置厂", "VERIFY_INTAKE",
                    "核实机构资质、封条并做厂内重量核实，然后实接", true, null));
            case "IN_PLANT_PENDING_CHECK" -> {
                steps.add(step("PLANT", "处置厂", "SUPPLEMENT_WEIGH_RESOLVE",
                        "安排逐箱补称/复称，差异结案后签收", true, null));
                steps.add(step("TRANSPORTER", "收运方", "JOIN_REWEIGH", "到场共同复称并确认补称结果", true, null));
                steps.add(step("PLANT", "处置厂", "SIGN_AFTER_RESOLVED", "差异结案后签收", false,
                        "仍有 " + openCount + " 项未结差异"));
                steps.add(step("PUBLIC", "规则", "NO_RETURN_IN_TRANSIT",
                        "已实接且差异未结 → 厂内待核，不能退回在途", false, "状态不可逆转"));
            }
            case "READY_TO_SIGN" -> steps.add(step("PLANT", "处置厂", "SIGN",
                    "资质/封条/重量核验通过，正式签收", true, null));
            case "SIGNED" -> {
                boolean review = ((List<?>) full.getOrDefault("openReviewTasks", List.of())).size() > 0
                        || "REVIEW_REQUIRED".equals(
                        ((Map<String, Object>) full.getOrDefault("mandate", Map.of())).get("status"));
                if (review) {
                    steps.add(step("PLANT", "处置厂", "REISSUE_RECEIPT_HANDLE_REVIEW",
                            "处置完成回执失效：补开有效回执并办结复核任务，委托才能结束", true, null));
                } else {
                    steps.add(step("PLANT", "处置厂", "BATCH_DISPOSAL_RECEIPT",
                            "组批 → 处置模拟器执行 → 签发有效处置完成回执以结束委托", true, null));
                }
                steps.add(step("TRANSPORTER", "收运员", "RETURN_EMPTIES",
                        "卸料后取回空箱，空箱只进入待清洁区", true, null));
            }
            default -> { }
        }
        return steps;
    }

    private Map<String, Object> step(String role, String roleLabel, String code,
                                     String label, boolean enabled, String reason) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("role", role);
        s.put("roleLabel", roleLabel);
        s.put("code", code);
        s.put("label", label);
        s.put("enabled", enabled);
        if (reason != null) s.put("disabledReason", reason);
        return s;
    }

    public List<Map<String, Object>> publicOpenDiscrepancies() {
        return discrepancyService.publicOpen();
    }
}
