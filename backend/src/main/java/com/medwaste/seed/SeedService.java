package com.medwaste.seed;

import com.medwaste.common.Db;
import com.medwaste.zone.ZoneSeed;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 全部机构、人员、资质、类别均为随题虚构。
 * reset() 会清空业务数据并重建主数据，保证验收脚本可重复运行。
 */
@Service
@Order(10)
public class SeedService implements ApplicationRunner {
    private final Db db;
    private final ZoneSeed zoneSeed;

    public SeedService(Db db, ZoneSeed zoneSeed) {
        this.db = db;
        this.zoneSeed = zoneSeed;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer cnt = db.jdbc().queryForObject("SELECT count(*) FROM orgs", Integer.class);
        if (cnt == null || cnt == 0) reset();
    }

    public synchronized void reset() {
        db.update("""
                TRUNCATE TABLE
                  custody_events, review_tasks, disposal_receipts, disposal_batches,
                  mandates, plant_receipts, discrepancies, weight_records,
                  manifest_items, manifests, pallet_loads, pallets, box_rounds,
                  boxes, bags, box_returns, vehicles, qualifications, sim_devices,
                  categories, app_users, orgs
                RESTART IDENTITY CASCADE
                """);
        seed();
        // 跨区特性基线（W1/W2/RV、A/P/C、流程卡、防护用品）随全局重置一并重建
        zoneSeed.resetZone();
    }

    private long org(String code, String name, String type) {
        return db.insert("INSERT INTO orgs(code,name,org_type) VALUES (?,?,?)", code, name, type);
    }

    private void user(String username, String pwd, String display, String role, Long orgId, String title) {
        db.insert("""
                INSERT INTO app_users(username,password,display_name,role,org_id,title)
                VALUES (?,?,?,?,?,?)
                """, username, pwd, display, role, orgId, title);
    }

    private void qual(long orgId, String certNo, String name, String scope,
                      LocalDate from, LocalDate to, String status) {
        db.insert("""
                INSERT INTO qualifications(org_id,cert_no,qual_name,scope_note,valid_from,valid_to,status)
                VALUES (?,?,?,?,?,?,?)
                """, orgId, certNo, name, scope, from, to, status);
    }

    private void seed() {
        // ---------- 虚构机构 ----------
        long h1 = org("YY-RH", "虚构市仁和医院", "HOSPITAL");
        long h2 = org("YY-KN", "虚构市康宁医院", "HOSPITAL");
        long t1 = org("YS-AJ", "虚构市安捷医疗废物收运有限公司", "TRANSPORTER");
        long t2 = org("YS-SD", "虚构市顺达收运队（资质已暂停）", "TRANSPORTER");
        long p1 = org("CL-LY", "虚构市绿源医疗废物处置厂", "DISPOSAL");

        // ---------- 测试账号（密码统一 123456，admin 除外） ----------
        user("admin", "admin123", "系统管理员", "ADMIN", null, "演示环境管理");
        user("hospital01", "123456", "王敏", "HOSPITAL", h1, "医院交接员");
        user("hospital02", "123456", "周岚", "HOSPITAL", h1, "医院交接员");
        user("kangning01", "123456", "吴医生", "HOSPITAL", h2, "康宁医院交接员");
        user("transport01", "123456", "张强", "TRANSPORTER", t1, "收运员");
        user("transport02", "123456", "李娜", "TRANSPORTER", t1, "收运员（第二接收人）");
        user("shunda01", "123456", "钱顺", "TRANSPORTER", t2, "收运员");
        user("plant01", "123456", "陈实", "PLANT", p1, "处置厂核实员");
        user("plant02", "123456", "林工", "PLANT", p1, "处置车间操作员");

        // ---------- feature-4 跨区派发：W1/W2 与独立复核员（密码均 123456） ----------
        user("w1", "123456", "W1·卫一", "HOSPITAL", h1, "跨区收运/供箱员 W1");
        user("w2", "123456", "W2·卫二", "HOSPITAL", h1, "跨区收运/污染交接员 W2");
        user("rv01", "123456", "周正", "HOSPITAL", h1, "独立复核员/值班主管");

        // ---------- 虚构机构资质 ----------
        qual(h1, "YG-XK-2026-0001", "医疗机构执业许可证（虚构）", "全部虚构医废类别",
                LocalDate.of(2026, 1, 1), LocalDate.of(2028, 12, 31), "VALID");
        qual(t1, "YS-XK-2026-A07", "道路运输经营许可证（虚构）", "医疗废物专用运输",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "VALID");
        qual(t1, "YS-YF-2026-021", "医疗废物收运备案凭证（虚构）", "虚构市全域",
                LocalDate.of(2026, 3, 1), LocalDate.of(2027, 2, 28), "VALID");
        qual(t2, "YS-XK-2024-B19", "道路运输经营许可证（虚构·暂停）", "医疗废物运输",
                LocalDate.of(2024, 1, 1), LocalDate.of(2028, 12, 31), "SUSPENDED");
        qual(p1, "HW-PX-2026-018", "危险废物经营许可证（虚构）", "HW01 医疗废物 / 高温蒸煮+焚烧",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 12, 31), "VALID");
        qual(p1, "CL-PW-2026-006", "排污许可证（虚构）", "医疗废物处置线",
                LocalDate.of(2026, 1, 1), LocalDate.of(2028, 6, 30), "VALID");

        // ---------- 虚构废物类别与规则 ----------
        String[][] cats = {
                {"FW-INF", "虚构感染性废物", "双层黄色袋、鹅颈封口；适配一次性袋+硬质周转箱"},
                {"FW-INJ", "虚构损伤性废物", "须先入利器盒再套袋；封口码与箱清单绑定"},
                {"FW-PAT", "虚构病理性废物", "低温暂存不超过 24 小时（虚构规则）"},
                {"FW-MED", "虚构药物性废物", "单独袋装、不得与感染性废物混装（虚构规则）"}
        };
        for (String[] c : cats) {
            db.insert("INSERT INTO categories(code,name,fictional_rule) VALUES (?,?,?)",
                    c[0], c[1], c[2]);
        }

        // ---------- 虚构车辆 ----------
        db.insert("INSERT INTO vehicles(plate_no,transporter_org_id,driver_name) VALUES (?,?,?)",
                "X-A1201", t1, "张强");
        db.insert("INSERT INTO vehicles(plate_no,transporter_org_id,driver_name) VALUES (?,?,?)",
                "X-A1202", t1, "李娜");
        db.insert("INSERT INTO vehicles(plate_no,transporter_org_id,driver_name,status) VALUES (?,?,?,?)",
                "X-B0999", t2, "钱顺", "SUSPENDED");

        // ---------- 模拟器：称重仪 / 处置设备 ----------
        scale("SC-H1", "仁和医院交接区一号秤（模拟）", "医院交接区", "10.000");
        scale("SC-T1", "安捷收运便携复核秤（模拟）", "交接现场/车厢", "10.000");
        scale("SC-P1", "绿源厂入口地磅（模拟）", "处置厂入口", "29.000");
        scale("SC-P2", "绿源厂差异补称台秤（模拟）", "厂内待核区", "10.000");
        db.insert("INSERT INTO sim_devices(code,name,device_type,location,extra) VALUES (?,?,?,?,?::jsonb)",
                "DISP-1", "一号高温蒸煮线（模拟）", "DISPOSAL", "处置车间 A",
                "{\"method\":\"高温蒸煮（虚构工艺）\"}");
        db.insert("INSERT INTO sim_devices(code,name,device_type,location,extra) VALUES (?,?,?,?,?::jsonb)",
                "DISP-2", "二号焚烧线（模拟）", "DISPOSAL", "处置车间 B",
                "{\"method\":\"焚烧（虚构工艺）\"}");

        // ---------- 示例周转箱 C1..C6（清洁可用，医院保管） ----------
        for (String code : List.of("C1", "C2", "C3", "C4", "C5", "C6")) {
            db.insert("""
                    INSERT INTO boxes(code,status,current_round_no,custody_party,custody_org_id,custody_user_name)
                    VALUES (?,'AVAILABLE_CLEAN',0,'HOSPITAL',?,'王敏')
                    """, code, h1);
        }
        // ---------- 托盘 P1/P2（皮重 4 / 5 kg） ----------
        db.insert("INSERT INTO pallets(code,tare_kg,custody_party,custody_org_id,custody_user_name) VALUES (?,4,'HOSPITAL',?,'王敏')",
                "P1", h1);
        db.insert("INSERT INTO pallets(code,tare_kg,custody_party,custody_org_id,custody_user_name) VALUES (?,5,'HOSPITAL',?,'王敏')",
                "P2", h1);

        // ---------- 若干未使用一次性袋/封口码，方便界面直接操作 ----------
        String[] seals = {"FD-SEAL-0001", "FD-SEAL-0002", "FD-SEAL-0003", "FD-SEAL-0004",
                "FD-SEAL-0005", "FD-SEAL-0006", "FD-SEAL-0007", "FD-SEAL-0008"};
        int i = 0;
        for (String s : seals) {
            String cat = cats[i % cats.length][0];
            db.insert("INSERT INTO bags(seal_code,category_code,hospital_org_id,status) VALUES (?,?,?,'UNUSED')",
                    s, cat, h1);
            i++;
        }
    }

    private void scale(String code, String name, String location, String reading) {
        db.insert("""
                INSERT INTO sim_devices(code,name,device_type,location,reading_kg)
                VALUES (?,?,'SCALE',?,?)
                """, code, name, location, new java.math.BigDecimal(reading));
    }

    /** 供 Controller 返回阈值配置。 */
    public Map<String, Object> ruleInfo(double tolerance) {
        return Map.of(
                "weightToleranceKg", tolerance,
                "rule", "同一称量口径下，认可重量差绝对值 > 0.5 kg 即转差异核实（虚构约定）",
                "noRatioAllocation", "合托合称只证明托内总量；出现差异必须补称，禁止按袋数比例摊造各箱净重"
        );
    }
}
