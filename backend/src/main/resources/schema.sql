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
