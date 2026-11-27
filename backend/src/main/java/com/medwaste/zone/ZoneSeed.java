package com.medwaste.zone;

import com.medwaste.common.Db;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 跨区派发特性的虚构基线：W1/W2（均有 A/P/C 岗位资格）、独立复核员 RV、
 * 题例任务 A(净区供箱10') / P(污染区交接15') / C(净区检查10', 11:00 前)、
 * 10 分钟转换流程卡、污染区防护套件与库存。全部随题虚构。
 */
@Component
@Order(20)
public class ZoneSeed implements ApplicationRunner {
    private final Db db;

    public ZoneSeed(Db db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer cnt = db.jdbc().queryForObject("SELECT count(*) FROM zone_staff", Integer.class);
        if (cnt == null || cnt == 0) resetZone();
    }

    /** 仅重置跨区特性自己的表，不动登录会话与其他业务；验收脚本每个场景前可重复调用。 */
    public synchronized void resetZone() {
        db.update("""
                TRUNCATE TABLE
                  zone_events, zone_round_steps, zone_rounds, zone_ppe_txn,
                  zone_ppe_reservation, zone_ppe_kit_line, zone_ppe_kit,
                  zone_ppe_sku, zone_tasks, zone_card_template, zone_staff, zone_settings
                RESTART IDENTITY CASCADE
                """);
        ensureLoginUsers();
        seed();
    }

    /** 保证 W1/W2/RV 可登录（旧库升级或只调 /api/zone/reset 时也成立）；密码与全站演示一致。 */
    private void ensureLoginUsers() {
        String org = "(SELECT id FROM orgs WHERE code='YY-RH' LIMIT 1)";
        loginUser("w1", "W1·卫一", "跨区收运/供箱员 W1", org);
        loginUser("w2", "W2·卫二", "跨区收运/污染交接员 W2", org);
        loginUser("rv01", "周正", "独立复核员/值班主管", org);
    }

    private void loginUser(String username, String display, String title, String orgSubSelect) {
        db.update("""
                INSERT INTO app_users(username,password,display_name,role,org_id,title)
                VALUES (?,'123456',?,'HOSPITAL',
                """ + orgSubSelect + """
                ,?)
                ON CONFLICT (username) DO UPDATE
                  SET password=EXCLUDED.password, display_name=EXCLUDED.display_name,
                      role='HOSPITAL', title=EXCLUDED.title
                """, username, display, title);
    }

    private void seed() {
        // 模拟日期 2026-11-27，时钟 10:00 = 600 分钟；转换（退出流程+独立复核）共 10 分钟
        db.update("INSERT INTO zone_settings(id, base_date, clock_min, transition_min) " +
                "VALUES (1, DATE '2026-11-27', 600, 10)");

        // ---------- 人员：岗位资格 / 实际区域 / 污染状态 / 净区资格 分列 ----------
        staff("W1", "卫一", "w1", "CLEAN", true);
        staff("W2", "卫二", "w2", "DIRTY", true);
        db.insert("""
                INSERT INTO zone_staff(code,name,username,post_zone,quals,can_review,actual_zone,clean_eligible)
                VALUES ('RV','周正（独立复核/主管）','rv01','FLOAT','["INDEP_REVIEW"]'::jsonb,TRUE,'CLEAN',TRUE)
                """);

        // ---------- 题例任务链 A -> P -> C ----------
        task("A", "净区供箱", "CLEAN", 10, 600, null, "[]", "[\"NET_SUPPLY\"]", null);
        task("P", "污染区交接", "DIRTY", 15, 600, null, "[\"A\"]", "[\"DIRTY_HANDOVER\"]", "KIT-DIRTY-STD");
        task("C", "净区检查", "CLEAN", 10, 600, 660, "[\"P\"]", "[\"NET_INSPECT\"]", null);

        // ---------- 虚构退出转换流程卡（最后一步为可判失败的检查） ----------
        step("DOFF_OUTER", 1, "按流程卡脱除外层防护服与外层手套（虚构）", false);
        step("HAND_HYGIENE", 2, "手卫生并更换外层手套（虚构流程）", false);
        step("PASSAGE", 3, "通过虚构消毒转换通道", false);
        step("DOFF_INNER", 4, "脱除内层防护并更换口罩（虚构）", false);
        step("INSPECTION", 5, "防护检查员目检/采样检查（可判合格或不合格）", true);

        // ---------- 防护用品 SKU 与库存 ----------
        sku("PPE-GOWN", "一次性防护服", "套", 10, 2);
        sku("PPE-GLOVE", "加长防护手套", "副", 20, 4);
        sku("PPE-MASK", "防护口罩", "只", 20, 4);
        sku("PPE-FACE", "护目面屏", "个", 8, 2);
        sku("PPE-BOOT", "高筒靴套", "双", 15, 2);

        db.update("INSERT INTO zone_ppe_kit(code,name) VALUES ('KIT-DIRTY-STD','污染区进入标准防护套件（随题虚构）')");
        kitLine("KIT-DIRTY-STD", "PPE-GOWN", 1);
        kitLine("KIT-DIRTY-STD", "PPE-GLOVE", 2);
        kitLine("KIT-DIRTY-STD", "PPE-MASK", 1);
        kitLine("KIT-DIRTY-STD", "PPE-FACE", 1);
        kitLine("KIT-DIRTY-STD", "PPE-BOOT", 1);
    }

    private void staff(String code, String name, String username, String postZone, boolean allQuals) {
        db.insert("""
                INSERT INTO zone_staff(code,name,username,post_zone,quals,can_review,actual_zone,clean_eligible)
                VALUES (?,?,?,?,'["NET_SUPPLY","DIRTY_HANDOVER","NET_INSPECT"]'::jsonb,FALSE,'CLEAN',TRUE)
                """, code, name, username, postZone);
    }

    private void task(String code, String title, String zoneReq, int dur, Integer release, Integer deadline,
                      String dependsOn, String quals, String kitCode) {
        db.insert("""
                INSERT INTO zone_tasks(code,title,zone_req,duration_min,release_min,deadline_min,
                                       depends_on,required_quals,kit_code,status)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,'OPEN')
                """, code, title, zoneReq, dur, release, deadline, dependsOn, quals, kitCode);
    }

    private void step(String code, int seq, String label, boolean inspection) {
        db.update("INSERT INTO zone_card_template(step_code,seq,label,inspection) VALUES (?,?,?,?)",
                code, seq, label, inspection);
    }

    private void sku(String code, String name, String unit, int onHand, int safety) {
        db.insert("INSERT INTO zone_ppe_sku(sku_code,name,unit,on_hand,safety_stock) VALUES (?,?,?,?,?)",
                code, name, unit, onHand, safety);
    }

    private void kitLine(String kit, String sku, int qty) {
        db.insert("INSERT INTO zone_ppe_kit_line(kit_code,sku_code,qty) VALUES (?,?,?)", kit, sku, qty);
    }
}
