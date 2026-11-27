-- 医疗废物收运交接应用 schema（PostgreSQL 18，全部规则均为随题虚构）
-- 使用 IF NOT EXISTS，启动时自动建表；业务数据由 SeedService 幂等播种/重置。

CREATE TABLE IF NOT EXISTS orgs (
  id          BIGSERIAL PRIMARY KEY,
  code        VARCHAR(40) UNIQUE NOT NULL,
  name        VARCHAR(120) NOT NULL,
  org_type    VARCHAR(20) NOT NULL,           -- HOSPITAL / TRANSPORTER / DISPOSAL
  fictional   BOOLEAN NOT NULL DEFAULT TRUE,
  info        JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_users (
  id          BIGSERIAL PRIMARY KEY,
  username    VARCHAR(40) UNIQUE NOT NULL,
  password    VARCHAR(80) NOT NULL,
  display_name VARCHAR(60) NOT NULL,
  role        VARCHAR(20) NOT NULL,            -- HOSPITAL / TRANSPORTER / PLANT / ADMIN
  org_id      BIGINT REFERENCES orgs(id),
  title       VARCHAR(60),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS qualifications (
  id            BIGSERIAL PRIMARY KEY,
  org_id        BIGINT NOT NULL REFERENCES orgs(id),
  cert_no       VARCHAR(60) NOT NULL,
  qual_name     VARCHAR(120) NOT NULL,
  scope_note    VARCHAR(200),
  valid_from    DATE NOT NULL,
  valid_to      DATE NOT NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'VALID', -- VALID / SUSPENDED / REVOKED / EXPIRED
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS categories (
  id            BIGSERIAL PRIMARY KEY,
  code          VARCHAR(40) UNIQUE NOT NULL,
  name          VARCHAR(80) NOT NULL,
  fictional_rule VARCHAR(300),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vehicles (
  id            BIGSERIAL PRIMARY KEY,
  plate_no      VARCHAR(40) UNIQUE NOT NULL,
  transporter_org_id BIGINT REFERENCES orgs(id),
  driver_name   VARCHAR(60),
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 模拟器设备：称重仪 / 处置设备
CREATE TABLE IF NOT EXISTS sim_devices (
  id            BIGSERIAL PRIMARY KEY,
  code          VARCHAR(40) UNIQUE NOT NULL,
  name          VARCHAR(120) NOT NULL,
  device_type   VARCHAR(20) NOT NULL,          -- SCALE / DISPOSAL
  location      VARCHAR(120),
  reading_kg    NUMERIC(10,3),                 -- 称重仪当前模拟读数
  extra         JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 一次性袋（唯一封口码）
CREATE TABLE IF NOT EXISTS bags (
  id                   BIGSERIAL PRIMARY KEY,
  seal_code            VARCHAR(60) UNIQUE NOT NULL,
  category_code        VARCHAR(40),
  hospital_org_id      BIGINT REFERENCES orgs(id),
  status               VARCHAR(30) NOT NULL DEFAULT 'UNUSED', -- UNUSED / ACTIVE_IN_LIST / ARCHIVED
  current_box_code     VARCHAR(40),
  current_round_no     INT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  sealed_at            TIMESTAMPTZ
);

-- 可重复周转箱
CREATE TABLE IF NOT EXISTS boxes (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  status               VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE_CLEAN',
  current_round_no     INT NOT NULL DEFAULT 0,
  active_manifest_id   BIGINT,
  cleaning_tare_kg     NUMERIC(10,3),
  custody_party        VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL', -- HOSPITAL / TRANSPORTER / PLANT / CLEANING
  custody_org_id       BIGINT REFERENCES orgs(id),
  custody_user_name    VARCHAR(60),
  custody_since        TIMESTAMPTZ NOT NULL DEFAULT now(),
  version              BIGINT NOT NULL DEFAULT 0,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 封箱轮次：每轮记录本轮袋码清单与实际空箱重
CREATE TABLE IF NOT EXISTS box_rounds (
  id                   BIGSERIAL PRIMARY KEY,
  box_id               BIGINT NOT NULL REFERENCES boxes(id),
  box_code             VARCHAR(40) NOT NULL,
  round_no             INT NOT NULL,
  qr_code              VARCHAR(80) NOT NULL,    -- C1#R3 形式，复用编号必须带本轮封装号
  actual_tare_kg       NUMERIC(10,3) NOT NULL,
  tare_device_code     VARCHAR(40),
  status               VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT / SEALED / CANCELLED
  bags                 JSONB NOT NULL DEFAULT '[]'::jsonb,
  seal_gross_kg        NUMERIC(10,3),
  seal_device_code     VARCHAR(40),
  recognized_net_kg    NUMERIC(10,3),
  seal_weight_id       BIGINT,
  sealed_by_name       VARCHAR(60),
  sealed_at            TIMESTAMPTZ,
  created_by_name      VARCHAR(60),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(box_id, round_no)
);

CREATE TABLE IF NOT EXISTS pallets (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  tare_kg              NUMERIC(10,3) NOT NULL,  -- 托盘皮重
  status               VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  custody_party        VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL',
  custody_org_id       BIGINT REFERENCES orgs(id),
  custody_user_name    VARCHAR(60),
  custody_since        TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 封托装载（声明清单 + 实际扫描清单）
CREATE TABLE IF NOT EXISTS pallet_loads (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  pallet_id            BIGINT NOT NULL REFERENCES pallets(id),
  pallet_code          VARCHAR(40) NOT NULL,
  tare_pallet_kg       NUMERIC(10,3) NOT NULL,
  manifest_id          BIGINT,
  status               VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT / COMPLETE / BLOCKED
  declared_round_ids   JSONB NOT NULL DEFAULT '[]'::jsonb,
  declared_qrs         JSONB NOT NULL DEFAULT '[]'::jsonb,
  scanned_round_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,
  scanned_qrs          JSONB NOT NULL DEFAULT '[]'::jsonb,
  recognized_total_net_kg NUMERIC(10,3),
  created_by_name      VARCHAR(60),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 交接车次/批次交接单
CREATE TABLE IF NOT EXISTS manifests (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  hospital_org_id      BIGINT REFERENCES orgs(id),
  transporter_org_id   BIGINT REFERENCES orgs(id),
  vehicle_id           BIGINT REFERENCES vehicles(id),
  vehicle_plate        VARCHAR(40),
  transporter_user_id  BIGINT,
  transporter_name     VARCHAR(60),
  hospital_confirmer_id BIGINT,
  hospital_confirmer_name VARCHAR(60),
  status               VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  -- DRAFT / SUBMITTED / HANDED_OVER / AT_PLANT / IN_PLANT_PENDING_CHECK / SIGNED / CANCELLED
  weighing_mode        VARCHAR(20),             -- BOX_DIRECT / PALLET_COMBINED / MIXED
  total_recognized_net_kg NUMERIC(12,3),
  weight_basis         VARCHAR(30),
  qual_ok              BOOLEAN,
  seal_ok              BOOLEAN,
  check_note           VARCHAR(400),
  plant_receipt_id     BIGINT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  submitted_at         TIMESTAMPTZ,
  handed_at            TIMESTAMPTZ,
  arrived_at           TIMESTAMPTZ,
  intake_at            TIMESTAMPTZ,
  signed_at            TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS manifest_items (
  id                   BIGSERIAL PRIMARY KEY,
  manifest_id          BIGINT NOT NULL REFERENCES manifests(id) ON DELETE CASCADE,
  box_round_id         BIGINT NOT NULL REFERENCES box_rounds(id),
  box_code             VARCHAR(40) NOT NULL,
  round_no             INT NOT NULL,
  UNIQUE(manifest_id, box_round_id)
);

-- 重量证据：同一 logical_id 可有多版本（旧称量修订留痕，不覆盖历史）
CREATE TABLE IF NOT EXISTS weight_records (
  id                   BIGSERIAL PRIMARY KEY,
  logical_id           VARCHAR(50) NOT NULL,
  version              INT NOT NULL DEFAULT 1,
  status               VARCHAR(20) NOT NULL,    -- PROPOSED / EFFECTIVE / DISPUTED / SUPERSEDED / HELD
  scope                VARCHAR(10) NOT NULL,    -- BOX / PALLET / MANIFEST
  stage                VARCHAR(20) NOT NULL,    -- SEAL / HANDOVER_JOINT / PLANT_CHECK / SUPPLEMENT
  mode                 VARCHAR(20) NOT NULL,    -- BOX_DIRECT / PALLET_COMBINED / AGGREGATE
  box_round_id         BIGINT REFERENCES box_rounds(id),
  box_code             VARCHAR(40),
  pallet_load_id       BIGINT REFERENCES pallet_loads(id),
  manifest_id          BIGINT REFERENCES manifests(id),
  subject_label        VARCHAR(80),
  gross_kg             NUMERIC(10,3),
  tare_box_kg          NUMERIC(10,3),
  tare_pallet_kg       NUMERIC(10,3),
  net_kg               NUMERIC(10,3),
  expected_net_kg      NUMERIC(10,3),
  diff_kg              NUMERIC(10,3),
  bag_count            INT,
  device_code          VARCHAR(40),
  revision_reason      VARCHAR(300),
  recorded_by_name     VARCHAR(60),
  recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  confirmed_by_name    VARCHAR(60),
  confirmed_at         TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_weight_logical ON weight_records(logical_id);
CREATE INDEX IF NOT EXISTS idx_weight_round ON weight_records(box_round_id);
CREATE INDEX IF NOT EXISTS idx_weight_manifest ON weight_records(manifest_id);

-- 差异核实案件
CREATE TABLE IF NOT EXISTS discrepancies (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  type                 VARCHAR(30) NOT NULL,
  -- WEIGHT_MISMATCH / MISSING_BOX / DUPLICATE_BAG / SEAL_BROKEN / QUALIFICATION_INVALID
  status               VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- OPEN / RESOLVED
  subject_type         VARCHAR(20) NOT NULL,    -- MANIFEST / PALLET_LOAD / BOX_ROUND / BAG
  subject_code         VARCHAR(60) NOT NULL,
  manifest_id          BIGINT REFERENCES manifests(id),
  box_round_id         BIGINT,
  pallet_load_id       BIGINT,
  expected_value       NUMERIC(12,3),
  observed_value       NUMERIC(12,3),
  diff_value           NUMERIC(12,3),
  threshold_kg         NUMERIC(10,3),
  title                VARCHAR(200) NOT NULL,
  description          VARCHAR(600),
  resolution_type      VARCHAR(40),
  resolution_note      VARCHAR(600),
  resolved_by_name     VARCHAR(60),
  resolved_at          TIMESTAMPTZ,
  created_by_name      VARCHAR(60),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_disc_manifest ON discrepancies(manifest_id);
CREATE INDEX IF NOT EXISTS idx_disc_status ON discrepancies(status);

-- 保管责任事件（只追加，真实交接事实不可改）
CREATE TABLE IF NOT EXISTS custody_events (
  id                   BIGSERIAL PRIMARY KEY,
  at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  target_type          VARCHAR(20) NOT NULL,    -- BOX / PALLET / MANIFEST / RECEIPT
  target_code          VARCHAR(60) NOT NULL,
  action               VARCHAR(40) NOT NULL,
  from_party           VARCHAR(20),
  to_party             VARCHAR(20),
  org_id               BIGINT,
  actor_name           VARCHAR(60),
  detail               VARCHAR(600)
);
CREATE INDEX IF NOT EXISTS idx_custody_target ON custody_events(target_code);

CREATE TABLE IF NOT EXISTS plant_receipts (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  manifest_id          BIGINT NOT NULL REFERENCES manifests(id),
  manifest_code        VARCHAR(40) NOT NULL,
  qual_ok              BOOLEAN NOT NULL,
  seal_ok              BOOLEAN NOT NULL,
  weight_total_kg      NUMERIC(12,3),
  note                 VARCHAR(400),
  signed_by_name      VARCHAR(60),
  signed_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 委托单：只有对应批次的有效处置完成回执才能结束
CREATE TABLE IF NOT EXISTS mandates (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  manifest_id          BIGINT NOT NULL REFERENCES manifests(id),
  manifest_code        VARCHAR(40) NOT NULL,
  hospital_org_id      BIGINT,
  status               VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / COMPLETED / REVIEW_REQUIRED
  valid_receipt_id     BIGINT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS disposal_batches (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  status               VARCHAR(20) NOT NULL DEFAULT 'CREATED', -- CREATED / DISPOSED
  manifest_ids         JSONB NOT NULL DEFAULT '[]'::jsonb,
  manifest_codes       JSONB NOT NULL DEFAULT '[]'::jsonb,
  device_code          VARCHAR(40),
  disposal_method      VARCHAR(80),
  performed_by_name    VARCHAR(60),
  performed_at         TIMESTAMPTZ,
  created_by_name      VARCHAR(60),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 处置完成回执：撤回/丢失不删行，保留全部历史
CREATE TABLE IF NOT EXISTS disposal_receipts (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  batch_id             BIGINT NOT NULL REFERENCES disposal_batches(id),
  batch_code           VARCHAR(40) NOT NULL,
  status               VARCHAR(20) NOT NULL DEFAULT 'VALID', -- VALID / WITHDRAWN / LOST
  seq_in_batch         INT NOT NULL DEFAULT 1,
  disposal_method      VARCHAR(80),
  issued_by_name       VARCHAR(60),
  issued_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  state_changed_at     TIMESTAMPTZ,
  reason               VARCHAR(400),
  replaces_receipt_id  BIGINT
);

-- 复核任务（回执撤回/丢失时新增）
CREATE TABLE IF NOT EXISTS review_tasks (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  task_type            VARCHAR(30) NOT NULL,    -- RECEIPT_WITHDRAWN / RECEIPT_LOST
  status               VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- OPEN / DONE
  receipt_id           BIGINT REFERENCES disposal_receipts(id),
  receipt_code         VARCHAR(40),
  batch_id             BIGINT,
  batch_code           VARCHAR(40),
  reason               VARCHAR(400),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  handled_at           TIMESTAMPTZ,
  handled_by_name      VARCHAR(60),
  handle_note          VARCHAR(400)
);

-- 空箱取回记录（取回只进入待清洁区）
CREATE TABLE IF NOT EXISTS box_returns (
  id                   BIGSERIAL PRIMARY KEY,
  code                 VARCHAR(40) UNIQUE NOT NULL,
  manifest_id          BIGINT,
  box_codes            JSONB NOT NULL DEFAULT '[]'::jsonb,
  by_name              VARCHAR(60),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 跨区人员任务与防护用品（feature-4）
-- 区域（净区 CLEAN / 污染区 DIRTY / 转换通道 TRANSITION）、流程卡、独立复核、
-- 防护用品套件与库存全部为随题虚构，不推断任何真实卫生规范。
-- 设计要点：岗位资质、实际所在区域、当前污染状态、本次防护用品分别保存；
-- 每进入一次污染区产生独立一轮 zone_rounds，只有“本轮流程卡 + 独立复核 +
-- 关键退出回执”全部通过才恢复净区资格；旧的清洁/已复核轮次不能覆盖新进入事实。
-- ============================================================================

-- 跨区仿真全局设置（单行）：模拟时钟与转换耗时
CREATE TABLE IF NOT EXISTS zone_settings (
  id              INT PRIMARY KEY DEFAULT 1,
  base_date       DATE NOT NULL DEFAULT DATE '2026-11-27',
  clock_min       INT NOT NULL DEFAULT 600,   -- 模拟当前时刻（自午夜分钟，600=10:00）
  transition_min  INT NOT NULL DEFAULT 10,    -- 题例：退出流程 + 独立复核共 10 分钟
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT zone_settings_singleton CHECK (id = 1)
);

-- 跨区人员：岗位能力(quals) / 固定分区倾向(post_zone) / 实际区域 / 污染状态 / 净区资格分列
CREATE TABLE IF NOT EXISTS zone_staff (
  id                BIGSERIAL PRIMARY KEY,
  code              VARCHAR(40) UNIQUE NOT NULL,   -- W1 / W2 / RV
  name              VARCHAR(60) NOT NULL,
  username          VARCHAR(40),                   -- 关联登录账号（独立复核身份用，可空）
  post_zone         VARCHAR(10) NOT NULL DEFAULT 'FLOAT', -- CLEAN / DIRTY / FLOAT（分守倾向，非硬限制）
  quals             JSONB NOT NULL DEFAULT '[]'::jsonb,    -- 仍有效的岗位能力标签
  can_review        BOOLEAN NOT NULL DEFAULT FALSE,
  active            BOOLEAN NOT NULL DEFAULT TRUE,
  actual_zone       VARCHAR(10) NOT NULL DEFAULT 'CLEAN',  -- 当前实际所在：CLEAN / DIRTY / TRANSITION
  contam_state      VARCHAR(30) NOT NULL DEFAULT 'CLEAN',
  clean_eligible    BOOLEAN NOT NULL DEFAULT TRUE,         -- 当前是否允许接触净区物品（派发硬前提）
  current_round_id  BIGINT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 跨区任务定义 + 当前派工/锁定段（A 净区供箱 / P 污染区交接 / C 净区检查 …）
CREATE TABLE IF NOT EXISTS zone_tasks (
  id                  BIGSERIAL PRIMARY KEY,
  code                VARCHAR(40) UNIQUE NOT NULL,
  title               VARCHAR(120) NOT NULL,
  zone_req            VARCHAR(10) NOT NULL,       -- CLEAN / DIRTY
  duration_min        INT NOT NULL,
  release_min         INT,                          -- 最早可开始时刻（分钟）
  deadline_min        INT,                          -- 完成截止时刻（分钟，C=660 即 11:00）
  depends_on          JSONB NOT NULL DEFAULT '[]'::jsonb, -- 必须先 DONE 的前置任务 code
  required_quals      JSONB NOT NULL DEFAULT '[]'::jsonb,
  kit_code            VARCHAR(40),                  -- DIRTY 任务进入前需领/核销的防护套件
  status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  -- OPEN / CONFIRMED(已确认并锁定人员可用段+用品) / IN_PROGRESS / DONE / CANCELLED
  assignee_code       VARCHAR(40),
  start_min           INT,                          -- 锁定可用段起点
  end_min             INT,                          -- 锁定可用段终点（半开区间，首尾相接不算冲突）
  actual_start_min    INT,
  actual_end_min      INT,
  reservation_code    VARCHAR(40),
  confirmed_by        VARCHAR(60),
  confirmed_at_min    INT,
  started_by          VARCHAR(60),
  started_at_min      INT,
  completed_by        VARCHAR(60),
  completed_at_min    INT,
  cancel_reason       VARCHAR(400),
  cancelled_by        VARCHAR(60),
  cancelled_at_min    INT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zone_tasks_assignee ON zone_tasks(assignee_code);
CREATE INDEX IF NOT EXISTS idx_zone_tasks_status ON zone_tasks(status);

-- 污染区进入轮次（每进一次污染区一行，只追加事实；取消任务也不删除）
CREATE TABLE IF NOT EXISTS zone_rounds (
  id                     BIGSERIAL PRIMARY KEY,
  code                   VARCHAR(40) UNIQUE NOT NULL,  -- ZR-XXXXXXXX
  staff_code             VARCHAR(40) NOT NULL,
  round_no               INT NOT NULL,
  task_code              VARCHAR(40),
  status                 VARCHAR(24) NOT NULL DEFAULT 'IN_DIRTY',
  -- IN_DIRTY / EXITED / CARD_IN_PROGRESS / CHECK_FAILED / REVIEW_PENDING /
  -- REVIEW_FAILED / AWAIT_RECEIPT / CLEARED
  entry_min              INT,
  exit_min               INT,
  cleared_min            INT,
  exit_receipt_status    VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN', -- UNKNOWN(关键回执未知/迟到) / KNOWN
  exit_receipt_code      VARCHAR(60),
  exit_receipt_min       INT,
  receipt_late           BOOLEAN NOT NULL DEFAULT FALSE,
  card_status            VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
  -- NOT_STARTED / IN_PROGRESS / PASSED / FAILED / INTERRUPTED
  check_status           VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING / PASS / FAIL
  review_status          VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING / PASS / FAIL
  reviewer_code          VARCHAR(40),
  reviewer_name          VARCHAR(60),
  reviewer_username      VARCHAR(40),
  reviewed_min           INT,
  review_note            VARCHAR(400),
  kit_code               VARCHAR(40),
  reservation_code       VARCHAR(40),
  kit_lines              JSONB,
  entry_actor            VARCHAR(60),
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(staff_code, round_no)
);
CREATE INDEX IF NOT EXISTS idx_zone_rounds_staff ON zone_rounds(staff_code);

-- 本轮流程卡步骤办理记录（模板见 zone_card_template）
CREATE TABLE IF NOT EXISTS zone_round_steps (
  id            BIGSERIAL PRIMARY KEY,
  round_id      BIGINT NOT NULL,
  step_code     VARCHAR(40) NOT NULL,
  seq           INT NOT NULL,
  label         VARCHAR(160) NOT NULL,
  inspection    BOOLEAN NOT NULL DEFAULT FALSE,
  status        VARCHAR(15) NOT NULL DEFAULT 'PENDING', -- PENDING / DONE / FAILED / INTERRUPTED
  passed        BOOLEAN,
  note          VARCHAR(400),
  actor         VARCHAR(60),
  at_min        INT,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zone_steps_round ON zone_round_steps(round_id);

-- 虚构流程卡模板
CREATE TABLE IF NOT EXISTS zone_card_template (
  step_code     VARCHAR(40) PRIMARY KEY,
  seq           INT NOT NULL,
  label         VARCHAR(160) NOT NULL,
  inspection    BOOLEAN NOT NULL DEFAULT FALSE
);

-- 防护用品 SKU 库存（on_hand=现场实物；HELD 预留另计；进入污染区才真正扣减 on_hand）
CREATE TABLE IF NOT EXISTS zone_ppe_sku (
  id            BIGSERIAL PRIMARY KEY,
  sku_code      VARCHAR(40) UNIQUE NOT NULL,
  name          VARCHAR(120) NOT NULL,
  unit          VARCHAR(10) NOT NULL DEFAULT '件',
  on_hand       INT NOT NULL DEFAULT 0,
  safety_stock  INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS zone_ppe_kit (
  code          VARCHAR(40) PRIMARY KEY,
  name          VARCHAR(120) NOT NULL
);

CREATE TABLE IF NOT EXISTS zone_ppe_kit_line (
  id            BIGSERIAL PRIMARY KEY,
  kit_code      VARCHAR(40) NOT NULL,
  sku_code      VARCHAR(40) NOT NULL,
  qty           INT NOT NULL,
  UNIQUE(kit_code, sku_code)
);

-- 防护用品预留/核销单：确认任务=HELD 锁定；进入污染区=CONSUMED 实耗；
-- 未进入即取消=RELEASED 退回可用；已 CONSUMED 不允许退回。
CREATE TABLE IF NOT EXISTS zone_ppe_reservation (
  id              BIGSERIAL PRIMARY KEY,
  code            VARCHAR(40) UNIQUE NOT NULL,
  task_code       VARCHAR(40),
  staff_code      VARCHAR(40) NOT NULL,
  kit_code        VARCHAR(40) NOT NULL,
  status          VARCHAR(12) NOT NULL DEFAULT 'HELD', -- HELD / CONSUMED / RELEASED
  lines           JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_min     INT,
  consumed_min    INT,
  released_min    INT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zone_res_task ON zone_ppe_reservation(task_code);

-- 只记录真正引起 on_hand 变动的库存流水：RECEIVE 补货 / ADJUST 盘点调整 / CONSUME 进区实耗
CREATE TABLE IF NOT EXISTS zone_ppe_txn (
  id            BIGSERIAL PRIMARY KEY,
  sku_code      VARCHAR(40) NOT NULL,
  change_qty    INT NOT NULL,
  txn_type      VARCHAR(12) NOT NULL,
  reason        VARCHAR(400),
  task_code     VARCHAR(40),
  at_min        INT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zone_tx_sku ON zone_ppe_txn(sku_code);

-- 跨区现场只追加事件（进入/退出/流程/复核/回执/派工/取消…，取消任务不抹除进区事实）
CREATE TABLE IF NOT EXISTS zone_events (
  id            BIGSERIAL PRIMARY KEY,
  at_min        INT,
  at_real       TIMESTAMPTZ NOT NULL DEFAULT now(),
  staff_code    VARCHAR(40),
  task_code     VARCHAR(40),
  round_code    VARCHAR(40),
  action        VARCHAR(50) NOT NULL,
  actor_name    VARCHAR(60),
  detail        VARCHAR(600)
);
CREATE INDEX IF NOT EXISTS idx_zone_events_staff ON zone_events(staff_code);
