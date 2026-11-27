// 六场景端到端验收 + 两路重量证据/容器生命周期附加核对。
// 同一份模块同时供：Node 命令行（verify/run.mjs）与 Vue「验收自检」页调用。
// 纯 ESM、零依赖，使用全局 fetch（Node >=18 / 现代浏览器）。

export const DEMO_ACCOUNTS = [
  { username: 'hospital01', password: '123456', label: '医院交接员·王敏' },
  { username: 'hospital02', password: '123456', label: '医院交接员·周岚' },
  { username: 'transport01', password: '123456', label: '收运员·张强' },
  { username: 'transport02', password: '123456', label: '收运员·李娜' },
  { username: 'plant01', password: '123456', label: '处置厂核实员·陈实' },
  { username: 'admin', password: 'admin123', label: '管理员' }
]

async function raw(base, method, path, body, token) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['Authorization'] = 'Bearer ' + token
  const res = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let data = null
  if (text) { try { data = JSON.parse(text) } catch { data = { raw: text } } }
  return { status: res.status, ok: res.ok, data }
}

function client(base, token) {
  return {
    token,
    raw: (m, p, b) => raw(base, m, p, b, token),
    get: (p) => raw(base, 'GET', p, undefined, token),
    post: (p, b) => raw(base, 'POST', p, b === undefined ? {} : b, token),
    patch: (p, b) => raw(base, 'PATCH', p, b, token),
    del: (p) => raw(base, 'DELETE', p, undefined, token)
  }
}

function must(r, what) {
  if (!r || !r.ok) {
    throw new Error(`${what}失败：HTTP ${r?.status} ${r?.data?.code ?? ''} ${r?.data?.message ?? ''}`)
  }
  return r.data
}

function fmt(v) {
  if (typeof v === 'number') return String(v)
  try { return JSON.stringify(v) } catch { return String(v) }
}

// ---------- 复用业务编排 ----------

async function sealedBox(H, tag, opts = {}) {
  const { tare = 2, gross = 10 } = opts
  const code = `${tag}${H.R}`.slice(0, 18)
  const seal = `S-${tag}-${H.R}`
  const qr = `${code}#R1`
  must(await H.U.h.post('/api/boxes', { code }), '建箱')
  must(await H.U.h.post(`/api/boxes/${code}/rounds`, { actualTareKg: tare, deviceCode: 'SC-H1' }), '开始轮次')
  must(await H.U.h.post('/api/bags', { sealCode: seal, categoryCode: opts.cat || 'FW-INF' }), '建袋')
  must(await H.U.h.post('/api/scan/claim-bag', { boxQr: qr, sealCode: seal }), '袋码入清单')
  const detail = must(await H.U.h.get(`/api/boxes/${code}`), '查箱')
  must(await H.U.h.post(`/api/box-rounds/${detail.currentRound.id}/seal`,
    { grossKg: gross, deviceCode: 'SC-H1' }), '封箱')
  return { code, seal, qr, tare, gross, net: gross - tare }
}

async function handoverBoxWeight(H, manifestCode, qr, gross) {
  const proposed = must(await H.U.t1.post('/api/weighing/handover-box',
    { manifestCode, boxQr: qr, grossKg: gross, deviceCode: 'SC-T1' }), '录入逐箱复称')
  const confirmed = must(await H.U.h.post(`/api/weighing/${proposed.id}/confirm`, {}), '共同确认重量')
  return confirmed
}

async function manifestWith(H, boxes, { vehicle = 'X-A1201', weigh = true } = {}) {
  const m = must(await H.U.t1.post('/api/manifests', { vehicleCode: vehicle }), '建交接单')
  for (const b of boxes) {
    must(await H.U.t1.post(`/api/manifests/${m.code}/scan`, { scanCode: b.qr }), '扫码入单')
  }
  if (weigh) {
    for (const b of boxes) await handoverBoxWeight(H, m.code, b.qr, b.gross)
  }
  must(await H.U.h.post(`/api/manifests/${m.code}/handover`, {}), '医院确认交车')
  return must(await H.U.t1.get(`/api/manifests/${m.code}`), '查交接单')
}

async function signedSingleBox(H, tag, gross = 10) {
  const b = await sealedBox(H, tag, { tare: 2, gross })
  const m = await manifestWith(H, [b])
  must(await H.U.t1.post(`/api/manifests/${m.code}/arrive`, {}), '到厂登记')
  const pw = must(await H.U.p.post(`/api/manifests/${m.code}/plant-weigh`,
    { readings: [{ boxQr: b.qr, grossKg: gross, deviceCode: 'SC-P1' }] }), '到厂称')
  must(await H.U.p.post(`/api/manifests/${m.code}/intake`,
    { qualOk: true, sealOk: true, note: '资质、封条、重量正常' }), '实接')
  must(await H.U.p.post(`/api/manifests/${m.code}/sign`, { note: '同意签收' }), '签收')
  return { ...b, manifestCode: m.code, plantWeigh: pw }
}

// ---------- 场景定义 ----------

const SCENARIOS = [
  {
    id: 'two-receivers',
    title: '两人收同一箱：并发确认只有一人成功，保管责任唯一',
    async run(H) {
      const b = await sealedBox(H, 'T1')
      const m1 = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1201' }), '建单1')
      const m2 = must(await H.U.t2.post('/api/manifests', { vehicleCode: 'X-A1202' }), '建单2')
      const s1 = await H.U.t1.post(`/api/manifests/${m1.code}/scan`, { scanCode: b.qr })
      const s2 = await H.U.t2.post(`/api/manifests/${m2.code}/scan`, { scanCode: b.qr })
      H.check(s1.ok && s2.ok, '两名收运员都能扫到同一已封箱并形成待确认清单（冲突在确认时裁决）',
        `scan1=${s1.status} scan2=${s2.status} ${s2.data?.message ?? ''}`)
      must(await H.U.t1.post(`/api/manifests/${m1.code}/submit`, {}), '提交1')
      must(await H.U.t2.post(`/api/manifests/${m2.code}/submit`, {}), '提交2')

      const [r1, r2] = await Promise.all([
        H.U.h.post(`/api/manifests/${m1.code}/handover`, {}),
        H.U.h.post(`/api/manifests/${m2.code}/handover`, {})
      ])
      const ok = [r1, r2].filter(r => r.ok)
      const bad = [r1, r2].find(r => !r.ok)
      H.eq(ok.length, 1, '并发医院确认后恰好一单交接成功')
      H.eq(bad?.status, 409, '失败方返回 409 冲突')
      H.check(['BOX_ALREADY_HANDED', 'MANIFEST_ALREADY_HANDED'].includes(bad?.data?.code),
        '失败错误码为箱/单已交接', bad?.data?.code)

      const winner = ok[0].data
      const loserCode = r1.ok ? m2.code : m1.code
      const loserUser = r1.ok ? H.U.t2 : H.U.t1
      const winnerName = winner.transporterName

      const track = must(await H.U.pub.get(`/api/track/${b.seal}`), '公开追踪')
      H.eq(track.custodian?.party, 'TRANSPORTER', '封口码追踪：当前保管方=收运方')
      H.eq(track.custodian?.person, winnerName, '保管人=成功交接单的收运员（不存在两个保管人）')

      const box = must(await H.U.h.get(`/api/boxes/${b.code}`), '箱详情')
      const handoverEvents = box.custodyEvents.filter(e => e.action === 'CUSTODY_HANDOVER')
      H.eq(handoverEvents.length, 1, '该箱只产生一条交车保管转移事件')

      const cancel = await loserUser.post(`/api/manifests/${loserCode}/cancel`, {})
      H.check(cancel.ok, '未抢到的收运员可作废自己的冲突单', cancel.data?.message ?? '')

      const mt = must(await H.U.pub.get(`/api/track/${winner.code}`), '单追踪')
      H.check(mt.nextSteps.some(s => s.code === 'REGISTER_ARRIVAL'),
        '成功收运员下一步=登记到厂', mt.nextSteps.map(s => s.code).join(','))
    }
  },

  {
    id: 'pallet-missing-box',
    title: '合托缺一箱：阻止完整合称、挂缺箱差异、禁止按袋数摊造，改逐箱/另单办理',
    async run(H) {
      const c1 = await sealedBox(H, 'M1', { tare: 2, gross: 10 })
      const c2 = await sealedBox(H, 'M2', { tare: 3, gross: 15 })
      const palletCode = `MX${H.R}`.slice(0, 12)
      must(await H.U.h.post('/api/pallets', { code: palletCode, tareKg: 4 }), '建托盘')
      const m = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1201' }), '建单')
      const load = must(await H.U.t1.post(`/api/manifests/${m.code}/pallet-load`,
        { palletCode, boxQrs: [c1.qr, c2.qr] }), '建封托清单')
      must(await H.U.t1.post(`/api/manifests/${m.code}/scan`, { scanCode: c1.qr }), '只扫到 C1')
      must(await H.U.p.post(`/api/pallet-loads/${load.id}/scan`, { boxQr: c1.qr }), '托扫 C1')

      // 物理上只有 C1 在托：毛重 = 托4 + C1毛10 = 14
      const pw = must(await H.U.t1.post('/api/weighing/pallet',
        { loadId: load.id, grossKg: 14, deviceCode: 'SC-T1' }), '录入合称')
      const cf = must(await H.U.h.post(`/api/weighing/${pw.id}/confirm`, {}), '合称确认结果')
      H.eq(cf.blocked, true, '清单缺一箱时共同确认被阻止')
      H.eq(cf.code, 'PALLET_LIST_INCOMPLETE', '返回 PALLET_LIST_INCOMPLETE')
      const discCode = cf.discrepancy?.code
      H.check(!!discCode && cf.discrepancy.type === 'MISSING_BOX', '生成 MISSING_BOX 未结差异', discCode)
      H.check(JSON.stringify(cf.missingQrs || []).includes(c2.code), '缺失项明确指向第二箱',
        JSON.stringify(cf.missingQrs))

      const fab = await H.U.p.post(`/api/discrepancies/${discCode}/resolve`,
        { resolutionType: 'ALLOCATE_BY_BAG_RATIO', note: '按袋数摊一下' })
      H.eq(fab.status, 400, '按袋数比例摊造每箱净重的请求被拒绝')
      H.eq(fab.data?.code, 'FABRICATION_FORBIDDEN', '错误码 FABRICATION_FORBIDDEN')

      const c2Detail = must(await H.U.h.get(`/api/boxes/${c2.code}`), 'C2详情')
      const joint = c2Detail.currentRound.weights.filter(w => w.stage === 'HANDOVER_JOINT')
      H.eq(joint.length, 0, '没有为缺失箱制造任何交接复称重量')
      const loadNow = must(await H.U.p.get(`/api/pallet-loads/${load.id}`), '托详情')
      H.check(loadNow.recognizedTotalNetKg == null, '不完整托没有认可总净重')

      // 现场路径：在托的 C1 改逐箱复称并随本单交接；C2 仍在医院
      const w1 = await handoverBoxWeight(H, m.code, c1.qr, 10)
      H.eq(w1.code, 'WEIGHT_EFFECTIVE', 'C1 逐箱复称有效，认可净重 8kg')
      must(await H.U.h.post(`/api/manifests/${m.code}/handover`, {}), 'C1单交接')
      const t1 = must(await H.U.pub.get(`/api/track/${c1.code}`), 'C1追踪')
      const t2 = must(await H.U.pub.get(`/api/track/${c2.code}`), 'C2追踪')
      H.eq(t1.custodian.party, 'TRANSPORTER', 'C1 保管已转收运方')
      H.eq(t2.custodian.party, 'HOSPITAL', '未交的 C2 保管仍在医院（责任不被合称裹挟）')

      // C2 找到后另单交接
      const m2 = await manifestWith(H, [c2], { vehicle: 'X-A1202' })
      H.eq(m2.status, 'HANDED_OVER', 'C2 另单完成交接')
      const resolved = await H.U.p.post(`/api/discrepancies/${discCode}/resolve`,
        { resolutionType: 'BOX_HANDLED_SEPARATELY', note: `缺箱已找到并经 ${m2.code} 另单交接` })
      H.eq(resolved.data?.status, 'RESOLVED', '缺箱差异凭另办交接单号结案')
    }
  },

  {
    id: 'plant-short-2kg',
    title: '到厂少 2 千克：厂内待核、禁止退回在途、补称后认可 18kg 再签收',
    async run(H) {
      const a = await sealedBox(H, 'P1', { tare: 2, gross: 10 })
      const b = await sealedBox(H, 'P2', { tare: 3, gross: 15 })
      const palletCode = `PY${H.R}`.slice(0, 12)
      must(await H.U.h.post('/api/pallets', { code: palletCode, tareKg: 4 }), '托盘')
      const m = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1201' }), '建单')
      for (const x of [a, b]) must(await H.U.t1.post(`/api/manifests/${m.code}/scan`, { scanCode: x.qr }), '扫码')
      const load = must(await H.U.t1.post(`/api/manifests/${m.code}/pallet-load`,
        { palletCode, boxQrs: [a.qr, b.qr] }), '封托')
      must(await H.U.p.post(`/api/pallet-loads/${load.id}/scan`, { boxQr: a.qr }), '扫A')
      must(await H.U.p.post(`/api/pallet-loads/${load.id}/scan`, { boxQr: b.qr }), '扫B')
      const pw = must(await H.U.t1.post('/api/weighing/pallet',
        { loadId: load.id, grossKg: 29, deviceCode: 'SC-T1' }), '合托毛重29')
      const cf = must(await H.U.h.post(`/api/weighing/${pw.id}/confirm`, {}), '确认合称')
      H.eq(cf.code, 'WEIGHT_EFFECTIVE', '完整一托合称有效')
      H.eq(Number(cf.weight.netKg), 20, '合托认可总净重 = 29-4-(2+3) = 20kg')
      must(await H.U.h.post(`/api/manifests/${m.code}/handover`, {}), '交车')
      must(await H.U.t1.post(`/api/manifests/${m.code}/arrive`, {}), '到厂')

      // 到厂逐箱核实：C1 毛 9（净7）、C2 毛 14（净11）→ 合计18，少2
      const plant = must(await H.U.p.post(`/api/manifests/${m.code}/plant-weigh`, {
        readings: [
          { boxQr: a.qr, grossKg: 9, deviceCode: 'SC-P1' },
          { boxQr: b.qr, grossKg: 14, deviceCode: 'SC-P1' }
        ]
      }), '到厂核实称')
      H.eq(Number(plant.plantTotalNetKg), 18, '到厂核实合计净重 18kg')
      H.eq(Number(plant.diffKg), -2, '与交接认可总量差 -2kg')
      H.check(plant.diffKg !== undefined && Math.abs(Number(plant.diffKg)) > 0.5 && !!plant.discrepancy,
        '绝对值超过 0.5kg 阈值，自动挂重量差异')
      const discCode = plant.discrepancy.code

      const intake = must(await H.U.p.post(`/api/manifests/${m.code}/intake`,
        { qualOk: true, sealOk: true, note: '实物已卸入厂内' }), '实接')
      H.eq(intake.status, 'IN_PLANT_PENDING_CHECK', '已实接但差异未结 → 厂内待核')

      const track = must(await H.U.pub.get(`/api/track/${m.code}`), '追踪')
      H.eq(track.custodian.party, 'PLANT', '保管责任已到处置厂')
      H.eq(track.status, 'IN_PLANT_PENDING_CHECK', '公开查询显示厂内待核')
      H.check(track.openDiscrepancies.some(d => d.code === discCode), '公开可查未结的 -2kg 差异')
      const codes = track.nextSteps.map(s => s.code)
      H.check(codes.includes('SUPPLEMENT_WEIGH_RESOLVE'), '处置厂下一步=补称并结案')
      H.check(codes.includes('JOIN_REWEIGH'), '收运方下一步=到场共同复称')
      H.check(codes.includes('NO_RETURN_IN_TRANSIT'), '明确提示不能退回在途')
      const signStep = track.nextSteps.find(s => s.code === 'SIGN_AFTER_RESOLVED')
      H.check(signStep && signStep.enabled === false, '差异未结时签收动作禁用并说明原因')

      const back = await H.U.t1.post(`/api/manifests/${m.code}/return-to-transit`, {})
      H.eq(back.status, 409, '退回在途请求被拒绝（409）')
      H.eq(back.data?.code, 'RETURN_IN_TRANSIT_FORBIDDEN', '错误码 RETURN_IN_TRANSIT_FORBIDDEN')

      must(await H.U.p.post(`/api/discrepancies/${discCode}/supplement-weigh`, {
        readings: [
          { boxQr: a.qr, grossKg: 9, deviceCode: 'SC-P2' },
          { boxQr: b.qr, grossKg: 14, deviceCode: 'SC-P2' }
        ],
        note: '双方在场补称'
      }), '补称')
      const resolved = must(await H.U.p.post(`/api/discrepancies/${discCode}/resolve`,
        { resolutionType: 'ACCEPT_MEASURED' }), '按实测结案')
      H.eq(resolved.status, 'RESOLVED', '重量差异结案')

      const after = must(await H.U.p.get(`/api/manifests/${m.code}`), '结案后单据')
      H.eq(Number(after.totalRecognizedNetKg), 18, '认可重量更新为实测 18kg')
      H.eq(after.weightBasis, 'PLANT_ADJUSTED', '重量基准标记为补称后厂调，不回改历史证据')
      H.eq(after.status, 'READY_TO_SIGN', '差异清零后转为待签收')
      const signed = must(await H.U.p.post(`/api/manifests/${m.code}/sign`, { note: '补称后签收' }), '签收')
      H.eq(signed.status, 'SIGNED', '处置厂完成签收')
    }
  },

  {
    id: 'duplicate-bag',
    title: '袋码跨箱重复：同一封口码不能进入两个当前有效清单',
    async run(H) {
      const x = await sealedBox(H, 'D1')
      const code2 = `D2${H.R}`.slice(0, 18)
      must(await H.U.h.post('/api/boxes', { code: code2 }), '建第二箱')
      must(await H.U.h.post(`/api/boxes/${code2}/rounds`, { actualTareKg: 3 }), '第二箱轮次')
      const dup = await H.U.h.post('/api/scan/claim-bag',
        { boxQr: `${code2}#R1`, sealCode: x.seal })
      H.eq(dup.status, 409, '跨箱重复袋码返回 409')
      H.eq(dup.data?.code, 'BAG_ALREADY_IN_LIST', '错误码 BAG_ALREADY_IN_LIST')
      H.eq(dup.data?.currentBoxCode, x.code, '错误体指明该袋当前所在箱')
      const bag = must(await H.U.h.get(`/api/bags/${x.seal}`), '查袋')
      H.eq(bag.currentBoxCode, x.code, '袋码当前有效清单唯一')
    }
  },

  {
    id: 'weight-revision',
    title: '旧称量修订：新增版本而非覆盖，历史读数保留、认可重量更新',
    async run(H) {
      const b = await sealedBox(H, 'V1', { tare: 2, gross: 10 })
      const m = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1201' }), '建单')
      must(await H.U.t1.post(`/api/manifests/${m.code}/scan`, { scanCode: b.qr }), '扫码')
      const cf = await handoverBoxWeight(H, m.code, b.qr, 10)
      const wid = cf.weight.id
      H.eq(Number(cf.weight.netKg), 8, '初次复称认可净重 8kg')

      const rev = must(await H.U.h.post(`/api/weighing/${wid}/revise`,
        { grossKg: 10.4, reason: '旧秤读数抄录误差，仪表校准后修订' }), '修订')
      H.eq(rev.status, 'EFFECTIVE', '容差内修订版本生效（差 +0.4kg ≤ 0.5）')
      H.eq(rev.version, 2, '生成第 2 版，而不是覆盖第 1 版')
      H.eq(Number(rev.netKg), 8.4, '修订后净重 8.4kg')

      const detail = must(await H.U.h.get(`/api/boxes/${b.code}`), '箱详情')
      const chain = detail.currentRound.weights.filter(w => w.stage === 'HANDOVER_JOINT')
      H.eq(chain.length, 2, '交接复称存在两个版本，旧记录未被删除')
      const v1 = chain.find(w => w.version === 1)
      const v2 = chain.find(w => w.version === 2)
      H.eq(v1.status, 'SUPERSEDED', '旧版本标记为 SUPERSEDED 留痕')
      H.eq(Number(v1.grossKg), 10, '旧版本毛重仍为原始 10kg（真实历史保留）')
      H.eq(v2.status, 'EFFECTIVE', '新版本为当前有效')
      H.eq(Number(detail.currentRound.recognizedNetKg), 8.4, '分箱认可重量=修订后 8.4kg')
    }
  },

  {
    id: 'receipt-lost',
    title: '处置回执丢失/撤回：委托不能结束，新增复核任务，历史回执与交接事实保留',
    async run(H) {
      const b = await signedSingleBox(H, 'R1')
      const bc = must(await H.U.p.post('/api/disposal-batches', { manifestCodes: [b.manifestCode] }), '组批').code
      must(await H.U.p.post(`/api/disposal-batches/${bc}/perform`,
        { deviceCode: 'DISP-1', method: '高温蒸煮（虚构工艺）' }), '处置模拟')
      const r1 = must(await H.U.p.post(`/api/disposal-batches/${bc}/issue-receipt`, {}), '签发回执1')
      let md = must(await H.U.p.get(`/api/manifests/${b.manifestCode}`), '单')
      H.eq(md.mandate.status, 'COMPLETED', '有效回执 → 委托结束 COMPLETED')

      const lost = must(await H.U.p.post(`/api/receipts/${r1.code}/lose`,
        { reason: '模拟器：回执纸质联在传递中丢失' }), '回执丢失')
      H.eq(lost.receipt.status, 'LOST', '原回执标记 LOST 但保留行')
      H.eq(lost.reviewTask.status, 'OPEN', '丢失自动新增 OPEN 复核任务')
      md = must(await H.U.p.get(`/api/manifests/${b.manifestCode}`), '单2')
      H.eq(md.mandate.status, 'REVIEW_REQUIRED', '回执失效后委托回到 REVIEW_REQUIRED，不能结束')
      const tr = must(await H.U.pub.get(`/api/track/${b.manifestCode}`), '追踪')
      H.check(tr.nextSteps.some(s => s.code === 'REISSUE_RECEIPT_HANDLE_REVIEW'),
        '处置厂下一步=补开回执并办结复核')

      const r2 = must(await H.U.p.post(`/api/disposal-batches/${bc}/issue-receipt`, {}), '补开回执2')
      H.eq(r2.status, 'VALID', '补开新回执 VALID')
      must(await H.U.p.post(`/api/review-tasks/${lost.reviewTask.code}/handle`,
        { note: `已补开回执 ${r2.code}` }), '办结丢失复核')
      md = must(await H.U.p.get(`/api/manifests/${b.manifestCode}`), '单3')
      H.eq(md.mandate.status, 'COMPLETED', '新有效回执使委托重新结束')

      const wd = must(await H.U.p.post(`/api/receipts/${r2.code}/withdraw`,
        { reason: '模拟器：监管端要求撤回该回执' }), '撤回')
      H.eq(wd.receipt.status, 'WITHDRAWN', '回执2 标记 WITHDRAWN')
      H.eq(wd.reviewTask.taskType, 'RECEIPT_WITHDRAWN', '撤回同样新增复核任务')
      md = must(await H.U.p.get(`/api/manifests/${b.manifestCode}`), '单4')
      H.eq(md.mandate.status, 'REVIEW_REQUIRED', '再次失去有效回执 → 委托复核中')
      const r3 = must(await H.U.p.post(`/api/disposal-batches/${bc}/issue-receipt`, {}), '补开回执3')
      must(await H.U.p.post(`/api/review-tasks/${wd.reviewTask.code}/handle`,
        { note: `撤回事项复核完毕，以 ${r3.code} 为准` }), '办结撤回复核')

      const receipts = must(await H.U.p.get(`/api/receipts?batchCode=${bc}`), '回执历史')
      H.eq(receipts.length, 3, '三张回执全部保留（丢失/撤回不删历史）')
      H.deepEq(receipts.map(r => r.status), ['LOST', 'WITHDRAWN', 'VALID'], '回执状态历史顺序')

      const box = must(await H.U.h.get(`/api/boxes/${b.code}`), '箱')
      H.eq(box.custodyEvents.filter(e => e.action === 'CUSTODY_HANDOVER').length, 1,
        '回执反复失效不改变真实交接事实（交车事件仍只有一条）')
      md = must(await H.U.p.get(`/api/manifests/${b.manifestCode}`), '最终单')
      H.check(!!md.handedAt && !!md.signedAt, '交接/签收时间事实仍在')
      H.eq(md.mandate.status, 'COMPLETED', '最终有效回执对应批次 → 委托结束')
    }
  },

  {
    id: 'weight-routes-example',
    title: '题例 C1/C2/P1：两路净重均 20kg，但称量次数与分箱证据不同，且不重复累计',
    async run(H) {
      // 路线 A：逐箱复称（孪生箱 EA1/EA2）
      const a1 = await sealedBox(H, 'A1', { tare: 2, gross: 10 })
      const a2 = await sealedBox(H, 'A2', { tare: 3, gross: 15 })
      const ma = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1201' }), 'A单')
      for (const x of [a1, a2]) must(await H.U.t1.post(`/api/manifests/${ma.code}/scan`, { scanCode: x.qr }), '扫')
      await handoverBoxWeight(H, ma.code, a1.qr, 10)
      await handoverBoxWeight(H, ma.code, a2.qr, 15)
      const da = must(await H.U.t1.get(`/api/manifests/${ma.code}`), 'A单详情')
      const boxRecords = da.weights.filter(w => w.stage === 'HANDOVER_JOINT' && w.mode === 'BOX_DIRECT' && w.status === 'EFFECTIVE')
      H.eq(boxRecords.length, 2, '逐箱路：2 次有效交接称量')
      H.deepEq(boxRecords.map(w => Number(w.netKg)).sort((x, y) => x - y), [8, 12],
        '逐箱保留各箱净重 8 / 12kg')
      H.eq(Number(da.totalRecognizedNetKg), 20, '逐箱路合计认可净重 20kg')
      H.eq(da.weightBasis, 'BOX_DIRECT', '重量基准 BOX_DIRECT')

      // 路线 B：合托合称（孪生箱 EB1/EB2 + 托盘皮重4，毛重29）
      const b1 = await sealedBox(H, 'B1', { tare: 2, gross: 10 })
      const b2 = await sealedBox(H, 'B2', { tare: 3, gross: 15 })
      const palletCode = `EP${H.R}`.slice(0, 12)
      must(await H.U.h.post('/api/pallets', { code: palletCode, tareKg: 4 }), '托盘')
      const mb = must(await H.U.t1.post('/api/manifests', { vehicleCode: 'X-A1202' }), 'B单')
      for (const x of [b1, b2]) must(await H.U.t1.post(`/api/manifests/${mb.code}/scan`, { scanCode: x.qr }), '扫')
      const load = must(await H.U.t1.post(`/api/manifests/${mb.code}/pallet-load`,
        { palletCode, boxQrs: [b1.qr, b2.qr] }), '封托')
      must(await H.U.p.post(`/api/pallet-loads/${load.id}/scan`, { boxQr: b1.qr }), '扫1')
      must(await H.U.p.post(`/api/pallet-loads/${load.id}/scan`, { boxQr: b2.qr }), '扫2')
      const pw = must(await H.U.t1.post('/api/weighing/pallet',
        { loadId: load.id, grossKg: 29 }), '合称')
      const cf = must(await H.U.h.post(`/api/weighing/${pw.id}/confirm`, {}), '确认')
      H.eq(cf.code, 'WEIGHT_EFFECTIVE', '合托路确认有效')
      H.eq(Number(cf.weight.tareBoxKg), 5, '合称扣减各箱实际空箱重之和 2+3=5kg（只扣一次）')
      H.eq(Number(cf.weight.tarePalletKg), 4, '合称另扣托盘皮重 4kg（只扣一次）')
      H.eq(Number(cf.weight.netKg), 20, '合托路净重 29-5-4=20kg')
      const db = must(await H.U.t1.get(`/api/manifests/${mb.code}`), 'B单详情')
      const palletRecords = db.weights.filter(w => w.stage === 'HANDOVER_JOINT' && w.mode === 'PALLET_COMBINED' && w.status === 'EFFECTIVE')
      H.eq(palletRecords.length, 1, '合托路：仅 1 次有效交接称量')
      H.eq(db.weightBasis, 'PALLET_COMBINED', '重量基准 PALLET_COMBINED')
      H.eq(Number(db.totalRecognizedNetKg), 20, '合托路认可总净重也是 20kg（不会把箱/托重复累计成 40）')
      for (const x of [b1, b2]) {
        const detail = must(await H.U.h.get(`/api/boxes/${x.code}`), 'B箱')
        H.eq(detail.currentRound.weights.filter(w => w.stage === 'HANDOVER_JOINT' && w.mode === 'BOX_DIRECT').length,
          0, '合托路没有伪造分箱复称证据：' + x.code)
      }
    }
  },

  {
    id: 'container-lifecycle',
    title: '空箱取回只进待清洁区；清洁后新轮次生效，旧轮 QR 不能领新袋',
    async run(H) {
      const b = await signedSingleBox(H, 'L1')
      const ret = must(await H.U.t1.post(`/api/manifests/${b.manifestCode}/return-empties`, {}), '空箱取回')
      H.check(ret.boxCodes.includes(b.code), '回送记录包含该箱')
      let detail = must(await H.U.h.get(`/api/boxes/${b.code}`), '取回后箱')
      H.eq(detail.status, 'PENDING_CLEANING', '空箱状态=待清洁区，而非可直接复用')

      const startEarly = await H.U.h.post(`/api/boxes/${b.code}/rounds`, { actualTareKg: 2 })
      H.eq(startEarly.status, 409, '待清洁箱直接开新轮被拒绝')
      H.eq(startEarly.data?.code, 'BOX_PENDING_CLEANING', '错误码 BOX_PENDING_CLEANING')

      const cleaned = must(await H.U.h.post(`/api/boxes/${b.code}/clean`,
        { newTareKg: 2.1, deviceCode: 'SC-H1' }), '清洁确认')
      H.eq(cleaned.status, 'AVAILABLE_CLEAN', '清洁并称重新皮重后才恢复可用')

      const r2 = must(await H.U.h.post(`/api/boxes/${b.code}/rounds`, { actualTareKg: 2.1 }), '新轮次')
      H.eq(r2.roundNo, 2, '新封装轮次=2，QR 为 ' + r2.qrCode)

      const newSeal = `SN-${H.R}`
      must(await H.U.h.post('/api/bags', { sealCode: newSeal }), '新袋')
      const oldQr = await H.U.h.post('/api/scan/claim-bag',
        { boxQr: `${b.code}#R1`, sealCode: newSeal })
      H.eq(oldQr.status, 409, '用旧轮次 QR 领新袋被拒绝')
      H.eq(oldQr.data?.code, 'OLD_ROUND_QR', '错误码 OLD_ROUND_QR')

      const newQr = await H.U.h.post('/api/scan/claim-bag',
        { boxQr: `${b.code}#R2`, sealCode: newSeal })
      H.check(newQr.ok, '用本轮 QR（' + b.code + '#R2）可正常领取新袋', newQr.data?.message ?? '')
    }
  }
]

// ---------- 运行入口 ----------

export async function runVerification(options = {}) {
  const base = options.baseUrl ?? ''
  const startedAt = new Date().toISOString()

  const reset = await raw(base, 'POST', '/api/dev/reset', {})
  if (!reset.ok) throw new Error('重置基线数据失败：' + reset.status)

  const tokens = {}
  for (const a of DEMO_ACCOUNTS) {
    tokens[a.username] = await (async () => {
      const r = await raw(base, 'POST', '/api/auth/login',
        { username: a.username, password: a.password })
      if (!r.ok) throw new Error('登录失败 ' + a.username)
      return r.data.token
    })()
  }
  const U = {
    h: client(base, tokens.hospital01),
    h2: client(base, tokens.hospital02),
    t1: client(base, tokens.transport01),
    t2: client(base, tokens.transport02),
    p: client(base, tokens.plant01),
    admin: client(base, tokens.admin),
    pub: client(base, null)
  }
  const R = Date.now().toString(36).toUpperCase() +
    Math.floor(Math.random() * 46656).toString(36).toUpperCase().padStart(3, '0')

  const scenarios = []
  for (const sc of SCENARIOS) {
    const checks = []
    const H = {
      base, R, U, checks,
      check: (cond, name, detail) => checks.push({ name, ok: !!cond, detail: detail ?? '' }),
      eq: (actual, expected, name) => checks.push({
        name,
        ok: actual === expected,
        detail: actual === expected ? '' : `期望 ${fmt(expected)}；实际 ${fmt(actual)}`
      }),
      deepEq: (actual, expected, name) => {
        const ok = JSON.stringify(actual) === JSON.stringify(expected)
        checks.push({ name, ok, detail: ok ? '' : `期望 ${fmt(expected)}；实际 ${fmt(actual)}` })
      }
    }
    try {
      await sc.run(H)
    } catch (e) {
      checks.push({ name: '场景执行未中断', ok: false, detail: String(e?.stack || e) })
    }
    scenarios.push({
      id: sc.id,
      title: sc.title,
      pass: checks.length > 0 && checks.every(c => c.ok),
      checks
    })
  }

  const passed = scenarios.filter(s => s.pass).length
  const checkTotal = scenarios.reduce((n, s) => n + s.checks.length, 0)
  const checkPassed = scenarios.reduce((n, s) => n + s.checks.filter(c => c.ok).length, 0)
  return {
    startedAt,
    finishedAt: new Date().toISOString(),
    runSuffix: R,
    summary: {
      scenarios: scenarios.length,
      passed,
      failed: scenarios.length - passed,
      checkTotal,
      checkPassed,
      checkFailed: checkTotal - checkPassed
    },
    scenarios
  }
}
