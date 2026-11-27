package com.medwaste.manifest;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import com.medwaste.custody.CustodyService;
import com.medwaste.discrepancy.DiscrepancyService;
import com.medwaste.weight.WeightService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 交接单/车次批次：扫码建单 → 医院确认交车 → 在途 → 到厂 → 实接待核 → 签收 → 空箱取回。 */
@Service
public class ManifestService {
    private final Db db;
    private final CustodyService custody;
    private final DiscrepancyService discrepancies;
    private final WeightService weights;

    public ManifestService(Db db, CustodyService custody,
                           DiscrepancyService discrepancies, WeightService weights) {
        this.db = db;
        this.custody = custody;
        this.discrepancies = discrepancies;
        this.weights = weights;
    }

    // ---------------- 视图 ----------------

    public Map<String, Object> detail(String code) {
        Map<String, Object> m = Views.of(db.one("SELECT * FROM manifests WHERE code=?", code));
        long id = ((Number) m.get("id")).longValue();

        if (m.get("hospitalOrgId") != null) {
            db.maybeOne("SELECT name FROM orgs WHERE id=?", m.get("hospitalOrgId"))
                    .ifPresent(o -> m.put("hospitalOrgName", o.get("name")));
        }
        if (m.get("transporterOrgId") != null) {
            db.maybeOne("SELECT name FROM orgs WHERE id=?", m.get("transporterOrgId"))
                    .ifPresent(o -> m.put("transporterOrgName", o.get("name")));
        }

        List<Map<String, Object>> items = Views.list(db.list("""
                SELECT mi.id, mi.box_round_id, mi.box_code, mi.round_no,
                       br.qr_code, br.actual_tare_kg,
                       br.recognized_net_kg, br.status AS round_status,
                       jsonb_array_length(COALESCE(br.bags,'[]'::jsonb)) AS bag_count,
                       b.status AS box_status, b.custody_party,
                       o.name AS custody_org_name
                FROM manifest_items mi
                JOIN box_rounds br ON br.id = mi.box_round_id
                JOIN boxes b ON b.code = mi.box_code
                LEFT JOIN orgs o ON o.id = b.custody_org_id
                WHERE mi.manifest_id=? ORDER BY mi.id
                """, id));
        m.put("items", items);

        List<Map<String, Object>> loads = Views.list(db.list(
                "SELECT * FROM pallet_loads WHERE manifest_id=? ORDER BY id", id));
        for (Map<String, Object> load : loads) {
            long lid = ((Number) load.get("id")).longValue();
            load.put("weights", weights.forPalletLoad(lid));
            load.put("scannedCount", ((List<?>) load.getOrDefault("scannedRoundIds", List.of())).size());
            load.put("declaredCount", ((List<?>) load.getOrDefault("declaredRoundIds", List.of())).size());
        }
        m.put("palletLoads", loads);
        m.put("weights", weights.forManifest(id));
        m.put("discrepancies", discrepancies.list("ALL").stream()
                .filter(d -> Objects.equals(d.get("manifestId"), id)).toList());
        m.put("openDiscrepancies", discrepancies.openForManifest(id));
        m.put("custodyEvents", Views.list(db.list(
                "SELECT * FROM custody_events WHERE target_code=? ORDER BY id", code)));
        db.maybeOne("SELECT * FROM plant_receipts WHERE manifest_id=?", id)
                .ifPresent(r -> m.put("plantReceipt", Views.of(r)));
        db.maybeOne("SELECT * FROM mandates WHERE manifest_id=?", id)
                .ifPresent(r -> m.put("mandate", Views.of(r)));
        return m;
    }

    public List<Map<String, Object>> listFor(AppUser user, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.*, v.plate_no AS vehicle_plate,
                  (SELECT count(*) FROM manifest_items mi WHERE mi.manifest_id=m.id) AS box_count,
                  (SELECT count(*) FROM discrepancies d WHERE d.manifest_id=m.id AND d.status='OPEN') AS open_disc_count
                FROM manifests m LEFT JOIN vehicles v ON v.id=m.vehicle_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if ("TRANSPORTER".equals(user.role())) {
            sql.append(" AND m.transporter_org_id=?");
            args.add(user.orgId());
        } else if ("HOSPITAL".equals(user.role())) {
            sql.append(" AND m.hospital_org_id=?");
            args.add(user.orgId());
        }
        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            sql.append(" AND m.status=?");
            args.add(status);
        }
        sql.append(" ORDER BY m.id DESC LIMIT 200");
        return Views.list(db.list(sql.toString(), args.toArray()));
    }

    // ---------------- 建单 / 扫码 ----------------

    @Transactional
    public Map<String, Object> create(String vehicleCode, String hospitalOrgCode, AppUser user) {
        long transporterOrg = user.orgId() == null ? 2L : user.orgId();
        long hospitalOrg;
        if (hospitalOrgCode != null && !hospitalOrgCode.isBlank()) {
            hospitalOrg = ((Number) db.one("SELECT id FROM orgs WHERE code=? AND org_type='HOSPITAL'",
                    hospitalOrgCode).get("id")).longValue();
        } else {
            hospitalOrg = ((Number) db.one("SELECT id FROM orgs WHERE code='YY-RH'").get("id")).longValue();
        }
        Long vehicleId = null;
        String plate = null;
        if (vehicleCode != null && !vehicleCode.isBlank()) {
            Map<String, Object> v = db.maybeOne("SELECT * FROM vehicles WHERE plate_no=?",
                    vehicleCode).orElseThrow(() ->
                    ApiException.notFound("VEHICLE_NOT_FOUND", "未登记车辆: " + vehicleCode));
            if (!"ACTIVE".equals(v.get("status"))) {
                throw ApiException.conflict("VEHICLE_UNAVAILABLE",
                        "车辆 " + v.get("plate_no") + " 状态 " + v.get("status") + "，不能执行收运");
            }
            vehicleId = ((Number) v.get("id")).longValue();
            plate = (String) v.get("plate_no");
        }
        String code = Codes.next("MD");
        long id = db.insert("""
                INSERT INTO manifests(code,hospital_org_id,transporter_org_id,vehicle_id,vehicle_plate,
                  transporter_user_id,transporter_name,status)
                VALUES (?,?,?,?,?,?,?, 'DRAFT')
                """, code, hospitalOrg, transporterOrg, vehicleId, plate, user.id(), user.displayName());
        custody.event("MANIFEST", code, "MANIFEST_CREATED", null, "TRANSPORTER",
                transporterOrg, user.displayName(), "收运员扫码建单" + (plate != null ? "，车辆 " + plate : ""));
        return detail(code);
    }

    @Transactional
    public Map<String, Object> assignVehicle(String code, String vehicleCode) {
        Map<String, Object> m = db.lockManifest(code);
        if (!"DRAFT".equals(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_NOT_EDITABLE", "交接单已提交/确认，不能更换车辆");
        }
        Map<String, Object> v = db.one("SELECT * FROM vehicles WHERE plate_no=?", vehicleCode);
        db.update("UPDATE manifests SET vehicle_id=?, vehicle_plate=? WHERE id=?",
                v.get("id"), v.get("plate_no"), m.get("id"));
        return detail(code);
    }

    /** 把任意扫码（箱 QR / 箱号 / 封口码）解析为当前有效封箱轮次。 */
    public Map<String, Object> resolveScan(String scanCode) {
        String s = scanCode.trim().toUpperCase();
        Optional<Map<String, Object>> round = Optional.empty();

        if (s.contains("#R")) {
            round = db.maybeOne("SELECT * FROM box_rounds WHERE qr_code=?", s);
        }
        if (round.isEmpty() && !s.startsWith("FD-")) {
            // 箱号：取当前轮次
            round = db.maybeOne("""
                    SELECT br.* FROM boxes b JOIN box_rounds br ON br.box_id=b.id AND br.round_no=b.current_round_no
                    WHERE b.code=?
                    """, s);
        }
        if (round.isEmpty()) {
            // 封口码 → 袋当前所在轮次
            Map<String, Object> bag = db.maybeOne("SELECT * FROM bags WHERE seal_code=?", s)
                    .orElseThrow(() -> ApiException.notFound("SCAN_NOT_RECOGNIZED",
                            "扫码 " + s + " 无法识别为封口码、箱号或带轮次箱码"));
            if (bag.get("current_box_code") == null) {
                throw ApiException.conflict("BAG_NOT_IN_LIST", "封口码 " + s + " 尚未封入任何箱清单");
            }
            round = db.maybeOne("SELECT * FROM box_rounds WHERE box_code=? AND round_no=?",
                    bag.get("current_box_code"), bag.get("current_round_no"));
        }
        Map<String, Object> r = round.orElseThrow(() ->
                ApiException.notFound("ROUND_NOT_FOUND", "扫码对应的封装轮次不存在"));
        return Views.of(r);
    }

    @Transactional
    public Map<String, Object> scanItem(String manifestCode, String scanCode, AppUser user) {
        Map<String, Object> m = db.lockManifest(manifestCode);
        String st = (String) m.get("status");
        if (!List.of("DRAFT", "SUBMITTED").contains(st)) {
            throw ApiException.conflict("MANIFEST_LOCKED", "交接单状态 " + st + "，不能再扫码加箱");
        }
        Map<String, Object> roundRow = resolveScan(scanCode);
        long roundId = ((Number) roundRow.get("id")).longValue();
        String boxCode = (String) roundRow.get("boxCode");
        int roundNo = ((Number) roundRow.get("roundNo")).intValue();

        if (!"SEALED".equals(roundRow.get("status"))) {
            throw ApiException.conflict("BOX_NOT_SEALED",
                    boxCode + "#R" + roundNo + " 尚未封箱，不能扫入交接单");
        }
        Map<String, Object> box = db.one("SELECT * FROM boxes WHERE code=?", boxCode);
        String bst = (String) box.get("status");
        if (List.of("IN_TRANSIT", "AT_PLANT", "PLANT_PENDING_CHECK", "PLANT_RECEIVED", "PENDING_CLEANING")
                .contains(bst)) {
            Map<String, Object> holder = db.maybeOne(
                    "SELECT o.name FROM boxes b LEFT JOIN orgs o ON o.id=b.custody_org_id WHERE b.code=?",
                    boxCode).orElse(Collections.emptyMap());
            throw ApiException.conflict("BOX_ALREADY_HANDED",
                    "箱 " + boxCode + " 已完成交接且当前状态为 " + bst + "，不能被第二个收运员再次接收")
                    .with("currentCustodian", holder.get("name"))
                    .with("boxStatus", bst);
        }

        boolean exists = db.maybeOne("SELECT id FROM manifest_items WHERE manifest_id=? AND box_round_id=?",
                m.get("id"), roundId).isPresent();
        if (!exists) {
            db.insert("INSERT INTO manifest_items(manifest_id,box_round_id,box_code,round_no) VALUES (?,?,?,?)",
                    m.get("id"), roundId, boxCode, roundNo);
        }
        return detail(manifestCode);
    }

    @Transactional
    public Map<String, Object> removeItem(String manifestCode, long roundId) {
        Map<String, Object> m = db.lockManifest(manifestCode);
        if (!List.of("DRAFT", "SUBMITTED").contains(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_LOCKED", "已交接单据不能移除箱");
        }
        db.update("DELETE FROM manifest_items WHERE manifest_id=? AND box_round_id=?", m.get("id"), roundId);
        return detail(manifestCode);
    }

    @Transactional
    public Map<String, Object> submit(String code) {
        Map<String, Object> m = db.lockManifest(code);
        Integer count = db.jdbc().queryForObject(
                "SELECT count(*) FROM manifest_items WHERE manifest_id=?", Integer.class, m.get("id"));
        if (count == null || count == 0) {
            throw ApiException.badRequest("EMPTY_MANIFEST", "箱清单为空，不能提交");
        }
        db.update("UPDATE manifests SET status='SUBMITTED', submitted_at=now() WHERE id=?", m.get("id"));
        return detail(code);
    }

    @Transactional
    public Map<String, Object> cancel(String code, AppUser user) {
        Map<String, Object> m = db.lockManifest(code);
        if ("HANDED_OVER".equals(m.get("status")) || "SIGNED".equals(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_LOCKED", "已完成保管转移的交接单不能取消");
        }
        db.update("UPDATE manifests SET status='CANCELLED' WHERE id=?", m.get("id"));
        custody.event("MANIFEST", code, "MANIFEST_CANCELLED", null, null, user.orgId(),
                user.displayName(), "取消/作废扫码单（未发生保管转移）");
        return detail(code);
    }

    // ---------------- 医院确认交车（并发关键路径） ----------------

    @Transactional
    public Map<String, Object> handover(String code, AppUser user) {
        // 锁交接单行，串行化同一单的重复确认
        Map<String, Object> m = db.lockManifest(code);
        String st = (String) m.get("status");
        if ("HANDED_OVER".equals(st) || "AT_PLANT".equals(st)
                || "IN_PLANT_PENDING_CHECK".equals(st) || "SIGNED".equals(st)) {
            throw ApiException.conflict("MANIFEST_ALREADY_HANDED",
                    "该交接单已由 " + m.get("hospital_confirmer_name") + " 确认交车，不能重复交接");
        }
        if ("CANCELLED".equals(st)) {
            throw ApiException.conflict("MANIFEST_CANCELLED", "交接单已取消");
        }
        long manifestId = ((Number) m.get("id")).longValue();
        List<Map<String, Object>> items = db.list(
                "SELECT * FROM manifest_items WHERE manifest_id=? ORDER BY box_code", manifestId);
        if (items.isEmpty()) throw ApiException.badRequest("EMPTY_MANIFEST", "箱清单为空，不能确认交接");

        // 按箱 id 排序加行锁，避免两个交叉清单并发时死锁
        List<Long> boxIds = new ArrayList<>();
        for (Map<String, Object> it : items) {
            boxIds.add(((Number) db.one("SELECT id FROM boxes WHERE code=?", it.get("box_code")).get("id")).longValue());
        }
        Collections.sort(boxIds);
        Map<String, Map<String, Object>> locked = new HashMap<>();
        for (Long bid : boxIds) {
            Map<String, Object> b = db.one("SELECT * FROM boxes WHERE id=? FOR UPDATE", bid);
            locked.put((String) b.get("code"), b);
        }
        for (Map<String, Object> it : items) {
            String boxCode = (String) it.get("box_code");
            Map<String, Object> b = locked.get(boxCode);
            String bst = (String) b.get("status");
            if (!"SEALED".equals(bst)) {
                Object holderOrg = b.get("custody_org_id");
                String orgName = holderOrg == null ? null : db.maybeOne(
                        "SELECT name FROM orgs WHERE id=?", holderOrg).map(r -> r.get("name").toString()).orElse(null);
                throw ApiException.conflict("BOX_ALREADY_HANDED",
                        "箱 " + boxCode + " 已被其他交接单接收（当前状态 " + bst +
                                (orgName != null ? "，保管方：" + orgName : "") + "），本次确认无效")
                        .with("boxCode", boxCode)
                        .with("currentCustodian", orgName)
                        .with("boxStatus", bst);
            }
        }

        // 保管责任：医院 → 收运机构
        for (Map<String, Object> it : items) {
            String boxCode = (String) it.get("box_code");
            db.update("""
                    UPDATE boxes SET status='IN_TRANSIT', active_manifest_id=?, version=version+1,
                      custody_party='TRANSPORTER', custody_org_id=?, custody_user_name=?
                    WHERE code=?
                    """, manifestId, m.get("transporter_org_id"), m.get("transporter_name"), boxCode);
            custody.event("BOX", boxCode, "CUSTODY_HANDOVER", "HOSPITAL", "TRANSPORTER",
                    ((Number) m.get("transporter_org_id")).longValue(),
                    (String) m.get("transporter_name"),
                    "医院交接员 " + user.displayName() + " 确认交车；交接单 " + code +
                            "，车牌 " + m.get("vehicle_plate"));
        }
        // 托盘随单保管转移
        List<Map<String, Object>> loads = db.list("SELECT * FROM pallet_loads WHERE manifest_id=?", manifestId);
        for (Map<String, Object> load : loads) {
            db.update("""
                    UPDATE pallets SET custody_party='TRANSPORTER', custody_org_id=?, custody_user_name=?
                    WHERE code=?
                    """, m.get("transporter_org_id"), m.get("transporter_name"), load.get("pallet_code"));
            custody.event("PALLET", (String) load.get("pallet_code"), "CUSTODY_HANDOVER",
                    "HOSPITAL", "TRANSPORTER", ((Number) m.get("transporter_org_id")).longValue(),
                    (String) m.get("transporter_name"), "随交接单 " + code + " 交车");
        }

        db.update("""
                UPDATE manifests SET status='HANDED_OVER', handed_at=now(),
                  hospital_confirmer_id=?, hospital_confirmer_name=?
                WHERE id=?
                """, user.id(), user.displayName(), manifestId);

        // 委托随交接成立：只有对应批次的有效处置完成回执才能结束
        db.insert("""
                INSERT INTO mandates(code,manifest_id,manifest_code,hospital_org_id,status)
                VALUES (?,?,?,?,'ACTIVE')
                """, Codes.next("WT"), manifestId, code, m.get("hospital_org_id"));

        weights.recomputeManifestTotal(manifestId);
        return detail(code);
    }

    // ---------------- 到厂 / 实接 / 签收 ----------------

    @Transactional
    public Map<String, Object> arrive(String code, AppUser user) {
        Map<String, Object> m = db.lockManifest(code);
        if (!"HANDED_OVER".equals(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "只有已交车在途的交接单能登记到厂，当前状态 " + m.get("status"));
        }
        long id = ((Number) m.get("id")).longValue();
        db.update("UPDATE manifests SET status='AT_PLANT', arrived_at=now() WHERE id=?", id);
        db.update("UPDATE boxes SET status='AT_PLANT' WHERE code IN (SELECT box_code FROM manifest_items WHERE manifest_id=?)", id);
        custody.event("MANIFEST", code, "VEHICLE_ARRIVED", "TRANSPORTER", "TRANSPORTER",
                user.orgId(), user.displayName(), "车辆到达处置厂；到厂不等于已完成处置，实物尚未实接");
        return detail(code);
    }

    /** 已实接且差异未结时，明确禁止退回在途。 */
    @Transactional
    public Map<String, Object> returnToTransitForbidden(String code) {
        Map<String, Object> m = db.one("SELECT * FROM manifests WHERE code=?", code);
        if (m.get("intake_at") != null) {
            long open = discrepancies.openForManifest(((Number) m.get("id")).longValue()).size();
            throw ApiException.conflict("RETURN_IN_TRANSIT_FORBIDDEN",
                    "该批实物已由处置厂实接，保管责任已转移到厂；" +
                            (open > 0 ? "仍有 " + open + " 项差异未结，状态为厂内待核，" : "") +
                            "不能退回在途。请在厂内完成复称/补称与差异办理后签收");
        }
        throw ApiException.conflict("NOT_INTAKEN", "尚未实接，无需走“退回在途”异常流程");
    }

    public record IntakeReq(Boolean qualOk, Boolean sealOk, String qualNote, String sealNote, String note) {}

    @Transactional
    public Map<String, Object> intake(String code, IntakeReq req, AppUser user) {
        Map<String, Object> m = db.lockManifest(code);
        if (!"AT_PLANT".equals(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_NOT_ARRIVED",
                    "只有已登记到厂的交接单能实接，当前状态 " + m.get("status"));
        }
        long id = ((Number) m.get("id")).longValue();
        boolean qualOk = Boolean.TRUE.equals(req.qualOk());
        boolean sealOk = Boolean.TRUE.equals(req.sealOk());

        db.update("UPDATE manifests SET qual_ok=?, seal_ok=?, check_note=?, intake_at=now() WHERE id=?",
                qualOk, sealOk, req.note(), id);

        // 实接：保管责任 → 处置厂
        List<Map<String, Object>> items = db.list("SELECT box_code FROM manifest_items WHERE manifest_id=?", id);
        for (Map<String, Object> it : items) {
            db.update("""
                    UPDATE boxes SET custody_party='PLANT', custody_org_id=?, custody_user_name=?
                    WHERE code=?
                    """, user.orgId(), user.displayName(), it.get("box_code"));
            custody.event("BOX", (String) it.get("box_code"), "PLANT_INTAKE",
                    "TRANSPORTER", "PLANT", user.orgId(), user.displayName(),
                    "处置厂实接入厂；资质核验 " + (qualOk ? "通过" : "异常") +
                            "，封条核验 " + (sealOk ? "完好" : "异常"));
        }

        if (!qualOk) {
            discrepancies.raise("QUALIFICATION_INVALID", "MANIFEST", code, id, null, null,
                    null, null, null, null, "收运/机构资质核验不通过",
                    req.qualNote() == null ? "资质过期、暂停或超范围" : req.qualNote(), user.displayName());
        }
        if (!sealOk) {
            discrepancies.raise("SEAL_BROKEN", "MANIFEST", code, id, null, null, null, null, null, null,
                    "封条/封口码核验异常",
                    req.sealNote() == null ? "封条破损或封口码与清单不符" : req.sealNote(), user.displayName());
        }

        long openCount = discrepancies.openForManifest(id).size();
        String nextStatus = openCount > 0 ? "IN_PLANT_PENDING_CHECK" : "READY_TO_SIGN";
        db.update("UPDATE manifests SET status=? WHERE id=?", nextStatus, id);
        if (openCount > 0) {
            db.update("""
                    UPDATE boxes SET status='PLANT_PENDING_CHECK'
                    WHERE code IN (SELECT box_code FROM manifest_items WHERE manifest_id=?)
                    """, id);
        }
        custody.event("MANIFEST", code, openCount > 0 ? "PLANT_PENDING_CHECK" : "PLANT_INTAKE_OK",
                "TRANSPORTER", "PLANT", user.orgId(), user.displayName(),
                openCount > 0 ? "已实接但有 " + openCount + " 项未结差异 → 厂内待核，不可退回在途"
                        : "资质/封条/重量核验通过，可签收");
        return detail(code);
    }

    public record SignReq(String note) {}

    @Transactional
    public Map<String, Object> sign(String code, SignReq req, AppUser user) {
        Map<String, Object> m = db.lockManifest(code);
        long id = ((Number) m.get("id")).longValue();
        String st = (String) m.get("status");
        if ("SIGNED".equals(st)) throw ApiException.conflict("ALREADY_SIGNED", "交接单已签收");
        if (!"READY_TO_SIGN".equals(st)) {
            long open = discrepancies.openForManifest(id).size();
            throw ApiException.conflict("CANNOT_SIGN_WITH_DISCREPANCY",
                    "当前状态 " + st + (open > 0 ? "，仍有 " + open + " 项差异未结案" : "") +
                            "；须完成核实/差异办理后才能签收");
        }
        if (!Boolean.TRUE.equals(m.get("qual_ok")) || !Boolean.TRUE.equals(m.get("seal_ok"))) {
            throw ApiException.conflict("CHECKS_INCOMPLETE", "资质与封条核验必须通过才能签收");
        }
        String receiptCode = Codes.next("QS");
        BigDecimal total = m.get("total_recognized_net_kg") == null ? null
                : WeightService.bd(m.get("total_recognized_net_kg"));
        long receiptId = db.insert("""
                INSERT INTO plant_receipts(code,manifest_id,manifest_code,qual_ok,seal_ok,
                  weight_total_kg,note,signed_by_name)
                VALUES (?,?,?,?,?,?,?,?)
                """, receiptCode, id, code, m.get("qual_ok"), m.get("seal_ok"),
                total, req == null ? null : req.note(), user.displayName());
        db.update("UPDATE manifests SET status='SIGNED', signed_at=now(), plant_receipt_id=? WHERE id=?",
                receiptId, id);
        db.update("UPDATE boxes SET status='PLANT_RECEIVED' WHERE code IN (SELECT box_code FROM manifest_items WHERE manifest_id=?)", id);
        custody.event("MANIFEST", code, "PLANT_SIGNED", "PLANT", "PLANT", user.orgId(),
                user.displayName(), "处置厂签收，签收单 " + receiptCode + "，认可总净重 " + total + " kg");
        return detail(code);
    }

    // ---------------- 空箱取回 → 待清洁区 ----------------

    @Transactional
    public Map<String, Object> returnEmpties(String code, AppUser user) {
        Map<String, Object> m = db.lockManifest(code);
        if (!"SIGNED".equals(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_NOT_SIGNED", "只有处置厂签收并卸料后的空箱可以取回");
        }
        long id = ((Number) m.get("id")).longValue();
        List<String> boxCodes = db.list("SELECT box_code FROM manifest_items WHERE manifest_id=? ORDER BY id", id)
                .stream().map(r -> (String) r.get("box_code")).toList();
        String returnCode = Codes.next("HS");
        db.insert("INSERT INTO box_returns(code,manifest_id,box_codes,by_name) VALUES (?,?,?::jsonb,?)",
                returnCode, id, com.medwaste.common.Jsonx.write(boxCodes), user.displayName());
        for (String boxCode : boxCodes) {
            db.update("""
                    UPDATE boxes SET status='PENDING_CLEANING', custody_party='CLEANING',
                      custody_user_name=? WHERE code=?
                    """, user.displayName(), boxCode);
            custody.event("BOX", boxCode, "EMPTY_RETURNED", "PLANT", "CLEANING",
                    user.orgId(), user.displayName(),
                    "空箱取回，只进入待清洁区，不能直接作为新一轮可用箱；回送单 " + returnCode);
        }
        return Map.of("ok", true, "returnCode", returnCode, "boxCodes", boxCodes,
                "manifest", detail(code));
    }
}
