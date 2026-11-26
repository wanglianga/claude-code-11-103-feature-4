# 医疗废物收运交接与重量证据平台（Vue 3 + Spring Boot + PostgreSQL）

医院交接员把封好的废物箱交给收运人员，处置厂接过实物后办理接收与处置；任何人都能按封口码 / 箱码 / 交接单号查到**包件现在由谁保管、认可重量、哪些差异尚未解决、各角色下一步**。

> **虚构声明**：所有废物类别、容器规则、机构、人员、资质、车辆、称重仪、处置设备、回执与 **0.5 kg 差异容限**均为随题虚构，不连接任何真实监管或处置设备。

---

## 1. 一键启动（Docker Compose）

```bash
cd /workspace/103
docker compose up --build
```

- 前端（Nginx + Vue）：<http://localhost:8088>
- 后端 API：<http://localhost:8080>
- PostgreSQL：宿主 `localhost:5433`（库/用户/密码 `medwaste/medwaste/medwaste`）

Compose 包含 `db(postgres:18-alpine) + backend(多阶段 Maven/JDK25) + frontend(Node22 构建/Nginx)`，
Nginx 已配置 `/api` 反向代理到 `backend:8080`。

> 说明：交付环境容器内没有 docker 二进制/守护进程，因此 **Docker 镜像构建与 compose up 未在本开发容器实际执行**；
> Dockerfile / compose 按标准多阶段构建编写。下列本地运行与端到端验收均已在预装运行时上**实际执行通过**。

---

## 2. 本地开发与验证（使用容器内预装运行时）

依赖：JDK 25（`/opt/jdk-25`）、Maven 3.9（`/opt/maven`）、Node 22（`/opt/node-22`）、PostgreSQL 18。
默认连接本机 `127.0.0.1:5432` 的 `ccdev` 库（用户/密码 `ccdev`），`schema.sql` 启动自动建表，空库自动播种虚构基线数据。

### 2.1 启动后端（8080）

```bash
cd /workspace/103/backend
/opt/maven/bin/mvn spring-boot:run -DskipTests
# 或先打包再运行：
/opt/maven/bin/mvn clean package -DskipTests
/opt/jdk-25/bin/java -jar target/medwaste-backend.jar
```

健康检查：<http://127.0.0.1:8080/api/meta/health>

可用环境变量覆盖数据库：`SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD / SERVER_PORT`。

### 2.2 启动前端（5173）

```bash
cd /workspace/103/frontend
/opt/node-22/bin/npm install      # 已内置 .npmrc（npmmirror + 项目内 .npm-cache，规避家目录缓存权限问题）
/opt/node-22/bin/npm run dev
```

打开 <http://127.0.0.1:5173> ，Vite 已配置 `/api` 代理到 `127.0.0.1:8080`。

生产构建：`npm run build`（产物在 `frontend/dist`，已验证构建成功）。

### 2.3 一键端到端验收（六场景 + 附加核对，92 条断言）

先保证后端在 `127.0.0.1:8080` 运行，然后：

```bash
node /workspace/103/verify/run.mjs
# 也可走 Vite 代理验证浏览器同源链路：
node /workspace/103/verify/run.mjs http://127.0.0.1:5173
```

脚本与界面里的 **「验收自检」页共用同一份** `frontend/src/verify/scenarios.mjs`
（纯 ESM、零依赖，浏览器和 Node 都能跑）。每次运行先 `POST /api/dev/reset` 重建基线，可重复执行。

**最近一次实际运行结果：`场景 8/8 通过；断言 92/92 通过；ALL_DONE: true`**（分别对 8080 直连与 5173 代理各跑过一次）。

也可以登录任意账号后进入顶部 **「验收自检」** 页，点击按钮在浏览器中查看每条断言。

---

## 3. 测试账号（密码均为 `123456`，admin 除外）

| 角色 | 用户名 | 密码 | 姓名/归属（均虚构） |
| --- | --- | --- | --- |
| 医院交接员 | `hospital01` | `123456` | 王敏 · 虚构市仁和医院 |
| 医院交接员 | `hospital02` | `123456` | 周岚 · 仁和医院 |
| 医院交接员（另一机构） | `kangning01` | `123456` | 吴医生 · 康宁医院 |
| 收运员 | `transport01` | `123456` | 张强 · 安捷收运（车 X-A1201） |
| 收运员（第二接收人） | `transport02` | `123456` | 李娜 · 安捷收运（车 X-A1202） |
| 收运员（资质暂停机构） | `shunda01` | `123456` | 钱顺 · 顺达收运队 |
| 处置厂核实员 | `plant01` | `123456` | 陈实 · 绿源处置厂 |
| 处置车间 | `plant02` | `123456` | 林工 |
| 管理员 | `admin` | `admin123` | 演示环境管理 |

登录页可直接点账号卡片一键登录。公开追踪与 `/api/track/**` 无需登录。

---

## 4. 界面导览（医院 / 收运 / 处置厂）

- **公开追踪（首页）**：封口码 / `C1#R1` / 箱号 / `MD-…` 交接单 / 托盘 / 委托 / 回执号 → 当前保管方、认可重量、未结差异、各角色下一步、只追加时间线；底部是所有未结差异公告板。
- **医院端**：登记箱/发放封口码；开始新一轮（轮次 +1、称实际空箱重）；扫袋码入本轮清单（跨箱重复/旧轮 QR 强拦截）；封箱毛重与封箱；交接单里共同确认复称并**确认交车转移保管**；待清洁区与清洁后复称复用。
- **收运端**：选车建单、扫码识别、连续扫封口码/箱 QR；交接单详情内逐箱录入复称、组织封托与合托毛重、扫箱入托；提交医院；到厂登记；签收后取回空箱。
- **处置厂端**：到厂逐箱核实称（自动算差挂案）、资质/封条核验、实接（保管转厂）、厂内待核、逐箱补称与差异结案、签收；组批 → 处置设备模拟执行 → 签发完成回执；回执撤回/丢失模拟与复核任务办结。
- **差异中心**：全部差异案件、处理方式与跳转办理。
- **模拟器**：4 台称重仪读数注入/读取、2 台处置设备、回执撤回/丢失、基线重置。
- **验收自检**：浏览器内运行 8 组场景并展开每条断言。
- **虚构机构**：三类机构、证照有效期/状态、车辆、类别与规则。

---

## 5. 核心业务规则如何落地

### 5.1 编号与清单唯一性

- 一次性袋有全局唯一**封口码**；`bags.status=ACTIVE_IN_LIST` 记录当前所在箱轮次，服务层 + JSONB 兜底查询保证
  **一个袋只能在一个当前有效箱清单**，跨箱加入第二个清单返回 `409 BAG_ALREADY_IN_LIST`（并带当前箱号）。
- 可重复周转箱 `boxes.current_round_no` 每轮 +1，本轮 QR = `C1#R3`。领袋接口 `POST /api/scan/claim-bag`
  校验 QR 中的轮次：小于当前轮 → `409 OLD_ROUND_QR`；本轮已封 → `409 ROUND_NOT_DRAFT`。
- 封箱（`box_rounds.status=SEALED`）只产生 SEAL 重量证据，箱仍在医院保管；**封箱 ≠ 交车**。

### 5.2 保管责任与并发“两人收同一箱”

- 保管状态只沿 `医院 HOSPITAL → 收运 TRANSPORTER → 处置厂 PLANT → 待清洁区 CLEANING → 医院` 前进，
  每次转移向 `custody_events` **只追加**事件；箱上冗余 `custody_party/org/user/since` 表示“现在谁保管”。
- 医院确认交车 `POST /api/manifests/{code}/handover` 在事务内
  `SELECT … FROM manifests … FOR UPDATE`，再按箱 id 排序对 `boxes … FOR UPDATE`，
  校验每箱仍为 `SEALED`。两个收运员各建一单扫入同一箱、两个医院确认并发时，
  **恰好一单 200，另一单 409 BOX_ALREADY_HANDED**，该箱只有一条 `CUSTODY_HANDOVER` 事件、保管人唯一。

### 5.3 两路重量证据与“不重复累计、不按袋摊造”

重量证据统一在 `weight_records`：`stage(SEAL/HANDOVER_JOINT/PLANT_CHECK/SUPPLEMENT)`、
`mode(BOX_DIRECT/PALLET_COMBINED)`、毛重、箱皮重合计、托盘皮重、净重、与基准差值、版本与状态。

题例 **C1 空 2 / 毛 10，C2 空 3 / 毛 15，托盘 P1 皮 4，合托毛 29**：

| 路径 | 称量次数（交接环节） | 证据 | 净重 |
| --- | --- | --- | --- |
| 逐箱复称 | **2** 次箱称 | C1 净 10−2=**8**，C2 净 15−3=**12**，分箱重量都保留 | 合计 **20 kg** |
| 合托合称 | **1** 次托称 | 29 − 托盘皮 4 − Σ箱皮(2+3)=5 | 托总净 **20 kg**，**不产生分箱 joint 重量** |

- 总认可重量由有效证据重算：有效合托记录覆盖的箱不再累加其逐箱重量，杜绝袋/箱/托重复累计成 40。
- 合托只证明托内总量；清单不完整时共同确认被阻止（`PALLET_LIST_INCOMPLETE` + `MISSING_BOX`），
  重量记录置 `HELD`，下一步是找箱/补扫或逐箱补称。
- 任何 `resolutionType` 含 `RATIO` 的“按袋数比例摊造每箱净重”请求一律
  `400 FABRICATION_FORBIDDEN`；重量差异只能通过 `supplement-weigh` 提交**逐箱真实读数**，
  再以 `ACCEPT_MEASURED` 更新认可重量（基准标记 `PLANT_ADJUSTED`，不回改历史交接证据）。
- **旧称量修订** `POST /api/weighing/{id}/revise` 新增版本：容差内旧版置 `SUPERSEDED`、新版 `EFFECTIVE` 并更新认可重量；
  历史毛重/版本/修订原因全部保留。

### 5.4 0.5 kg 容限、厂内待核与不可退回

- 共同确认 / 到厂核实中 `|diff| > 0.5`（`app.weight-tolerance-kg` 可调）即自动建立 `WEIGHT_MISMATCH` 案件，
  证据置 `DISPUTED`，认可重量维持不变。
- 到厂逐箱合计比交接认可总量少 2 kg 时挂案；处置厂实接后单据为
  `IN_PLANT_PENDING_CHECK`（**厂内待核**），箱状态 `PLANT_PENDING_CHECK`，保管方为 PLANT。
- 此时调用 `POST /api/manifests/{code}/return-to-transit` 必返回
  `409 RETURN_IN_TRANSIT_FORBIDDEN`；追踪页给出 `NO_RETURN_IN_TRANSIT` 提示。
- 补称并结案最后一项差异后，单据自动转 `READY_TO_SIGN`，厂方才可签收。

### 5.5 委托、处置回执丢失/撤回

- 交车时生成委托 `mandates(ACTIVE)`；签收单组批 `disposal_batches`，经处置模拟器 `perform` 后可签发
  `disposal_receipts(VALID)`。只有存在覆盖该交接单的 **VALID** 回执时，委托才 `COMPLETED`。
- 回执 `lose` / `withdraw` **不删行**（状态 LOST/WITHDRAWN 全部保留），新增一条 OPEN `review_tasks`，
  委托重算为 `REVIEW_REQUIRED`；可补开新回执使委托重新 COMPLETED，再人工办结复核任务。
  反复失效不改变 `custody_events` 中的真实交接/签收事实。

### 5.6 空箱取回 ≠ 可直接复用

`return-empties` 后箱 → `PENDING_CLEANING`（保管方 `CLEANING`，待清洁区）；
直接开新轮返回 `409 BOX_PENDING_CLEANING`。清洁/检查并填入清洁后皮重 `/boxes/{code}/clean` 后才
`AVAILABLE_CLEAN`，下一次开轮得到 R2，旧 `Cx#R1` 扫码领新袋返回 `OLD_ROUND_QR`。

---

## 6. 六个规定验证场景 ↔ 自动断言

`verify/scenarios.mjs`（Node 与浏览器共用）实际通过的 8 组、92 条断言覆盖：

1. **two-receivers 两人收同一箱**：两收运员各建单扫码 → 并发医院确认 → 恰好一成一 409；
   追踪保管人=成功单收运员；箱只有一条交车事件；失败单可取消；下一步=登记到厂。
2. **pallet-missing-box 合托缺一箱**：声明 2 箱只扫 1 箱，合托确认被阻止并挂 `MISSING_BOX`（指明缺箱）；
   按袋比例摊造被 `FABRICATION_FORBIDDEN` 拒绝；缺失箱无 joint 重量、托无认可总量；
   在托箱改逐箱 8kg 交接、缺箱仍在医院；缺箱另单交接后凭单号结案。
3. **plant-short-2kg 到厂少 2 千克**：合托 20kg 交接；到厂 18kg、diff −2 挂案；
   实接后厂内待核、保管=PLANT；下一步含补称/共同复称/禁退回；退回在途 409；补称后认可 18kg 再签收。
4. **duplicate-bag 袋码跨箱重复**：同封口码加入第二箱 → 409 `BAG_ALREADY_IN_LIST` 且指明当前箱。
5. **weight-revision 旧称量修订**：10kg→10.4kg（差 +0.4）出 v2 EFFECTIVE、v1 SUPERSEDED 且原始 10kg 保留、认可 8.4kg。
6. **receipt-lost 处置回执丢失**：VALID→委托 COMPLETED；丢失→LOST+OPEN 复核+委托 REVIEW_REQUIRED+下一步补回执；
   补开→COMPLETED、复核办结；再撤回→WITHDRAWN+撤回复核；三张回执状态历史 `[LOST, WITHDRAWN, VALID]`；交车事实事件仍仅一条。
7. **weight-routes-example（附加）**：C1/C2/P1 孪生数据验证题例——逐箱 2 次称量得 8/12，合托 1 次称量 29−5−4=20，
   两路均 20、基准不同、合托路无分箱证据、不重复累计。
8. **container-lifecycle（附加）**：签收→空箱 PENDING_CLEANING→不能直接开轮→清洁称皮→R2→旧 QR `OLD_ROUND_QR`、新 QR 可领袋。

---

## 7. 目录与主要 API

```
103/
├── docker-compose.yml          # db + backend + frontend(nginx)
├── verify/run.mjs              # node verify/run.mjs [BASE]
├── backend/                    # Spring Boot 3.5 / JDK21+ / JdbcTemplate / PostgreSQL
│   ├── Dockerfile  docker-settings.xml
│   └── src/main/
│       ├── resources/schema.sql, application.yml
│       └── java/com/medwaste/{auth,common,container,manifest,weight,discrepancy,
│                              disposal,custody,track,sim,seed,web}
└── frontend/                   # Vue3 + Vite + Element Plus
    ├── Dockerfile  nginx.conf  .npmrc
    └── src/{pages,components,verify/scenarios.mjs,api.js,router.js}
```

主要接口（均以 `/api` 为前缀；除 `/auth/login`、`/track/**`、`/public/**`、`/dev/**` 外需 Bearer token）：

- 认证/元数据：`POST /auth/login`、`GET /auth/demo-accounts`、`GET /orgs`、`/vehicles`、`/categories`、`/meta/rules`
- 箱袋托：`/boxes`、`/boxes/{code}/rounds|clean`、`/bags`、`/scan/claim-bag`、`/box-rounds/{id}/seal`、`/pallets`
- 交接：`/manifests`、`/manifests/{code}/scan|submit|handover|arrive|intake|sign|cancel|return-empties|return-to-transit|plant-weigh|pallet-load`
- 称重：`/weighing/handover-box|pallet`、`/weighing/{id}/confirm|revise`、`/pallet-loads/{id}/scan`、`/discrepancies/{code}/supplement-weigh|resolve`
- 处置：`/disposal-batches`、`/disposal-batches/{code}/perform|issue-receipt`、`/receipts/{code}/withdraw|lose`、`/review-tasks/{code}/handle`
- 公开：`GET /track/{code}`、`GET /track/open-discrepancies`
- 模拟/开发：`GET/PATCH /sim/scales[/{code}]`、`GET /sim/scales/{code}/read`、`/sim/disposal-devices`、`POST /dev/reset`

本项目为全栈应用，按题目说明不强制提供 Swagger/OpenAPI UI；以上接口可直接由界面或 `verify/run.mjs` 对照调用。

---

## 8. 本次实际验证记录（如实描述）

在交付容器内实际执行并通过：

- `mvn compile` 通过；`mvn -B package -DskipTests` 生成 `target/medwaste-backend.jar`（约 24.9 MB），并用 JDK25 `java -jar` 实际启动验证。
- `npm install` 成功（需用项目 `.npmrc` 的项目内缓存规避家目录缓存 EACCES）；`npm run build` 成功；`npm run dev` 启动 Vite 6 且所有页面 SFC 经 Vite 转译均 200，`/api` 代理健康检查通过。
- PostgreSQL 18（127.0.0.1:5432/ccdev）连接正常，`schema.sql` 自动建表、播种成功。
- `node verify/run.mjs http://127.0.0.1:8080` 与 `… http://127.0.0.1:5173` 均为 **8/8 场景、92/92 断言通过，ALL_DONE: true**；打包 jar 在 8081 端口同样跑过一遍 92/92。
- 用 Vite `ssrLoadModule + renderToString` 对全部 10 个路由做了渲染冒烟，所有页面 setup/模板渲染无异常（临时脚本未保留在交付物中）。
- 未执行项：本容器没有 docker CLI/守护进程，故未实际 `docker compose build/up`；未使用真实浏览器做像素级点击（环境无可用无头浏览器），UI 经生产构建、Vite 转译、SSR 渲染冒烟与接口联调验证。
