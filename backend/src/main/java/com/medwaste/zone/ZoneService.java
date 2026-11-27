package com.medwaste.zone;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 跨区人员任务 / 污染轮次 / 流程卡 + 独立复核 / 防护用品库存 的全部虚构规则。
 *
 * 核心不变量：
 * 1) 岗位资格有效 ≠ 可接触净区物品；净区资格只由“最近一轮污染轮次”的结清状态决定。
 * 2) 进入污染区立即产生只追加的一轮（IN_DIRTY），旧的 CLEARED 轮不能覆盖新进入事实。
 * 3) 只有 本轮流程卡检查合格 + 独立复核通过 + 关键退出回执已知 三者齐备才 CLEARED。
 * 4) 派工确认时锁定人员可用段（同人重叠 409）并把套件置 HELD；进入污染区才实耗库存；
 *    未进入即取消释放 HELD，已 CONSUMED 的用品与进区事实不因取消而回滚/删除。
 */
@Service
public class ZoneService {
    private final Db db;
    private final ZoneSeed seed;

    public ZoneService(Db db, ZoneSeed seed) {
        this.db = db;
        this.seed = seed;
    }

    // ======================================================================
    // 基础工具
    // ======================================================================

    private static int ai(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    private static Integer aiObj(Object o) { return o == null ? null : ((Number) o).intValue(); }

    private static String s(Map<String, Object> b, String... keys) {
        if (b == null) return null;
        for (String k : keys) {
            Object v = b.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
    }

    private static boolean flag(Map<String, Object> b, String key, boolean dflt) {
        if (b == null || b.get(key) == null) return dflt;
        Object v = b.get(key);
        if (v instanceof Boolean bb) return bb;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listVal(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        return List.of();
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        else if (v instanceof String str && !str.isBlank()) {
            for (String x : str.split(",")) if (!x.isBlank()) out.add(x.trim());
        }
        return out;
    }

    /** 接受 "HH:mm" / "HH:mm:ss" / 分钟数 / ISO 日期时间，返回自午夜分钟。 */
    static int parseMin(Object v) {
        if (v == null) throw ApiException.badRequest("CLOCK_BAD_TIME", "无法解析时间：空");
        if (v instanceof Number n) return n.intValue();
        String str = String.valueOf(v).trim();
        if (str.matches("-?\\d+")) return Integer.parseInt(str);
        String t = str;
        if (str.contains("T")) t = str.substring(str.indexOf('T') + 1);
        else if (str.contains(" ")) t = str.substring(str.indexOf(' ') + 1);
        if (t.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        }
        throw ApiException.badRequest("CLOCK_BAD_TIME", "时间需为 HH:mm 或分钟数：" + str);
    }

    static String hhmm(int min) {
        int m = ((min % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private int clockMin() {
        return ai(db.one("SELECT clock_min FROM zone_settings WHERE id=1").get("clock_min"));
    }

    /** 取动作时间（at/atMin/time），缺省用当前模拟时钟；并让时钟单向前进到该时刻。 */
    private int actTime(Map<String, Object> body) {
        int min = (body != null && s(body, "at", "atMin", "time") != null)
                ? parseMin(s(body, "at", "atMin", "time")) : clockMin();
        db.update("UPDATE zone_settings SET clock_min = GREATEST(clock_min, ?), updated_at=now() WHERE id=1", min);
        return min;
    }

    private Map<String, Object> mustStaff(String code) {
        return db.maybeOne("SELECT * FROM zone_staff WHERE code=?", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("ZONE_STAFF_NOT_FOUND", "人员不存在: " + code));
    }

    private Map<String, Object> lockStaff(String code) {
        return db.maybeOne("SELECT * FROM zone_staff WHERE code=? FOR UPDATE", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("ZONE_STAFF_NOT_FOUND", "人员不存在: " + code));
    }

    private Map<String, Object> mustTask(String code) {
        return db.maybeOne("SELECT * FROM zone_tasks WHERE code=?", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("ZONE_TASK_NOT_FOUND", "跨区任务不存在: " + code));
    }

    private Map<String, Object> lockTask(String code) {
        return db.maybeOne("SELECT * FROM zone_tasks WHERE code=? FOR UPDATE", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("ZONE_TASK_NOT_FOUND", "跨区任务不存在: " + code));
    }

    private Map<String, Object> lockRound(String code) {
        return db.maybeOne("SELECT * FROM zone_rounds WHERE code=? FOR UPDATE", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("ZONE_ROUND_NOT_FOUND", "污染轮次不存在: " + code));
    }

    private void event(Integer atMin, String staff, String task, String round,
                       String action, String actor, String detail) {
        db.insert("""
                INSERT INTO zone_events(at_min,staff_code,task_code,round_code,action,actor_name,detail)
                VALUES (?,?,?,?,?,?,?)
                """, atMin, staff, task, round, action, actor, detail);
    }

    private static Map<String, Object> ok(String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        if (action != null) m.put("action", action);
        return m;
    }

    // ======================================================================
    // 重置 / 时钟
    // ======================================================================

    @Transactional
    public Map<String, Object> reset() {
        seed.resetZone();
        return board();
    }

    public Map<String, Object> clock() {
        Map<String, Object> s = Views.of(db.one("SELECT * FROM zone_settings WHERE id=1"));
        int c = ai(s.get("clockMin"));
        s.put("nowHHmm", hhmm(c));
        return s;
    }

    @Transactional
    public Map<String, Object> setClock(Map<String, Object> body) {
        Integer min = null;
        if (body != null && body.get("nowMin") instanceof Number n) min = n.intValue();
        else if (body != null && s(body, "now", "time", "at") != null) min = parseMin(s(body, "now", "time", "at"));
        Integer advance = body != null && body.get("advanceMinutes") instanceof Number n ? n.intValue() : null;
        int cur = clockMin();
        int next = min != null ? min : (advance != null ? cur + advance : cur);
        db.update("UPDATE zone_settings SET clock_min=?, updated_at=now() WHERE id=1", next);
        return clock();
    }

    // ======================================================================
    // 净区资格状态机
    // ======================================================================

    private static final Set<String> RESTRICTING = Set.of(
            "IN_DIRTY", "EXITED", "CARD_IN_PROGRESS", "CHECK_FAILED", "INTERRUPTED",
            "REVIEW_PENDING", "REVIEW_FAILED", "AWAIT_RECEIPT");

    private Optional<Map<String, Object>> latestRound(String staffCode) {
        return db.maybeOne(
                "SELECT * FROM zone_rounds WHERE staff_code=? ORDER BY round_no DESC LIMIT 1", staffCode)
                .map(Views::of);
    }

    /** 根据最近一轮重算人员实际区域 / 污染状态 / 净区资格（唯一事实来源）。 */
    @Transactional
    public Map<String, Object> refreshEligibility(String staffCode) {
        Map<String, Object> staff = mustStaff(staffCode);
        Optional<Map<String, Object>> lr = latestRound(staffCode);
        boolean eligible;
        String zone;
        String contam;
        Long currentRoundId = null;
        if (lr.isEmpty() || "CLEARED".equals(lr.get().get("status"))) {
            eligible = true;
            zone = "CLEAN";
            contam = "CLEAN";
        } else {
            Map<String, Object> r = lr.get();
            String st = (String) r.get("status");
            eligible = false;
            contam = st;
            zone = "IN_DIRTY".equals(st) ? "DIRTY" : "TRANSITION";
            currentRoundId = ((Number) r.get("id")).longValue();
        }
        db.update("""
                UPDATE zone_staff
                   SET actual_zone=?, contam_state=?, clean_eligible=?, current_round_id=?
                 WHERE code=?
                """, zone, contam, eligible, currentRoundId, staffCode);
        return mustStaff(staffCode);
    }

    /** 净区派发/开始前的硬前提：资质之外，必须本轮已结清。失败给出最具体原因码。 */
    private void requireCleanEligible(String staffCode) {
        Map<String, Object> staff = refreshEligibility(staffCode);
        if (Boolean.TRUE.equals(staff.get("cleanEligible"))) return;
        Map<String, Object> r = latestRound(staffCode).orElse(null);
        String status = r == null ? "NONE" : (String) r.get("status");
        String roundCode = r == null ? null : (String) r.get("code");

        String code;
        String msg;
        if ("IN_DIRTY".equals(status)) {
            code = "STAFF_IN_DIRTY_ZONE";
            msg = "人员当前实际在污染区，尚未退出并完成本轮流程/复核，不能接触净区物品";
        } else if ("CHECK_FAILED".equals(status) || "FAIL".equals(r.get("checkStatus"))) {
            code = "PROCESS_CHECK_FAILED";
            msg = "本轮流程卡检查未通过，净区资格维持限制，需返工并重新检查/复核";
        } else if ("REVIEW_FAILED".equals(status) || "FAIL".equals(r.get("reviewStatus"))) {
            code = "INDEPENDENT_REVIEW_FAILED";
            msg = "本轮独立复核未通过，净区资格维持限制，需返工后重新复核";
        } else if ("INTERRUPTED".equals(status)) {
            code = "PROCESS_INTERRUPTED";
            msg = "本轮流程卡办理中断，未完成前不能恢复净区任务资格";
        } else if ("PENDING".equals(r.get("reviewStatus")) && "PASSED".equals(r.get("cardStatus"))
                && !"KNOWN".equals(r.get("exitReceiptStatus"))) {
            code = "EXIT_RECEIPT_UNKNOWN";
            msg = "退出关键回执仍未知（可能迟到），即使流程与复核已办，也暂不恢复净区资格";
        } else if (!"PASSED".equals(r.get("cardStatus"))) {
            code = "ZONE_TRANSITION_INCOMPLETE";
            msg = "人员退出污染区后尚未完成本轮虚构流程卡，不能跳过转换直接领取净区任务";
        } else if (!"PASS".equals(r.get("reviewStatus"))) {
            code = "INDEPENDENT_REVIEW_REQUIRED";
            msg = "本轮流程卡已完成，但尚缺独立复核通过，不能恢复净区任务资格";
        } else if (!"KNOWN".equals(r.get("exitReceiptStatus"))) {
            code = "EXIT_RECEIPT_UNKNOWN";
            msg = "退出关键回执未知/迟到，回执确认前维持原限制";
        } else {
            code = "STAFF_NOT_CLEAN_ELIGIBLE";
            msg = "人员本轮污染状态尚未结清，当前不允许接触净区物品";
        }
        throw ApiException.conflict(code, msg)
                .with("staffCode", staffCode)
                .with("roundCode", roundCode)
                .with("reasonCode", code)
                .with("cleanEligible", false);
    }

    private void requireQualification(Map<String, Object> staff, Map<String, Object> task) {
        List<String> have = strList(staff.get("quals"));
        List<String> need = strList(task.get("requiredQuals"));
        List<String> missing = need.stream().filter(q -> !have.contains(q)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.forbidden("STAFF_NOT_QUALIFIED",
                    "人员 " + staff.get("code") + " 缺少岗位资格: " + String.join(",", missing)
                            + "（资质有效也不能替代跨区状态前提）")
                    .with("staffCode", staff.get("code"))
                    .with("missingQuals", missing);
        }
    }

    // ======================================================================
    // 防护用品库存 / 预留
    // ======================================================================

    private Map<String, Integer> heldQtyBySku() {
        Map<String, Integer> held = new HashMap<>();
        for (Map<String, Object> r : Views.list(db.list(
                "SELECT status, lines FROM zone_ppe_reservation WHERE status='HELD'"))) {
            for (Object lo : listVal(r.get("lines"))) {
                if (lo instanceof Map<?, ?> line) {
                    String sku = String.valueOf(line.get("skuCode"));
                    int q = ai(line.get("qty"));
                    held.merge(sku, q, Integer::sum);
                }
            }
        }
        return held;
    }

    /** 套件行（含 sku 名称/单位/当前可用）。 */
    private List<Map<String, Object>> kitLines(String kitCode) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : Views.list(db.list(
                "SELECT * FROM zone_ppe_kit_line WHERE kit_code=? ORDER BY id", kitCode))) {
            Map<String, Object> line = new LinkedHashMap<>(l);
            Map<String, Object> sku = Views.of(db.one("SELECT * FROM zone_ppe_sku WHERE sku_code=?",
                    l.get("skuCode")));
            line.put("name", sku.get("name"));
            line.put("unit", sku.get("unit"));
            lines.add(line);
        }
        return lines;
    }

    @Transactional
    public Map<String, Object> restock(String skuCode, Map<String, Object> body, AppUser user) {
        int qty = 0;
        if (body != null) {
            if (body.get("qty") instanceof Number n) qty = n.intValue();
            else if (body.get("amount") instanceof Number a) qty = a.intValue();
            else if (body.get("delta") instanceof Number d && d.intValue() > 0) qty = d.intValue();
        }
        if (qty <= 0) throw ApiException.badRequest("PPE_BAD_QTY", "补货数量 qty 必须为正整数");
        Map<String, Object> sku = mustSku(skuCode);
        lockSkuRows(List.of(skuCode));
        db.update("UPDATE zone_ppe_sku SET on_hand=on_hand+? WHERE sku_code=?", qty, skuCode);
        int at = actTime(body);
        String reason = s(body, "reason") != null ? s(body, "reason") : "补货";
        db.insert("INSERT INTO zone_ppe_txn(sku_code,change_qty,txn_type,reason,at_min) VALUES (?,?,'RECEIVE',?,?)",
                skuCode, qty, reason, at);
        event(at, null, null, null, "PPE_RESTOCK", actor(user), sku.get("name") + " 补货 " + qty);
        Map<String, Object> res = ok("restock");
        res.put("sku", ppeSkuView(skuCode));
        return res;
    }

    @Transactional
    public Map<String, Object> adjust(String skuCode, Map<String, Object> body, AppUser user) {
        if (body == null || !(body.get("delta") instanceof Number)) {
            throw ApiException.badRequest("PPE_BAD_QTY", "调整库存需要数值字段 delta（可为负）");
        }
        int delta = ((Number) body.get("delta")).intValue();
        lockSkuRows(List.of(skuCode));
        Map<String, Object> sku = mustSku(skuCode);
        int onHand = ai(sku.get("onHand")) + delta;
        int held = heldQtyBySku().getOrDefault(skuCode, 0);
        if (onHand < 0) throw ApiException.badRequest("PPE_BAD_QTY", "调整后库存不能为负");
        if (onHand < held) {
            throw ApiException.conflict("PPE_HELD_CONFLICT",
                    "调整后现货 " + onHand + " 小于已预留 " + held + "，不能盘点下调");
        }
        db.update("UPDATE zone_ppe_sku SET on_hand=? WHERE sku_code=?", onHand, skuCode);
        int at = actTime(body);
        String reason = s(body, "reason") != null ? s(body, "reason") : "盘点/缺件模拟调整";
        db.insert("INSERT INTO zone_ppe_txn(sku_code,change_qty,txn_type,reason,at_min) VALUES (?,?,'ADJUST',?,?)",
                skuCode, delta, reason, at);
        Map<String, Object> res = ok("adjust");
        res.put("sku", ppeSkuView(skuCode));
        return res;
    }

    private Map<String, Object> mustSku(String code) {
        return db.maybeOne("SELECT * FROM zone_ppe_sku WHERE sku_code=?", code)
                .map(Views::of)
                .orElseThrow(() -> ApiException.notFound("PPE_SKU_NOT_FOUND", "防护用品不存在: " + code));
    }

    private void lockSkuRows(List<String> skuCodes) {
        List<String> sorted = skuCodes.stream().distinct().sorted().toList();
        for (String sc : sorted) {
            db.maybeOne("SELECT sku_code FROM zone_ppe_sku WHERE sku_code=? FOR UPDATE", sc)
                    .orElseThrow(() -> ApiException.notFound("PPE_SKU_NOT_FOUND", "防护用品不存在: " + sc));
        }
    }

    private Map<String, Object> ppeSkuView(String skuCode) {
        Map<String, Object> sku = mustSku(skuCode);
        Map<String, Integer> held = heldQtyBySku();
        int heldQ = held.getOrDefault(skuCode, 0);
        int onHand = ai(sku.get("onHand"));
        int consumed = ai(db.jdbc().queryForObject(
                "SELECT COALESCE(SUM((l->>'qty')::int),0) FROM zone_ppe_reservation r, "
                        + "jsonb_array_elements(r.lines) l WHERE r.status='CONSUMED' AND l->>'skuCode'=?",
                Integer.class, skuCode));
        sku.put("heldQty", heldQ);
        sku.put("available", onHand - heldQ);
        sku.put("consumedQty", consumed);
        sku.put("ok", onHand - heldQ >= ai(sku.get("safetyStock")));
        return sku;
    }

    // ======================================================================
    // 任务：创建 / 派发锁定 / 开始(进入污染区) / 完成 / 取消
    // ======================================================================

    @Transactional
    public Map<String, Object> createTask(Map<String, Object> body, AppUser user) {
        if (body == null || s(body, "title") == null || s(body, "zoneReq", "zone") == null) {
            throw ApiException.badRequest("TASK_BAD_REQUEST", "创建任务需要 title 与 zoneReq(CLEAN/DIRTY)");
        }
        String zone = s(body, "zoneReq", "zone").toUpperCase();
        if (!zone.equals("CLEAN") && !zone.equals("DIRTY")) {
            throw ApiException.badRequest("TASK_BAD_ZONE", "zoneReq 只能是 CLEAN / DIRTY");
        }
        String code = s(body, "code");
        if (code == null) code = "ZT" + Codes.next("").replace("-", "").substring(0, 6);
        code = code.toUpperCase();
        if (db.maybeOne("SELECT id FROM zone_tasks WHERE code=?", code).isPresent()) {
            throw ApiException.conflict("ZONE_TASK_CODE_EXISTS", "任务编号已存在: " + code);
        }
        int dur = body.get("durationMin") instanceof Number n ? n.intValue()
                : body.get("duration") instanceof Number d ? d.intValue() : 10;
        Integer release = body.get("releaseMin") instanceof Number r ? r.intValue()
                : (s(body, "startAt") != null ? parseMin(s(body, "startAt")) : null);
        Integer deadline = body.get("deadlineMin") instanceof Number dl ? dl.intValue()
                : (s(body, "deadlineAt") != null ? parseMin(s(body, "deadlineAt")) : null);
        List<String> depends = strList(body.get("dependsOn"));
        List<String> quals = strList(body.get("requiredQuals"));
        String kit = s(body, "kitCode", "kit");
        if ("DIRTY".equals(zone) && kit == null) kit = "KIT-DIRTY-STD";
        db.insert("""
                INSERT INTO zone_tasks(code,title,zone_req,duration_min,release_min,deadline_min,
                                      depends_on,required_quals,kit_code,status)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,'OPEN')
                """, code, s(body, "title"), zone, dur, release, deadline,
                JsonxWrite(depends), JsonxWrite(quals), kit);
        event(clockMin(), null, code, null, "TASK_CREATED", actor(user), "新建" + zone + "任务 " + code);
        Map<String, Object> res = ok("createTask");
        res.put("task", taskView(code));
        return res;
    }

    private static String JsonxWrite(Object o) {
        return com.medwaste.common.Jsonx.write(o);
    }

    private Map<String, Object> taskView(String code) {
        return Views.of(db.one("SELECT * FROM zone_tasks WHERE code=?", code));
    }

    /** 确认派发：锁人员可用段 + 锁防护套件（HELD）。claim 即从当前模拟时刻起派给自己。 */
    @Transactional
    public Map<String, Object> assign(String taskCode, Map<String, Object> body, AppUser user, boolean claim) {
        Map<String, Object> task = lockTask(taskCode);
        if (!"OPEN".equals(task.get("status"))) {
            throw ApiException.conflict("TASK_ALREADY_CLAIMED",
                    "任务 " + taskCode + " 当前状态 " + task.get("status")
                            + (task.get("assigneeCode") != null ? "，已由 " + task.get("assigneeCode") + " 负责" : ""))
                    .with("taskCode", taskCode)
                    .with("assigneeCode", task.get("assigneeCode"))
                    .with("status", task.get("status"));
        }
        String staffCode = s(body, "staffCode", "staff", "workerCode", "assignee");
        if (claim && staffCode == null) {
            // 允许用登录账号认领：把 username 映射到 zone_staff
            staffCode = staffCodeByUsername(user == null ? null : user.username());
        }
        if (staffCode == null) throw ApiException.badRequest("STAFF_CODE_REQUIRED", "派发需要 staffCode（如 W1/W2）");
        staffCode = staffCode.toUpperCase();

        Map<String, Object> staff = lockStaff(staffCode);
        if (!Boolean.TRUE.equals(staff.get("active"))) {
            throw ApiException.conflict("STAFF_INACTIVE", "人员已停用: " + staffCode);
        }
        requireQualification(staff, task);

        int now = clockMin();
        Integer startMin;
        if (claim) {
            startMin = (body != null && s(body, "startAt", "at") != null)
                    ? parseMin(s(body, "startAt", "at")) : now;
        } else {
            startMin = body != null && body.get("startMin") instanceof Number n ? n.intValue()
                    : (body != null && s(body, "startAt") != null ? parseMin(s(body, "startAt")) : now);
        }
        int endMin = startMin + ai(task.get("durationMin"));

        // 净区任务：派发当下就必须具备净区资格（不能“先占上、跳过转换”）
        if ("CLEAN".equals(task.get("zoneReq"))) {
            requireCleanEligible(staffCode);
        }

        // 同一人员可用段重叠裁决（半开区间：首尾相接允许）
        Optional<Map<String, Object>> overlap = db.maybeOne("""
                SELECT code, title, start_min, end_min, status, zone_req
                  FROM zone_tasks
                 WHERE assignee_code=? AND status IN ('CONFIRMED','IN_PROGRESS')
                   AND start_min < ? AND end_min > ?
                 LIMIT 1
                """, staffCode, endMin, startMin).map(Views::of);
        if (overlap.isPresent()) {
            Map<String, Object> o = overlap.get();
            throw ApiException.conflict("STAFF_SEGMENT_CONFLICT",
                    "人员 " + staffCode + " 在 " + hhmm(startMin) + "-" + hhmm(endMin)
                            + " 与已锁定任务 " + o.get("code") + "(" + hhmm(ai(o.get("startMin")))
                            + "-" + hhmm(ai(o.get("endMin"))) + ") 时间段重叠，不能同人两区/并发接单")
                    .with("staffCode", staffCode)
                    .with("conflictTaskCode", o.get("code"))
                    .with("conflictStartMin", o.get("startMin"))
                    .with("conflictEndMin", o.get("endMin"))
                    .with("startMin", startMin).with("endMin", endMin);
        }

        // 防护套件：缺件整体阻止派发（不产生半截预留）
        String reservationCode = null;
        String kitCode = (String) task.get("kitCode");
        if (kitCode != null && !kitCode.isBlank()) {
            List<Map<String, Object>> lines = kitLines(kitCode);
            List<String> skuCodes = lines.stream().map(l -> (String) l.get("skuCode")).toList();
            lockSkuRows(skuCodes);
            Map<String, Integer> held = heldQtyBySku();
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Map<String, Object> line : lines) {
                String sku = (String) line.get("skuCode");
                int need = ai(line.get("qty"));
                int onHand = ai(mustSku(sku).get("onHand"));
                int available = onHand - held.getOrDefault(sku, 0);
                if (available < need) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("skuCode", sku);
                    m.put("name", line.get("name"));
                    m.put("required", need);
                    m.put("available", Math.max(available, 0));
                    m.put("short", need - available);
                    missing.add(m);
                }
            }
            if (!missing.isEmpty()) {
                throw ApiException.conflict("PPE_SHORTAGE",
                        "防护用品缺件，不能确认派发/进入污染区：" + missing.stream()
                                .map(m -> m.get("name") + " 缺 " + m.get("short")).toList())
                        .with("kitCode", kitCode).with("missing", missing);
            }
            reservationCode = Codes.next("PPE");
            int confirmAt = body != null && s(body, "at") != null ? actTime(body) : now;
            db.insert("""
                    INSERT INTO zone_ppe_reservation(code,task_code,staff_code,kit_code,status,lines,created_min)
                    VALUES (?,?,?,?,'HELD',?::jsonb,?)
                    """, reservationCode, taskCode, staffCode, kitCode, JsonxWrite(lines), confirmAt);
        }

        int confirmMin = body != null && s(body, "at") != null ? actTime(body) : now;
        db.update("""
                UPDATE zone_tasks
                   SET status='CONFIRMED', assignee_code=?, start_min=?, end_min=?,
                       reservation_code=?, confirmed_by=?, confirmed_at_min=?
                 WHERE code=?
                """, staffCode, startMin, endMin, reservationCode, actor(user), confirmMin, taskCode);
        event(confirmMin, staffCode, taskCode, null, "TASK_ASSIGNED", actor(user),
                "锁定 " + hhmm(startMin) + "-" + hhmm(endMin) + " 可用段"
                        + (reservationCode != null ? "，预留用品 " + reservationCode : ""));

        Map<String, Object> res = ok(claim ? "claim" : "assign");
        res.put("task", taskView(taskCode));
        res.put("staff", refreshEligibility(staffCode));
        if (reservationCode != null) res.put("reservationCode", reservationCode);
        return res;
    }

    private String staffCodeByUsername(String username) {
        if (username == null) return null;
        return db.maybeOne("SELECT code FROM zone_staff WHERE username=?", username)
                .map(r -> (String) r.get("code")).orElse(null);
    }

    @Transactional
    public Map<String, Object> startTask(String taskCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> task = lockTask(taskCode);
        // 宽容：OPEN 且带 staffCode 时，先按确认派发处理
        if ("OPEN".equals(task.get("status"))) {
            assign(taskCode, body == null ? new LinkedHashMap<>() : body, user, false);
            task = lockTask(taskCode);
        }
        if (!"CONFIRMED".equals(task.get("status"))) {
            throw ApiException.conflict("TASK_STATUS_CONFLICT",
                    "只有已确认(CONFIRMED)任务可开始，当前 " + task.get("status"))
                    .with("taskCode", taskCode).with("status", task.get("status"));
        }
        String staffCode = (String) task.get("assigneeCode");
        int at = actTime(body);

        // 前置任务必须完成（C 必须等 P，P 必须等 A）
        for (Object d0 : strList(task.get("dependsOn"))) {
            String d = String.valueOf(d0);
            Map<String, Object> dt = mustTask(d);
            if (!"DONE".equals(dt.get("status"))) {
                throw ApiException.conflict("TASK_DEPENDENCY_NOT_DONE",
                        "任务 " + taskCode + " 需在前置任务 " + d + " 完成后才能开始（当前 "
                                + dt.get("status") + "）")
                        .with("taskCode", taskCode).with("dependencyCode", d)
                        .with("dependencyStatus", dt.get("status"));
            }
        }

        String zone = (String) task.get("zoneReq");
        Map<String, Object> res = ok("start");
        if ("CLEAN".equals(zone)) {
            // 开始净区任务前再次裁决净区资格（转换可能尚未完成）
            requireCleanEligible(staffCode);
            db.update("UPDATE zone_tasks SET status='IN_PROGRESS', started_by=?, started_at_min=?, actual_start_min=? WHERE code=?",
                    actor(user), at, at, taskCode);
            event(at, staffCode, taskCode, null, "TASK_STARTED", actor(user), "开始净区任务");
            res.put("staff", refreshEligibility(staffCode));
        } else {
            // 进入污染区：同一人不能有两轮未结清的污染轮次
            Optional<Map<String, Object>> active = db.maybeOne("""
                    SELECT code FROM zone_rounds
                     WHERE staff_code=? AND status <> 'CLEARED'
                     ORDER BY round_no DESC LIMIT 1
                    """, staffCode).map(Views::of);
            if (active.isPresent()) {
                throw ApiException.conflict("OPEN_CONTAMINATION_ROUND",
                        "人员 " + staffCode + " 尚有未结清污染轮次 " + active.get().get("code")
                                + "，不能再次进入污染区/同人两区并发接单")
                        .with("roundCode", active.get().get("code"));
            }

            // 实耗确认时 HELD 的套件：扣现货，预留单转 CONSUMED（不可退未用）
            String reservationCode = (String) task.get("reservationCode");
            Map<String, Object> reservation = null;
            if (reservationCode != null) {
                reservation = db.maybeOne("SELECT * FROM zone_ppe_reservation WHERE code=? FOR UPDATE",
                        reservationCode).map(Views::of).orElse(null);
            }
            List<Map<String, Object>> consumedLines;
            if (reservation != null && "HELD".equals(reservation.get("status"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> lines = (List<Map<String, Object>>) (List<?>) listVal(reservation.get("lines"));
                List<String> skuCodes = lines.stream().map(l -> String.valueOf(l.get("skuCode"))).toList();
                lockSkuRows(skuCodes);
                for (Map<String, Object> line : lines) {
                    String sku = String.valueOf(line.get("skuCode"));
                    int q = ai(line.get("qty"));
                    int upd = db.update("UPDATE zone_ppe_sku SET on_hand=on_hand-? WHERE sku_code=? AND on_hand>=?",
                            q, sku, q);
                    if (upd == 0) throw ApiException.conflict("PPE_SHORTAGE", "防护用品现货不足: " + sku)
                            .with("skuCode", sku);
                    db.insert("INSERT INTO zone_ppe_txn(sku_code,change_qty,txn_type,reason,task_code,at_min) "
                            + "VALUES (?,-?,'CONSUME','进入污染区实际消耗',?,?)", sku, q, taskCode, at);
                }
                db.update("UPDATE zone_ppe_reservation SET status='CONSUMED', consumed_min=? WHERE code=?",
                        at, reservationCode);
                consumedLines = lines;
                event(at, staffCode, taskCode, null, "PPE_CONSUMED", actor(user),
                        "进入污染区，实际消耗套件 " + reservationCode + "（取消任务也不退回未用）");
            } else if (task.get("kitCode") != null) {
                throw ApiException.conflict("PPE_RESERVATION_MISSING",
                        "任务缺少 HELD 防护套件预留，不能进入污染区");
            } else {
                consumedLines = List.of();
            }

            int roundNo = ai(db.jdbc().queryForObject(
                    "SELECT COALESCE(MAX(round_no),0)+1 FROM zone_rounds WHERE staff_code=?",
                    Integer.class, staffCode));
            String roundCode = Codes.next("ZR");
            db.insert("""
                    INSERT INTO zone_rounds(code,staff_code,round_no,task_code,status,entry_min,
                                            exit_receipt_status,kit_code,reservation_code,kit_lines,entry_actor)
                    VALUES (?,?,?,?,'IN_DIRTY',?,'UNKNOWN',?,?,?::jsonb,?)
                    """, roundCode, staffCode, roundNo, taskCode, at,
                    task.get("kitCode"), reservationCode, JsonxWrite(consumedLines), actor(user));
            long roundId = db.one("SELECT id FROM zone_rounds WHERE code=?", roundCode).get("id") instanceof Number nn
                    ? nn.longValue() : 0L;
            for (Map<String, Object> tpl : Views.list(db.list(
                    "SELECT * FROM zone_card_template ORDER BY seq"))) {
                db.insert("""
                        INSERT INTO zone_round_steps(round_id,step_code,seq,label,inspection,status)
                        VALUES (?,?,?,?,?,'PENDING')
                        """, roundId, tpl.get("stepCode"), ai(tpl.get("seq")),
                        tpl.get("label"), Boolean.TRUE.equals(tpl.get("inspection")));
            }
            db.update("UPDATE zone_tasks SET status='IN_PROGRESS', started_by=?, started_at_min=?, actual_start_min=? WHERE code=?",
                    actor(user), at, at, taskCode);
            event(at, staffCode, taskCode, roundCode, "ZONE_ENTERED", actor(user),
                    "第 " + roundNo + " 次进入污染区，产生本轮污染状态（旧清洁记录不得覆盖）");
            Map<String, Object> staff = refreshEligibility(staffCode);
            res.put("round", roundView(roundCode));
            res.put("staff", staff);
        }
        res.put("task", taskView(taskCode));
        return res;
    }

    @Transactional
    public Map<String, Object> completeTask(String taskCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> task = lockTask(taskCode);
        if (!"IN_PROGRESS".equals(task.get("status"))) {
            throw ApiException.conflict("TASK_STATUS_CONFLICT",
                    "只有进行中(IN_PROGRESS)任务可完成，当前 " + task.get("status"))
                    .with("status", task.get("status"));
        }
        int at = actTime(body);
        db.update("UPDATE zone_tasks SET status='DONE', completed_by=?, completed_at_min=?, actual_end_min=? WHERE code=?",
                actor(user), at, at, taskCode);
        event(at, (String) task.get("assigneeCode"), taskCode, null, "TASK_DONE", actor(user), "任务完成");
        Map<String, Object> res = ok("complete");
        res.put("task", taskView(taskCode));
        return res;
    }

    /** 主管取消：未进入→释放段与 HELD 用品；已进入→实耗不退回、轮次/进区事实保留。 */
    @Transactional
    public Map<String, Object> cancelTask(String taskCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> task = lockTask(taskCode);
        String st = (String) task.get("status");
        if ("DONE".equals(st) || "CANCELLED".equals(st)) {
            throw ApiException.conflict("TASK_STATUS_CONFLICT", "任务已 " + st + "，不能取消");
        }
        int at = actTime(body);
        String reason = s(body, "reason", "note") != null ? s(body, "reason", "note") : "主管取消";
        String staffCode = (String) task.get("assigneeCode");
        boolean entered = "IN_PROGRESS".equals(st);

        String resvCode = (String) task.get("reservationCode");
        String ppeEffect = "无预留用品";
        if (resvCode != null) {
            Map<String, Object> resv = db.maybeOne(
                    "SELECT * FROM zone_ppe_reservation WHERE code=? FOR UPDATE", resvCode)
                    .map(Views::of).orElse(null);
            if (resv != null && "HELD".equals(resv.get("status"))) {
                db.update("UPDATE zone_ppe_reservation SET status='RELEASED', released_min=? WHERE code=?", at, resvCode);
                ppeEffect = "未进入污染区，预留用品释放为可用";
            } else if (resv != null && "CONSUMED".equals(resv.get("status"))) {
                ppeEffect = "用品已实际消耗，不退回未用库存";
            }
        }

        db.update("""
                UPDATE zone_tasks SET status='CANCELLED', cancel_reason=?, cancelled_by=?, cancelled_at_min=?
                 WHERE code=?
                """, reason, actor(user), at, taskCode);
        event(at, staffCode, taskCode, null, "TASK_CANCELLED", actor(user),
                "主管取消任务：" + reason + "；" + ppeEffect
                        + (entered ? "；实际进区记录与污染限制保留" : ""));
        Map<String, Object> staff = staffCode == null ? null : refreshEligibility(staffCode);

        Map<String, Object> res = ok("cancel");
        res.put("task", taskView(taskCode));
        res.put("ppeEffect", ppeEffect);
        res.put("zoneEnteredRecordKept", entered);
        if (staff != null) res.put("staff", staff);
        return res;
    }

    // ======================================================================
    // 污染轮次：退出 / 流程卡 / 独立复核 / 迟到回执 / 结清裁决
    // ======================================================================

    private Map<String, Object> roundView(String code) {
        Map<String, Object> r = Views.of(db.one("SELECT * FROM zone_rounds WHERE code=?", code));
        r.put("steps", Views.list(db.list(
                "SELECT * FROM zone_round_steps WHERE round_id=? ORDER BY seq",
                ((Number) r.get("id")).longValue())));
        return r;
    }

    // 资源级详情（供单独查询；board 已包含同样信息）
    public Map<String, Object> staffDetail(String code) {
        Map<String, Object> s = mustStaff(code);
        latestRound(code).ifPresent(r -> {
            s.put("activeRound", "CLEARED".equals(r.get("status")) ? null : roundView((String) r.get("code")));
            s.put("latestRound", roundView((String) r.get("code")));
        });
        List<Map<String, Object>> segs = new ArrayList<>();
        for (Map<String, Object> t : Views.list(db.list("SELECT * FROM zone_tasks WHERE assignee_code=? ORDER BY id", code))) {
            if (Set.of("CONFIRMED", "IN_PROGRESS").contains(t.get("status"))) segs.add(t);
        }
        s.put("segments", segs);
        return s;
    }

    public Map<String, Object> taskDetail(String code) {
        return taskView(code);
    }

    public Map<String, Object> roundDetail(String code) {
        return roundView(code);
    }

    @Transactional
    public Map<String, Object> exitRound(String roundCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> r = lockRound(roundCode);
        if ("CLEARED".equals(r.get("status"))) {
            throw ApiException.conflict("ROUND_ALREADY_CLEARED", "该轮已复核结清，无需退出");
        }
        if (r.get("exitMin") != null) {
            throw ApiException.conflict("ROUND_ALREADY_EXITED",
                    "该轮已报告退出；回执补登请调用 exit-receipt").with("roundCode", roundCode);
        }
        int at = actTime(body);
        // 缺省回执即时已知；显式 receiptKnown:false 模拟关键回执未知/迟到
        boolean receiptKnown = flag(body, "receiptKnown", true);
        String receiptCode = s(body, "receiptCode");
        if (receiptKnown) {
            if (receiptCode == null) receiptCode = Codes.next("RC");
            db.update("""
                    UPDATE zone_rounds SET exit_min=?, status='EXITED',
                      exit_receipt_status='KNOWN', exit_receipt_code=?, exit_receipt_min=?, receipt_late=FALSE
                     WHERE code=?
                    """, at, receiptCode, at, roundCode);
        } else {
            db.update("""
                    UPDATE zone_rounds SET exit_min=?, status='EXITED', exit_receipt_status='UNKNOWN'
                     WHERE code=?
                    """, at, roundCode);
        }
        event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode, "ZONE_EXITED",
                actor(user), receiptKnown ? "退出污染区，退出回执 " + receiptCode + " 已知"
                        : "退出污染区，但关键退出回执未知（可能迟到），限制不解除");
        Map<String, Object> staff = refreshEligibility((String) r.get("staffCode"));
        Map<String, Object> res = ok("exit");
        res.put("round", roundView(roundCode));
        res.put("staff", staff);
        return res;
    }

    @Transactional
    public Map<String, Object> recordStep(String roundCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> r = lockRound(roundCode);
        if ("CLEARED".equals(r.get("status"))) {
            throw ApiException.conflict("ROUND_ALREADY_CLEARED", "该轮已结清，不能再办流程卡");
        }
        String stepCode = s(body, "stepCode", "step");
        if (stepCode == null) throw ApiException.badRequest("STEP_CODE_REQUIRED", "需要 stepCode");
        Map<String, Object> step = db.maybeOne(
                "SELECT * FROM zone_round_steps WHERE round_id=? AND step_code=? FOR UPDATE",
                ((Number) r.get("id")).longValue(), stepCode).map(Views::of)
                .orElseThrow(() -> ApiException.notFound("STEP_NOT_FOUND", "本轮流程卡无此步骤: " + stepCode));
        int at = actTime(body);
        String actor = actor(user);
        boolean inspection = Boolean.TRUE.equals(step.get("inspection"));

        if (inspection) {
            Boolean passed = body == null ? null
                    : (body.get("passed") instanceof Boolean pb ? pb
                    : body.get("pass") instanceof Boolean x ? x
                    : body.get("ok") instanceof Boolean okb ? okb
                    : ("PASS".equalsIgnoreCase(s(body, "result", "decision")) ? Boolean.TRUE
                    : "FAIL".equalsIgnoreCase(s(body, "result", "decision")) ? Boolean.FALSE : null));
            if (passed == null) {
                throw ApiException.badRequest("INSPECTION_RESULT_REQUIRED",
                        "检查步骤必须给出 passed:true/false（或 result=PASS/FAIL）");
            }
            if (passed) {
                db.update("""
                        UPDATE zone_round_steps SET status='DONE', passed=TRUE, note=?, actor=?, at_min=?, updated_at=now()
                         WHERE id=?""", s(body, "note"), actor, at, ((Number) step.get("id")).longValue());
                db.update("UPDATE zone_rounds SET check_status='PASS', status='CARD_IN_PROGRESS' WHERE code=?", roundCode);
                event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                        "CARD_INSPECTION_PASS", actor, "流程卡检查合格");
            } else {
                db.update("""
                        UPDATE zone_round_steps SET status='FAILED', passed=FALSE, note=?, actor=?, at_min=?, updated_at=now()
                         WHERE id=?""", s(body, "note") != null ? s(body, "note") : "检查未过",
                        actor, at, ((Number) step.get("id")).longValue());
                db.update("UPDATE zone_rounds SET check_status='FAIL', card_status='FAILED', status='CHECK_FAILED' WHERE code=?",
                        roundCode);
                event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                        "CARD_INSPECTION_FAIL", actor, "流程卡检查未通过，净区限制保持，需返工");
            }
        } else if (flag(body, "interrupted", false)) {
            db.update("""
                    UPDATE zone_round_steps SET status='INTERRUPTED', note=?, actor=?, at_min=?, updated_at=now()
                     WHERE id=?""", s(body, "reason", "note") != null ? s(body, "reason", "note") : "流程中断",
                    actor, at, ((Number) step.get("id")).longValue());
            db.update("UPDATE zone_rounds SET card_status='INTERRUPTED', status='INTERRUPTED' WHERE code=?", roundCode);
            event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                    "PROCESS_INTERRUPTED", actor, "流程卡步骤 " + stepCode + " 中断，限制保持");
        } else {
            db.update("""
                    UPDATE zone_round_steps SET status='DONE', passed=NULL, note=?, actor=?, at_min=?, updated_at=now()
                     WHERE id=?""", s(body, "note"), actor, at, ((Number) step.get("id")).longValue());
            // 返工恢复：若此前中断/检查失败/复核失败，重新进入办理中
            db.update("""
                    UPDATE zone_rounds
                       SET card_status='IN_PROGRESS',
                           status='CARD_IN_PROGRESS'
                     WHERE code=? AND status IN ('EXITED','INTERRUPTED','CHECK_FAILED','REVIEW_FAILED')
                    """, roundCode);
            event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                    "CARD_STEP_DONE", actor, "完成流程卡步骤 " + stepCode);
        }
        Map<String, Object> staff = refreshEligibility((String) r.get("staffCode"));
        Map<String, Object> res = ok("step");
        res.put("round", roundView(roundCode));
        res.put("staff", staff);
        return res;
    }

    @Transactional
    public Map<String, Object> completeCard(String roundCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> r = lockRound(roundCode);
        if ("CLEARED".equals(r.get("status"))) {
            throw ApiException.conflict("ROUND_ALREADY_CLEARED", "该轮已结清");
        }
        List<Map<String, Object>> steps = Views.list(db.list(
                "SELECT * FROM zone_round_steps WHERE round_id=? ORDER BY seq",
                ((Number) r.get("id")).longValue()));
        List<String> interrupted = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean checkFail = false;
        for (Map<String, Object> st : steps) {
            switch ((String) st.get("status")) {
                case "INTERRUPTED" -> interrupted.add((String) st.get("stepCode"));
                case "PENDING" -> pending.add((String) st.get("stepCode"));
                case "FAILED" -> checkFail = true;
                default -> { }
            }
        }
        if (!interrupted.isEmpty()) {
            throw ApiException.conflict("PROCESS_INTERRUPTED",
                    "流程卡存在中断步骤 " + interrupted + "，恢复并完成前不能提交").with("interruptedSteps", interrupted);
        }
        if (checkFail || "FAIL".equals(r.get("checkStatus"))) {
            throw ApiException.conflict("PROCESS_CHECK_FAILED",
                    "检查未通过，不能完成流程卡；请返工后重新检查合格").with("reasonCode", "PROCESS_CHECK_FAILED");
        }
        if (!pending.isEmpty()) {
            throw ApiException.conflict("PROCESS_CARD_INCOMPLETE",
                    "流程卡尚有步骤未完成: " + pending).with("pendingSteps", pending);
        }
        int at = actTime(body);
        // 若上一轮独立复核曾 FAIL，返工整卡后复核结论作废，必须重新独立复核
        db.update("""
                UPDATE zone_rounds
                   SET card_status='PASSED', check_status='PASS', review_status='PENDING',
                       reviewer_code=NULL, reviewer_name=NULL, reviewer_username=NULL,
                       reviewed_min=NULL, review_note=NULL, status='REVIEW_PENDING'
                 WHERE code=?
                """, roundCode);
        event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                "CARD_COMPLETED", actor(user), "本轮流程卡全部完成且检查合格，待独立复核");
        boolean cleared = evaluateClearance(roundCode, at);
        Map<String, Object> staff = refreshEligibility((String) r.get("staffCode"));
        Map<String, Object> res = ok("completeCard");
        res.put("round", roundView(roundCode));
        res.put("staff", staff);
        res.put("cleared", cleared);
        return res;
    }

    @Transactional
    public Map<String, Object> reviewRound(String roundCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> r = lockRound(roundCode);
        if ("CLEARED".equals(r.get("status"))) {
            throw ApiException.conflict("ROUND_ALREADY_CLEARED", "该轮已结清，复核不能重复改变结论");
        }
        if (!"PASSED".equals(r.get("cardStatus")) || !"PASS".equals(r.get("checkStatus"))) {
            throw ApiException.conflict("REVIEW_PREREQUISITE_MISSING",
                    "独立复核必须在本轮流程卡完成且检查合格后进行").with("reasonCode", "PROCESS_CARD_INCOMPLETE");
        }
        String decision = s(body, "decision", "result");
        boolean pass;
        if (decision != null) pass = decision.equalsIgnoreCase("PASS") || decision.equalsIgnoreCase("APPROVE");
        else pass = flag(body, "pass", flag(body, "approved", true));

        // 复核人解析：显式 reviewerCode 优先，否则用登录账号映射；独立性是硬不变量
        String reviewerCode = s(body, "reviewerCode", "reviewer");
        String reviewerUsername = user == null ? null : user.username();
        if (reviewerCode == null && reviewerUsername != null) reviewerCode = staffCodeByUsername(reviewerUsername);
        if (reviewerCode == null) reviewerCode = "USER:" + reviewerUsername;
        String staffCode = (String) r.get("staffCode");

        if (reviewerCode.equals(staffCode)
                || ("W1".equals(staffCode) && "w1".equals(reviewerUsername))
                || ("W2".equals(staffCode) && "w2".equals(reviewerUsername))) {
            throw ApiException.conflict("REVIEW_NOT_INDEPENDENT",
                    "独立复核人不能是当事人本人（" + staffCode + "）；自检自核无效，净区限制保持")
                    .with("staffCode", staffCode).with("reviewerCode", reviewerCode);
        }
        String reviewerName = s(body, "reviewerName");
        if (reviewerName == null && user != null) reviewerName = user.displayName();
        if (reviewerName == null) reviewerName = reviewerCode;

        int at = actTime(body);
        if (!pass) {
            db.update("""
                    UPDATE zone_rounds SET review_status='FAIL', status='REVIEW_FAILED',
                      reviewer_code=?, reviewer_name=?, reviewer_username=?, reviewed_min=?, review_note=?
                     WHERE code=?
                    """, reviewerCode, reviewerName, reviewerUsername, at,
                    s(body, "note", "reason") != null ? s(body, "note", "reason") : "独立复核未通过", roundCode);
            event(at, staffCode, (String) r.get("taskCode"), roundCode, "REVIEW_FAILED", reviewerName,
                    "独立复核未通过：" + s(body, "note", "reason"));
        } else {
            db.update("""
                    UPDATE zone_rounds SET review_status='PASS', reviewer_code=?, reviewer_name=?,
                      reviewer_username=?, reviewed_min=?, review_note=?
                     WHERE code=?
                    """, reviewerCode, reviewerName, reviewerUsername, at, s(body, "note"), roundCode);
            event(at, staffCode, (String) r.get("taskCode"), roundCode, "REVIEW_PASSED", reviewerName,
                    "独立复核通过（复核人 " + reviewerCode + "）");
        }
        boolean cleared = evaluateClearance(roundCode, at);
        Map<String, Object> staff = refreshEligibility(staffCode);
        Map<String, Object> res = ok("review");
        res.put("round", roundView(roundCode));
        res.put("staff", staff);
        res.put("cleared", cleared);
        return res;
    }

    @Transactional
    public Map<String, Object> receiveExitReceipt(String roundCode, Map<String, Object> body, AppUser user) {
        Map<String, Object> r = lockRound(roundCode);
        int at = actTime(body);
        String receiptCode = s(body, "receiptCode");
        if (receiptCode == null) receiptCode = Codes.next("RC");
        boolean wasUnknown = !"KNOWN".equals(r.get("exitReceiptStatus"));
        boolean late = wasUnknown && r.get("exitMin") != null;
        db.update("""
                UPDATE zone_rounds SET exit_receipt_status='KNOWN', exit_receipt_code=?,
                  exit_receipt_min=?, receipt_late=?
                 WHERE code=?
                """, receiptCode, at, late, roundCode);
        event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                "EXIT_RECEIPT_KNOWN", actor(user),
                (late ? "迟到的退出回执 now 到达" : "退出回执登记") + "：" + receiptCode);
        boolean cleared = evaluateClearance(roundCode, at);
        Map<String, Object> staff = refreshEligibility((String) r.get("staffCode"));
        Map<String, Object> res = ok("exitReceipt");
        res.put("late", late);
        res.put("cleared", cleared);
        res.put("round", roundView(roundCode));
        res.put("staff", staff);
        return res;
    }

    /** 三闸门齐备才 CLEARED，否则推导到最贴近的等待状态；返回是否已结清。 */
    private boolean evaluateClearance(String roundCode, int at) {
        Map<String, Object> r = Views.of(db.one("SELECT * FROM zone_rounds WHERE code=?", roundCode));
        boolean cardPassed = "PASSED".equals(r.get("cardStatus")) && "PASS".equals(r.get("checkStatus"));
        boolean reviewPassed = "PASS".equals(r.get("reviewStatus"));
        boolean receiptKnown = "KNOWN".equals(r.get("exitReceiptStatus"));
        if (cardPassed && reviewPassed && receiptKnown) {
            db.update("UPDATE zone_rounds SET status='CLEARED', cleared_min=? WHERE code=?", at, roundCode);
            event(at, (String) r.get("staffCode"), (String) r.get("taskCode"), roundCode,
                    "ROUND_CLEARED", "SYSTEM",
                    "本轮流程卡检查、独立复核、退出回执均齐备，恢复净区任务资格");
            return true;
        }
        // 尚未齐备：把聚合状态校正到最具体的等待态（不覆盖 FAIL / INTERRUPTED）
        String cur = (String) r.get("status");
        if (!Set.of("CHECK_FAILED", "REVIEW_FAILED", "INTERRUPTED").contains(cur)) {
            String target;
            if (!rContainsExit(r)) target = "IN_DIRTY";
            else if (!cardPassed) target = "REVIEW_PENDING".equals(cur) ? "REVIEW_PENDING" : "EXITED";
            else if (!reviewPassed) target = "REVIEW_PENDING";
            else target = "AWAIT_RECEIPT";
            if (!target.equals(cur)) {
                db.update("UPDATE zone_rounds SET status=? WHERE code=?", target, roundCode);
            }
        }
        return false;
    }

    private boolean rContainsExit(Map<String, Object> r) {
        return r.get("exitMin") != null;
    }

    private String actor(AppUser user) {
        return user == null ? "SYSTEM" : user.displayName();
    }

    // ======================================================================
    // 两路派发方案（数据驱动的确定性演算）
    // ======================================================================

    public Map<String, Object> dispatchPlans() {
        Map<String, Object> settingRaw = db.one("SELECT transition_min FROM zone_settings WHERE id=1");
        int transition = ai(settingRaw.get("transition_min"));
        int base = 600;
        Map<String, Object> A = taskOrNull("A"), P = taskOrNull("P"), C = taskOrNull("C");
        int aDur = A == null ? 10 : ai(A.get("durationMin"));
        int pDur = P == null ? 15 : ai(P.get("durationMin"));
        int cDur = C == null ? 10 : ai(C.get("durationMin"));
        if (A != null && A.get("releaseMin") instanceof Number rn) base = rn.intValue();
        Integer deadline = C == null ? 660 : (C.get("deadlineMin") instanceof Number d ? d.intValue() : 660);

        int a0 = base, a1 = a0 + aDur;
        int p0 = a1, p1 = p0 + pDur;

        // 方案一：固定分区 —— W1 守净区(A,C)，W2 守污染区(P)，W1 不进污染区、无转换
        int fC0 = p1, fC1 = fC0 + cDur;
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("strategy", "FIXED_ZONES");
        fixed.put("name", "固定分区：W1 守净区 / W2 守污染区");
        fixed.put("staffCount", 2);
        fixed.put("transitionMin", 0);
        fixed.put("dependencyWaitMin", fC0 - a1);
        fixed.put("cStartMin", fC0); fixed.put("cEndMin", fC1);
        fixed.put("cStartHHmm", hhmm(fC0)); fixed.put("cEndHHmm", hhmm(fC1));
        fixed.put("meetsDeadline", fC1 <= deadline);
        fixed.put("segments", List.of(
                seg("W1", "A", "净区供箱", "CLEAN", a0, a1),
                seg("W2", "P", "污染区交接", "DIRTY", p0, p1),
                seg("W1", "C", "净区检查", "CLEAN", fC0, fC1)));

        // 方案二：同一 W1 跨区复用 —— A→P→转换(流程+复核)→C
        int t0 = p1, t1 = t0 + transition;
        int rC0 = Math.max(p1, t1), rC1 = rC0 + cDur;
        Map<String, Object> reuse = new LinkedHashMap<>();
        reuse.put("strategy", "REUSE_ONE");
        reuse.put("name", "跨区复用 W1：A→P→流程/复核转换→C（W2 备用）");
        reuse.put("staffCount", 1);
        reuse.put("transitionMin", transition);
        reuse.put("dependencyWaitMin", 0);
        reuse.put("cStartMin", rC0); reuse.put("cEndMin", rC1);
        reuse.put("cStartHHmm", hhmm(rC0)); reuse.put("cEndHHmm", hhmm(rC1));
        reuse.put("meetsDeadline", rC1 <= deadline);
        reuse.put("segments", List.of(
                seg("W1", "A", "净区供箱", "CLEAN", a0, a1),
                seg("W1", "P", "污染区交接", "DIRTY", p0, p1),
                seg("W1", "T", "退出流程+独立复核", "TRANSITION", t0, t1),
                seg("W1", "C", "净区检查", "CLEAN", rC0, rC1)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseMin", base); out.put("baseHHmm", hhmm(base));
        out.put("deadlineMin", deadline); out.put("deadlineHHmm", hhmm(deadline));
        out.put("transitionMin", transition);
        out.put("fixedZones", fixed);
        out.put("reuseOne", reuse);
        out.put("plans", List.of(fixed, reuse));
        return out;
    }

    private Map<String, Object> seg(String staff, String code, String title, String zone, int s, int e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("staffCode", staff); m.put("taskCode", code); m.put("title", title); m.put("zoneReq", zone);
        m.put("startMin", s); m.put("endMin", e); m.put("startHHmm", hhmm(s)); m.put("endHHmm", hhmm(e));
        return m;
    }

    private Map<String, Object> taskOrNull(String code) {
        return db.maybeOne("SELECT * FROM zone_tasks WHERE code=?", code).map(Views::of).orElse(null);
    }

    // ======================================================================
    // 总览板 + 医院现场可继续办理清单
    // ======================================================================

    public Map<String, Object> board() {
        Map<String, Object> b = new LinkedHashMap<>();
        int clock = clockMin();
        Map<String, Object> setting = Views.of(db.one("SELECT * FROM zone_settings WHERE id=1"));
        b.put("setting", setting);
        b.put("nowMin", clock);
        b.put("nowHHmm", hhmm(clock));
        b.put("transitionMin", ai(setting.get("transitionMin")));

        List<Map<String, Object>> tasks = Views.list(db.list("SELECT * FROM zone_tasks ORDER BY id"));
        List<Map<String, Object>> rounds = Views.list(db.list("SELECT * FROM zone_rounds ORDER BY id DESC"));
        List<Map<String, Object>> allSteps = Views.list(db.list("SELECT * FROM zone_round_steps ORDER BY seq"));
        Map<Long, List<Map<String, Object>>> stepsByRound = new HashMap<>();
        for (Map<String, Object> st : allSteps) {
            stepsByRound.computeIfAbsent(((Number) st.get("roundId")).longValue(), k -> new ArrayList<>()).add(st);
        }
        Map<String, Map<String, Object>> activeRoundByStaff = new HashMap<>();
        for (Map<String, Object> r : rounds) {
            r.put("steps", stepsByRound.getOrDefault(((Number) r.get("id")).longValue(), List.of()));
            if (!"CLEARED".equals(r.get("status"))) {
                activeRoundByStaff.putIfAbsent((String) r.get("staffCode"), r);
            }
        }

        List<Map<String, Object>> staff = Views.list(db.list("SELECT * FROM zone_staff ORDER BY id"));
        for (Map<String, Object> s : staff) {
            String code = (String) s.get("code");
            s.put("activeRound", activeRoundByStaff.get(code));
            Map<String, Object> ar = activeRoundByStaff.get(code);
            if (ar != null) {
                Map<String, Object> ppe = new LinkedHashMap<>();
                ppe.put("kitCode", ar.get("kitCode"));
                ppe.put("reservationCode", ar.get("reservationCode"));
                ppe.put("roundStatus", ar.get("status"));
                ppe.put("lines", ar.get("kitLines"));
                s.put("currentPpe", ppe);
            }
            List<Map<String, Object>> segs = new ArrayList<>();
            for (Map<String, Object> t : tasks) {
                if (code.equals(t.get("assigneeCode"))
                        && Set.of("CONFIRMED", "IN_PROGRESS").contains(t.get("status"))) {
                    segs.add(t);
                }
            }
            s.put("segments", segs);
        }

        Map<String, Integer> held = heldQtyBySku();
        List<Map<String, Object>> skus = Views.list(db.list("SELECT * FROM zone_ppe_sku ORDER BY id"));
        for (Map<String, Object> sku : skus) {
            String sc = (String) sku.get("skuCode");
            int onHand = ai(sku.get("onHand"));
            int heldQ = held.getOrDefault(sc, 0);
            int consumed = ai(db.jdbc().queryForObject(
                    "SELECT COALESCE(SUM((l->>'qty')::int),0) FROM zone_ppe_reservation r, "
                            + "jsonb_array_elements(r.lines) l WHERE r.status='CONSUMED' AND l->>'skuCode'=?",
                    Integer.class, sc));
            sku.put("heldQty", heldQ);
            sku.put("available", onHand - heldQ);
            sku.put("consumedQty", consumed);
        }
        List<Map<String, Object>> kits = Views.list(db.list("SELECT * FROM zone_ppe_kit ORDER BY code"));
        for (Map<String, Object> k : kits) k.put("lines", kitLines((String) k.get("code")));

        List<Map<String, Object>> reservations = Views.list(db.list(
                "SELECT * FROM zone_ppe_reservation ORDER BY id DESC LIMIT 50"));
        List<Map<String, Object>> txns = Views.list(db.list(
                "SELECT * FROM zone_ppe_txn ORDER BY id DESC LIMIT 50"));
        List<Map<String, Object>> events = Views.list(db.list(
                "SELECT * FROM zone_events ORDER BY id DESC LIMIT 80"));

        b.put("staff", staff);
        b.put("tasks", tasks);
        b.put("rounds", rounds);
        b.put("skus", skus);
        b.put("kits", kits);
        b.put("reservations", reservations);
        b.put("txns", txns);
        b.put("events", events);
        b.put("dispatchPlans", dispatchPlans());
        b.put("nextActions", buildActions(tasks, staff, rounds));
        return b;
    }

    private Map<String, Object> action(String code, String label, String tone,
                                       String path, Map<String, Object> body, boolean enabled, String reason) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("code", code);
        a.put("label", label);
        a.put("tone", tone);
        a.put("method", "POST");
        a.put("path", path);
        a.put("body", body == null ? Map.of() : body);
        a.put("enabled", enabled);
        a.put("reason", reason == null ? "" : reason);
        return a;
    }

    /** 医院现场“可继续办理清单”：把人员状态/任务责任/库存/复核串成可执行下一步。 */
    private List<Map<String, Object>> buildActions(List<Map<String, Object>> tasks,
                                                  List<Map<String, Object>> staff,
                                                  List<Map<String, Object>> rounds) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Map<String, Object>> staffByCode = new HashMap<>();
        for (Map<String, Object> s : staff) staffByCode.put((String) s.get("code"), s);

        for (Map<String, Object> t : tasks) {
            String tc = (String) t.get("code");
            String st = (String) t.get("status");
            String zone = (String) t.get("zoneReq");
            if ("OPEN".equals(st)) {
                // 缺件优先提示补货
                String kit = (String) t.get("kitCode");
                List<String> missingNames = new ArrayList<>();
                String firstMissingSku = null;
                if (kit != null) {
                    Map<String, Integer> held = heldQtyBySku();
                    for (Map<String, Object> line : kitLines(kit)) {
                        String sc = (String) line.get("skuCode");
                        int avail = ai(mustSku(sc).get("onHand")) - held.getOrDefault(sc, 0);
                        if (avail < ai(line.get("qty"))) {
                            missingNames.add((String) line.get("name"));
                            if (firstMissingSku == null) firstMissingSku = sc;
                        }
                    }
                }
                if (!missingNames.isEmpty()) {
                    String skuPath = firstMissingSku == null ? "PPE-FACE" : firstMissingSku;
                    actions.add(action("PPE_SHORTAGE_" + tc,
                            "任务 " + tc + " 防护用品缺件：" + String.join("、", missingNames) + "，需先补货",
                            "danger", "/zone/ppe/stock/" + skuPath + "/restock",
                            Map.of("qty", 5), false,
                            "缺件时不能确认派发；此按钮先补 " + skuPath + "，其余缺件请在库存表补"));
                }
                for (Map<String, Object> s : staff) {
                    String sc = (String) s.get("code");
                    if ("RV".equals(sc)) continue;
                    List<String> have = strList(s.get("quals"));
                    if (!have.containsAll(strList(t.get("requiredQuals")))) continue;
                    boolean cleanOk = Boolean.TRUE.equals(s.get("cleanEligible"));
                    boolean enabled = "DIRTY".equals(zone) || cleanOk;
                    actions.add(action("ASSIGN_" + tc + "_" + sc,
                            "派发 " + tc + "（" + t.get("title") + "）给 " + sc
                                    + ("CLEAN".equals(zone) && !cleanOk ? "（" + s.get("contamState") + "，净区资格受限）" : ""),
                            enabled ? "primary" : "info",
                            "/zone/tasks/" + tc + "/assign", Map.of("staffCode", sc),
                            enabled, enabled ? "" : "该人员尚未完成本轮污染转换/复核，不能接触净区物品"));
                }
            } else if ("CONFIRMED".equals(st)) {
                String sc = (String) t.get("assigneeCode");
                Map<String, Object> s = staffByCode.get(sc);
                boolean cleanOk = s == null || Boolean.TRUE.equals(s.get("cleanEligible"));
                if ("CLEAN".equals(zone)) {
                    actions.add(action("START_" + tc, "开始净区任务 " + tc + "（责任人 " + sc + "）",
                            "success", "/zone/tasks/" + tc + "/start", Map.of(), cleanOk,
                            cleanOk ? "" : "责任人当前不具备净区资格，不能开始；转换完成也不会自动改派/抢回"));
                } else {
                    actions.add(action("ENTER_" + tc, "责任人 " + sc + " 进入污染区执行 " + tc + "（实耗防护用品）",
                            "warning", "/zone/tasks/" + tc + "/start", Map.of(), true, ""));
                }
                actions.add(action("CANCEL_" + tc, "主管取消任务 " + tc + "（保留进区与实耗记录）",
                        "danger", "/zone/tasks/" + tc + "/cancel",
                        Map.of("reason", "现场调整"), true, ""));
            } else if ("IN_PROGRESS".equals(st)) {
                actions.add(action("COMPLETE_" + tc, "完成任务 " + tc + "（责任人 " + t.get("assigneeCode") + "）",
                        "success", "/zone/tasks/" + tc + "/complete", Map.of(), true, ""));
                actions.add(action("CANCEL_" + tc, "主管取消进行中任务 " + tc + "（进区/实耗不抹除）",
                        "danger", "/zone/tasks/" + tc + "/cancel",
                        Map.of("reason", "主管取消"), true, ""));
            }
        }

        for (Map<String, Object> r : rounds) {
            String rc = (String) r.get("code");
            String sc = (String) r.get("staffCode");
            String rst = (String) r.get("status");
            if ("CLEARED".equals(rst)) continue;
            if (r.get("exitMin") == null) {
                actions.add(action("EXIT_" + rc, sc + " 退出污染区并登记已知回执 " + rc,
                        "warning", "/zone/rounds/" + rc + "/exit", Map.of("receiptKnown", true), true, ""));
                actions.add(action("EXIT_PENDING_" + rc, sc + " 退出但关键回执未知（模拟迟到）",
                        "info", "/zone/rounds/" + rc + "/exit", Map.of("receiptKnown", false), true, ""));
                continue;
            }
            if (!"PASSED".equals(r.get("cardStatus"))) {
                for (Map<String, Object> st : listVal(r.get("steps")).stream()
                        .map(o -> (Map<String, Object>) o).toList()) {
                    String stepCode = (String) st.get("stepCode");
                    String stepStatus = (String) st.get("status");
                    boolean inspection = Boolean.TRUE.equals(st.get("inspection"));
                    if (inspection) {
                        if (!Boolean.TRUE.equals(st.get("passed"))) {
                            actions.add(action("INSP_PASS_" + rc, "检查步骤判定合格：" + st.get("label"),
                                    "success", "/zone/rounds/" + rc + "/step",
                                    Map.of("stepCode", stepCode, "passed", true), true, ""));
                            actions.add(action("INSP_FAIL_" + rc, "检查步骤判定不合格（验证检查失败）",
                                    "danger", "/zone/rounds/" + rc + "/step",
                                    Map.of("stepCode", stepCode, "passed", false), true, ""));
                        }
                    } else if (!"DONE".equals(stepStatus)) {
                        actions.add(action("STEP_" + rc + "_" + stepCode,
                                ("INTERRUPTED".equals(stepStatus) ? "恢复并完成：" : "办理：") + st.get("label"),
                                "primary", "/zone/rounds/" + rc + "/step",
                                Map.of("stepCode", stepCode), true, ""));
                        if (!"INTERRUPTED".equals(stepStatus)) {
                            actions.add(action("INTERRUPT_" + rc + "_" + stepCode,
                                    "将该步标记为流程中断（验证中断限制）", "info",
                                    "/zone/rounds/" + rc + "/step",
                                    Map.of("stepCode", stepCode, "interrupted", true), true, ""));
                        }
                    }
                }
                boolean ready = "PASS".equals(r.get("checkStatus"));
                actions.add(action("CARD_DONE_" + rc, "提交并办结 " + sc + " 本轮流程卡",
                        "success", "/zone/rounds/" + rc + "/complete-card", Map.of(), ready,
                        ready ? "" : "需先完成全部步骤且检查合格"));
            } else if (!"PASS".equals(r.get("reviewStatus"))) {
                actions.add(action("REVIEW_PASS_" + rc, "由独立复核员 RV 通过 " + sc + " 本轮复核",
                        "success", "/zone/rounds/" + rc + "/review",
                        Map.of("decision", "PASS", "reviewerCode", "RV"), true, ""));
                actions.add(action("REVIEW_FAIL_" + rc, "独立复核不通过（验证复核失败限制）",
                        "danger", "/zone/rounds/" + rc + "/review",
                        Map.of("decision", "FAIL", "reviewerCode", "RV", "note", "模拟复核发现问题"), true, ""));
            } else if (!"KNOWN".equals(r.get("exitReceiptStatus"))) {
                actions.add(action("RECEIPT_" + rc, "补登迟到的退出关键回执",
                        "warning", "/zone/rounds/" + rc + "/exit-receipt", Map.of(), true, ""));
            }
        }
        return actions;
    }
}
