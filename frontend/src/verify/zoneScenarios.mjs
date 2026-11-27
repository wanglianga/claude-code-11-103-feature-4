// feature-4 跨区人员任务与防护用品 · 端到端验收脚本（纯 ESM、零依赖）。
// 同一份模块供：Node 命令行 verify/run-zone.mjs 与 Vue「跨区派发」页内验收按钮调用。
// 每个场景开始都 POST /api/zone/reset，只重建 zone_* 虚构基线，可重复执行。

export const ZONE_ACCOUNTS = [
  { username: 'w1', password: '123456', label: '跨区员 W1·卫一' },
  { username: 'w2', password: '123456', label: '跨区员 W2·卫二' },
  { username: 'rv01', password: '123456', label: '独立复核员·周正' },
  { username: 'admin', password: 'admin123', label: '管理员/主管' }
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

function must(r, what) {
  if (!r || !r.ok) {
    throw new Error(`${what}失败：HTTP ${r?.status} ${r?.data?.code ?? ''} ${r?.data?.message ?? ''}`)
  }
  return r.data
}

// 走完污染轮次的标准动作，便于各场景复用。
const NORMAL_STEPS = ['DOFF_OUTER', 'HAND_HYGIENE', 'PASSAGE', 'DOFF_INNER']

async function fillCard(Z, rc, { pass = true, at } = {}) {
  for (const stepCode of NORMAL_STEPS) {
    must(await Z.admin.post(`/zone/rounds/${rc}/step`, { stepCode, ...(at ? { at } : {}) }), '流程步骤 ' + stepCode)
  }
  return Z.admin.post(`/zone/rounds/${rc}/step`, { stepCode: 'INSPECTION', passed: pass, ...(at ? { at } : {}) })
}

// W1 完成 A→P 并进入污染区（返回 roundCode），供“转换/复核”类场景起步。
async function w1EnterP(Z, { enterAt = '10:10' } = {}) {
  must(await Z.admin.post('/zone/tasks/A/assign', { staffCode: 'W1', startAt: '10:00' }), '派 A')
  must(await Z.admin.post('/zone/tasks/A/start', { at: '10:00' }), '开始 A')
  must(await Z.admin.post('/zone/tasks/A/complete', { at: '10:10' }), '完成 A')
  must(await Z.admin.post('/zone/tasks/P/assign', { staffCode: 'W1', startAt: '10:10' }), '派 P')
  const entered = must(await Z.admin.post('/zone/tasks/P/start', { at: enterAt }), '进入污染区')
  must(await Z.admin.post('/zone/tasks/P/complete', { at: '10:25' }), '完成 P 交接')
  return entered.round.code
}

const SCENARIOS = [
  {
    id: 'dispatch-plans',
    title: '题例两路派发：分区 W1+W2 使 C 10:25-10:35；复用 W1 转换 10 分钟使 C 10:35-10:45，均在 11:00 前',
    async run(H) {
      await H.reset()
      const p = must(await H.U.admin.get('/zone/dispatch-plans'), '两路方案')
      H.eq(p.baseHHmm, '10:00', '基准时刻 10:00')
      H.eq(p.deadlineHHmm, '11:00', 'C 截止 11:00')
      H.eq(p.transitionMin, 10, '题例退出流程+独立复核共 10 分钟')

      const f = p.fixedZones
      H.eq(f.staffCount, 2, '固定分区占用 2 人（W1 净 / W2 污）')
      H.eq(f.transitionMin, 0, 'W1 不进污染区，转换耗时 0')
      H.eq(f.cStartHHmm, '10:25', '分区：W1 等 P 完成后 10:25 开始 C')
      H.eq(f.cEndHHmm, '10:35', '分区：C 10:35 完成')
      H.eq(f.meetsDeadline, true, '分区方案早于 11:00 截止')
      const fseg = Object.fromEntries(f.segments.map(s => [`${s.staffCode}-${s.taskCode}`, s]))
      H.eq(fseg['W2-P'].startHHmm, '10:10', 'W2 的 P 为 10:10-10:25')
      H.eq(fseg['W2-P'].endHHmm, '10:25', 'W2 P 结束 10:25')

      const r = p.reuseOne
      H.eq(r.staffCount, 1, '跨区复用仅需 W1 一人连做（W2 备用）')
      H.eq(r.transitionMin, 10, '复用方案含 10 分钟转换')
      H.eq(r.cStartHHmm, '10:35', '复用：10:25-10:35 转换后 C 才开始')
      H.eq(r.cEndHHmm, '10:45', '复用：C 10:45 完成（比分区多等 10 分钟）')
      H.eq(r.meetsDeadline, true, '复用方案也在 11:00 前完成')
      const rseg = Object.fromEntries(r.segments.map(s => [s.taskCode, s]))
      H.eq(rseg.T.startHHmm, '10:25', '转换段 10:25 开始')
      H.eq(rseg.T.endHHmm, '10:35', '转换段 10:35 结束')
    }
  },

  {
    id: 'fixed-zones-happy',
    title: '固定分区实跑：W1 做 A/C 始终净区，W2 做 P 进污染区后被限制，库存实耗、责任与清单联动',
    async run(H) {
      await H.reset()
      must(await H.U.admin.post('/zone/tasks/A/assign', { staffCode: 'W1', startAt: '10:00' }), '派 A')
      must(await H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W2', startAt: '10:10' }), '派 P')

      // 前置依赖：A 未完成不能开始 P
      const earlyP = await H.U.admin.post('/zone/tasks/P/start', { at: '10:10' })
      H.eq(earlyP.status, 409, 'A 未完成时开始 P 被拒绝（409）')
      H.eq(earlyP.data?.code, 'TASK_DEPENDENCY_NOT_DONE', '错误码 TASK_DEPENDENCY_NOT_DONE')

      must(await H.U.admin.post('/zone/tasks/A/start', { at: '10:00' }), 'A 开始')
      must(await H.U.admin.post('/zone/tasks/A/complete', { at: '10:10' }), 'A 完成')
      const enter = must(await H.U.admin.post('/zone/tasks/P/start', { at: '10:10' }), 'W2 进入污染区')
      H.eq(enter.staff.cleanEligible, false, 'W2 进入污染区后立即失去净区资格（尽管岗位资质仍有效）')
      H.eq(enter.staff.actualZone, 'DIRTY', 'W2 实际区域=污染区')
      H.eq(enter.round.status, 'IN_DIRTY', '产生 IN_DIRTY 本轮状态')
      must(await H.U.admin.post('/zone/tasks/P/complete', { at: '10:25' }), 'P 完成')

      // W1 一直在净区，可在 10:25 接 C
      must(await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W1', startAt: '10:25' }), '派 C 给 W1')
      must(await H.U.admin.post('/zone/tasks/C/start', { at: '10:25' }), 'C 开始')
      const cdone = must(await H.U.admin.post('/zone/tasks/C/complete', { at: '10:35' }), 'C 完成')
      H.eq(cdone.task.status, 'DONE', 'C DONE')
      H.eq(cdone.task.completedAtMin, 635, 'C 实际完成时刻=10:35（635）')

      const b = must(await H.U.admin.get('/zone/board'), '总览板')
      const w1 = b.staff.find(s => s.code === 'W1')
      const w2 = b.staff.find(s => s.code === 'W2')
      H.eq(w1.cleanEligible, true, 'W1 未进污染区，始终可接触净区物品')
      H.eq(w2.cleanEligible, false, 'W2 做完 P 仍处污染限制（固定污染岗也不自动净区）')
      H.check(!!w2.activeRound && w2.activeRound.status === 'IN_DIRTY', '板上 W2 挂着未结清污染轮次')
      const face = b.skus.find(x => x.skuCode === 'PPE-FACE')
      H.eq(face.onHand, 7, '进入污染区实耗 1 个面屏：现货 8→7')
      H.check(b.reservations.some(r => r.taskCode === 'P' && r.status === 'CONSUMED'),
        'P 的防护预留单为 CONSUMED')
      H.check(b.nextActions.some(a => a.path.includes('/exit') && a.enabled),
        '现场可继续办理清单给出 W2 退出污染区动作')
    }
  },

  {
    id: 'skip-transition-blocked',
    title: 'W1 跳过转换领取 C 全程被拦：在污染区/流程未完/待复核各有精确原因，独立复核后 10:45 完成',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)

      let claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '尚在污染区时领取 C → 409')
      H.eq(claim.data?.code, 'STAFF_IN_DIRTY_ZONE', '错误码 STAFF_IN_DIRTY_ZONE')
      H.eq(claim.data?.cleanEligible, false, '错误体明确 cleanEligible=false')

      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '退出但未办流程卡，领取 C 仍 409')
      H.eq(claim.data?.code, 'ZONE_TRANSITION_INCOMPLETE', '错误码 ZONE_TRANSITION_INCOMPLETE（不能跳过转换）')

      // 只办四个普通步骤、检查还没判：仍不能领
      for (const sc of NORMAL_STEPS) {
        must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: sc }), sc)
      }
      claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '检查未判前领取 C 仍被拒')

      must(await fillCard(H.U, rc, { pass: true }), '检查合格')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '办结流程卡')
      claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '流程完成但未独立复核，领取 C → 409')
      H.eq(claim.data?.code, 'INDEPENDENT_REVIEW_REQUIRED', '错误码 INDEPENDENT_REVIEW_REQUIRED')

      // 当事人本人 token 自核必须被拒
      const selfReview = await H.U.w1.post(`/zone/rounds/${rc}/review`, { decision: 'PASS' })
      H.eq(selfReview.status, 409, 'W1 本人独立复核自己 → 409')
      H.eq(selfReview.data?.code, 'REVIEW_NOT_INDEPENDENT', '错误码 REVIEW_NOT_INDEPENDENT')

      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35', note: '独立复核通过' }), 'RV 复核')
      H.eq(rv.cleared, true, '三闸门齐备，本轮 CLEARED')
      H.eq(rv.round.clearedMin, 635, '结清时刻 10:35，转换恰好 10 分钟')
      H.eq(rv.staff.cleanEligible, true, 'W1 恢复净区资格')

      must(await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W1', startAt: '10:35' }), '10:35 派 C')
      must(await H.U.admin.post('/zone/tasks/C/start', { at: '10:35' }), 'C 开始')
      const done = must(await H.U.admin.post('/zone/tasks/C/complete', { at: '10:45' }), 'C 完成')
      H.eq(done.task.completedAtMin, 645, '复用 W1：C 10:45 完成，仍早于 11:00')
      H.eq(done.task.endMin, 645, '锁定段终点也是 10:45')
    }
  },

  {
    id: 'clean-task-stolen-no-reclaim',
    title: '转换期间 C 被 W2 接走：W1 复核完成后也不能自动抢回，任务责任始终是 W2',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      must(await fillCard(H.U, rc, { pass: true }), '流程卡')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '待复核（W1 仍受限）')

      // W1 转换中、尚无净区资格时，净区 C 可被干净且有资格的 W2 接走
      const assignW2 = await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W2', startAt: '10:35' })
      H.check(assignW2.ok, 'W2 净区资格有效，可接走 C', assignW2.data?.message)
      must(assignW2, 'W2 接 C')
      must(await H.U.admin.post('/zone/tasks/C/start', { at: '10:35' }), 'W2 开始 C')

      // W1 此刻才复核完成、恢复资格
      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), 'W1 复核通过')
      H.eq(rv.staff.cleanEligible, true, 'W1 已恢复净区资格')

      const c = must(await H.U.admin.get('/zone/tasks'), '任务列表').find(t => t.code === 'C')
      H.eq(c.assigneeCode, 'W2', 'C 的责任人仍是 W2，没有被自动改派给 W1')
      H.eq(c.status, 'IN_PROGRESS', 'C 仍由 W2 进行中')

      const snatch = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(snatch.status, 409, 'W1 想补领已被接走的 C → 409')
      H.eq(snatch.data?.code, 'TASK_ALREADY_CLAIMED', '错误码 TASK_ALREADY_CLAIMED')
      const c2 = (await H.U.admin.get('/zone/tasks')).data.find(t => t.code === 'C')
      H.eq(c2.assigneeCode, 'W2', '争抢失败后责任人依旧是 W2（不会自动抢回）')
    }
  },

  {
    id: 'same-staff-concurrent-zones',
    title: '同人两区/重叠时段并发接单：行锁裁决恰好一单成功，失败方不留段、不留预留',
    async run(H) {
      await H.reset()
      const [ra, rp] = await Promise.all([
        H.U.admin.post('/zone/tasks/A/assign', { staffCode: 'W1', startAt: '10:00' }), // 10:00-10:10
        H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W1', startAt: '10:05' })  // 10:05-10:20 重叠
      ])
      const oks = [ra, rp].filter(r => r.ok)
      const bad = [ra, rp].find(r => !r.ok)
      H.eq(oks.length, 1, '并发两单恰好一单确认成功')
      H.eq(bad?.status, 409, '失败方返回 409')
      H.eq(bad?.data?.code, 'STAFF_SEGMENT_CONFLICT', '错误码 STAFF_SEGMENT_CONFLICT', bad?.data?.code)

      const b = must(await H.U.admin.get('/zone/board'), '板')
      const active = b.tasks.filter(t => t.assigneeCode === 'W1' && ['CONFIRMED', 'IN_PROGRESS'].includes(t.status))
      H.eq(active.length, 1, 'W1 名下只有一个有效锁定段')
      // 任意两个有效段不得时间重叠
      let overlap = false
      for (let i = 0; i < active.length; i++) {
        for (let j = i + 1; j < active.length; j++) {
          if (active[i].startMin < active[j].endMin && active[i].endMin > active[j].startMin) overlap = true
        }
      }
      H.eq(overlap, false, '不存在重叠的人员可用段')
      // 预留一致性（固定为一条断言，避免数量随并发获胜方变化而令总数漂移）
      const held = b.reservations.filter(x => x.status === 'HELD')
      H.check(
        held.every(r => b.tasks.some(t => t.code === r.taskCode && t.status === 'CONFIRMED')) &&
        b.tasks.filter(t => t.status === 'CONFIRMED' && t.kitCode)
          .every(t => held.some(r => r.taskCode === t.code)),
        'HELD 预留均属于已确认任务，且已确认污染任务都有对应 HELD 预留（当前 HELD ' + held.length + ' 条）'
      )
    }
  },

  {
    id: 'inspection-failed',
    title: '检查未过：流程卡不能提交、净区资格保持；返工复检合格并复核后才恢复',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      for (const sc of NORMAL_STEPS) {
        must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: sc }), sc)
      }
      const fail = await H.U.admin.post(`/zone/rounds/${rc}/step`,
        { stepCode: 'INSPECTION', passed: false, note: '模拟检出问题' })
      H.eq(fail.data.round.status, 'CHECK_FAILED', '检查判负 → CHECK_FAILED')
      H.eq(fail.data.staff.cleanEligible, false, '检查失败后 W1 仍无净区资格')

      const card = await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {})
      H.eq(card.status, 409, '检查未过提交流程卡 → 409')
      H.eq(card.data?.code, 'PROCESS_CHECK_FAILED', '错误码 PROCESS_CHECK_FAILED')

      const claim = await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W1', startAt: '10:35' })
      H.eq(claim.status, 409, '检查失败期间不能派净区 C')
      H.eq(claim.data?.code, 'PROCESS_CHECK_FAILED', '派发同样给出 PROCESS_CHECK_FAILED')

      // 返工：重新检查合格 → 提交 → 独立复核通过
      must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: 'INSPECTION', passed: true }), '复检合格')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '重新提交')
      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), '复核')
      H.eq(rv.cleared, true, '返工+复核后恢复净区资格')
      must(await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W1', startAt: '10:35' }), '此时可派 C')
    }
  },

  {
    id: 'review-failed',
    title: '独立复核未过：保持限制且错误码独立；返工重提、再次复核通过才结清',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      must(await fillCard(H.U, rc, { pass: true }), '流程卡合格')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '待复核')

      const bad = await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'FAIL', reviewerCode: 'RV', note: '复核发现脱防护不规范' })
      H.eq(bad.data.round.status, 'REVIEW_FAILED', '复核失败 → REVIEW_FAILED')
      H.eq(bad.data.staff.cleanEligible, false, '复核失败仍无净区资格')

      const claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '复核失败期间领取 C → 409')
      H.eq(claim.data?.code, 'INDEPENDENT_REVIEW_FAILED', '错误码 INDEPENDENT_REVIEW_FAILED')

      // 返工并重提流程卡（复核结论被重置为待复核），再次独立复核通过
      must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: 'INSPECTION', passed: true }), '返工复检')
      const resubmit = must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '重提')
      H.eq(resubmit.round.reviewStatus, 'PENDING', '重提后复核结论重置为 PENDING，需要新一轮独立复核')
      const ok = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), '二次复核')
      H.eq(ok.cleared, true, '二次复核通过后结清')
    }
  },

  {
    id: 'process-interrupted',
    title: '流程中断：中断步骤未恢复前不能提交流程卡、净区资格保持；恢复后可继续',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: 'DOFF_OUTER' }), '第一步')
      const inter = await H.U.admin.post(`/zone/rounds/${rc}/step`,
        { stepCode: 'HAND_HYGIENE', interrupted: true, reason: '模拟现场被呼叫打断' })
      H.eq(inter.data.round.status, 'INTERRUPTED', '步骤中断 → INTERRUPTED')
      H.eq(inter.data.staff.cleanEligible, false, '中断期间净区资格保持限制')

      const card = await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {})
      H.eq(card.status, 409, '存在中断步骤时提交流程卡 → 409')
      H.eq(card.data?.code, 'PROCESS_INTERRUPTED', '错误码 PROCESS_INTERRUPTED')
      const claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.data?.code, 'PROCESS_INTERRUPTED', '领取 C 同样被中断原因拦截')

      // 恢复并完成该步，继续其余步骤
      must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: 'HAND_HYGIENE' }), '恢复手卫生')
      for (const sc of ['PASSAGE', 'DOFF_INNER']) {
        must(await H.U.admin.post(`/zone/rounds/${rc}/step`, { stepCode: sc }), sc)
      }
      must(await fillCard(H.U, rc, { pass: true }), '检查合格')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '提交')
      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), '复核')
      H.eq(rv.cleared, true, '从中断恢复并走完全流程后结清')
    }
  },

  {
    id: 'late-exit-receipt',
    title: '退出回执迟到/未知：即使流程与复核已过也不恢复；迟到回执登记后才结清',
    async run(H) {
      await H.reset()
      const rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: false }),
        '退出但回执未知')
      must(await fillCard(H.U, rc, { pass: true }), '流程卡')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '提交流程卡')
      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:33' }), '复核先通过')
      H.eq(rv.cleared, false, '关键回执未知时，复核通过也不结清')
      H.eq(rv.round.status, 'AWAIT_RECEIPT', '轮次进入 AWAIT_RECEIPT')
      H.eq(rv.staff.cleanEligible, false, 'W1 仍无净区资格')

      const claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.status, 409, '回执未知时领取 C → 409')
      H.eq(claim.data?.code, 'EXIT_RECEIPT_UNKNOWN', '错误码 EXIT_RECEIPT_UNKNOWN')

      const rcpt = must(await H.U.admin.post(`/zone/rounds/${rc}/exit-receipt`,
        { receiptCode: 'RC-LATE-0001', at: '10:35' }), '迟到回执到达')
      H.eq(rcpt.late, true, '该回执被标记为迟到')
      H.eq(rcpt.cleared, true, '回执齐备后本轮结清（无需推翻已有复核）')
      H.eq(rcpt.round.exitReceiptCode, 'RC-LATE-0001', '登记迟到回执号')
      must(await H.U.admin.post('/zone/tasks/C/assign', { staffCode: 'W1', startAt: '10:35' }), '此时可派 C')
    }
  },

  {
    id: 'ppe-shortage-blocks',
    title: '防护用品缺件：缺任一件整体阻止派发，且不留任务责任/预留；补货后可派',
    async run(H) {
      await H.reset()
      let b = must(await H.U.admin.get('/zone/board'), '板')
      const face = b.skus.find(s => s.skuCode === 'PPE-FACE')
      must(await H.U.admin.post('/zone/ppe/stock/PPE-FACE/adjust', { delta: -face.onHand }), '把面屏调为 0')

      const no = await H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W1', startAt: '10:10' })
      H.eq(no.status, 409, '缺件时派发 P → 409')
      H.eq(no.data?.code, 'PPE_SHORTAGE', '错误码 PPE_SHORTAGE')
      H.check(Array.isArray(no.data?.missing) && no.data.missing.some(m => m.skuCode === 'PPE-FACE' && m.short >= 1),
        '错误体 missing 明确列出缺件 PPE-FACE 与缺口', JSON.stringify(no.data?.missing))

      b = must(await H.U.admin.get('/zone/board'), '板2')
      const p = b.tasks.find(t => t.code === 'P')
      H.eq(p.status, 'OPEN', '缺件被拒后 P 仍 OPEN（没有锁成责任）')
      H.eq(p.assigneeCode ?? null, null, 'P 没有责任人')
      H.check(!b.reservations.some(r => r.taskCode === 'P' && r.status === 'HELD'),
        '缺件整体回滚，没有留下 P 的 HELD 预留')

      must(await H.U.admin.post('/zone/ppe/stock/PPE-FACE/restock', { qty: 5 }), '补 5 个面屏')
      const ok = await H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W1', startAt: '10:10' })
      H.check(ok.ok, '补货后派发成功', ok.data?.message)
    }
  },

  {
    id: 'cancel-releases-unused-ppe',
    title: '未进区即取消：锁定的人员段释放、HELD 用品退回可用库存',
    async run(H) {
      await H.reset()
      const before = must(await H.U.admin.get('/zone/board'), '板前')
      const face0 = before.skus.find(s => s.skuCode === 'PPE-FACE').available

      must(await H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W2', startAt: '10:10' }), '派 P 给 W2')
      let b = must(await H.U.admin.get('/zone/board'), '板锁')
      H.eq(b.skus.find(s => s.skuCode === 'PPE-FACE').available, face0 - 1, '确认后可用量 -1（HELD）')

      const cancel = must(await H.U.admin.post('/zone/tasks/P/cancel', { reason: '主管改派' }), '取消 P')
      H.check(/释放/.test(cancel.ppeEffect || ''), '返回说明：未进入，预留释放')
      b = must(await H.U.admin.get('/zone/board'), '板消')
      H.eq(b.skus.find(s => s.skuCode === 'PPE-FACE').available, face0, '取消后 HELD 释放，可用量恢复')
      H.check(b.reservations.some(r => r.taskCode === 'P' && r.status === 'RELEASED'), '预留单标记 RELEASED 留痕')
      const w2 = b.staff.find(s => s.code === 'W2')
      H.eq(w2.segments.length, 0, '取消后 W2 该锁定段不再占用')
    }
  },

  {
    id: 'cancel-keeps-consumed-and-entry',
    title: '进入污染区后主管取消：实耗用品不退未用、进区轮次与限制不抹除；仍可走完转换复核',
    async run(H) {
      await H.reset()
      // 进入 P 污染区但先不完成工单：主管在进行中取消
      must(await H.U.admin.post('/zone/tasks/A/assign', { staffCode: 'W1', startAt: '10:00' }), '派 A')
      must(await H.U.admin.post('/zone/tasks/A/start', { at: '10:00' }), '开始 A')
      must(await H.U.admin.post('/zone/tasks/A/complete', { at: '10:10' }), '完成 A')
      must(await H.U.admin.post('/zone/tasks/P/assign', { staffCode: 'W1', startAt: '10:10' }), '派 P')
      const entered = must(await H.U.admin.post('/zone/tasks/P/start', { at: '10:10' }), '进入污染区')
      const rc = entered.round.code
      let b = must(await H.U.admin.get('/zone/board'), '板进')
      const faceAfterEnter = b.skus.find(s => s.skuCode === 'PPE-FACE').onHand

      const cancel = must(await H.U.admin.post('/zone/tasks/P/cancel', { reason: '主管取消该交接单' }), '取消 P')
      H.eq(cancel.zoneEnteredRecordKept, true, '响应明确：实际进区记录保留')
      H.check(/不退回|实[际耗]/.test(cancel.ppeEffect || ''), '响应说明已消耗用品不退回：' + cancel.ppeEffect)

      b = must(await H.U.admin.get('/zone/board'), '板取消')
      H.eq(b.skus.find(s => s.skuCode === 'PPE-FACE').onHand, faceAfterEnter,
        '取消后现货不回弹（实耗不退回未用）')
      H.check(b.reservations.some(r => r.taskCode === 'P' && r.status === 'CONSUMED'),
        '预留单仍为 CONSUMED，不改成 RELEASED')
      const w1 = b.staff.find(s => s.code === 'W1')
      H.eq(w1.cleanEligible, false, '取消工单不解除进区带来的净区限制')
      H.check(!!b.rounds.find(r => r.code === rc && r.entryMin != null), '进区轮次仍在且有进入时刻')
      H.check(b.events.some(e => e.action === 'ZONE_ENTERED'), '只追加事件中 ZONE_ENTERED 仍保留')
      H.check(b.events.some(e => e.action === 'TASK_CANCELLED'), '同时留有 TASK_CANCELLED 事件')
      H.check(b.nextActions.some(a => a.path.includes('/exit')), '可继续办理清单仍提示为 W1 办退出/转换')

      // 取消后仍须且仍能完成安全转换与独立复核
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出')
      must(await fillCard(H.U, rc, { pass: true }), '流程卡')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '提交')
      const rv = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), '复核')
      H.eq(rv.cleared, true, '取消工单不影响依法走完本轮转换后恢复资格')
    }
  },

  {
    id: 'new-entry-overrides-old-clear',
    title: '上一轮清洁/复核不能覆盖新进入污染区：W1 已 CLEARED 后再次进区，立即重新受限（第 2 轮）',
    async run(H) {
      await H.reset()
      let rc = await w1EnterP(H.U)
      must(await H.U.admin.post(`/zone/rounds/${rc}/exit`, { at: '10:25', receiptKnown: true }), '退出1')
      must(await fillCard(H.U, rc, { pass: true }), '卡1')
      must(await H.U.admin.post(`/zone/rounds/${rc}/complete-card`, {}), '提交1')
      const rv1 = must(await H.U.admin.post(`/zone/rounds/${rc}/review`,
        { decision: 'PASS', reviewerCode: 'RV', at: '10:35' }), '复核1')
      H.eq(rv1.staff.cleanEligible, true, '第一轮结清后 W1 净区合格')

      // 新建第二个污染任务并再次进入
      must(await H.U.admin.post('/zone/tasks',
        { code: 'Q2', title: '临时污染区补办交接', zoneReq: 'DIRTY', durationMin: 10 }), '建 Q2')
      must(await H.U.admin.post('/zone/tasks/Q2/assign', { staffCode: 'W1', startAt: '10:40' }), '派 Q2')
      const enter2 = must(await H.U.admin.post('/zone/tasks/Q2/start', { at: '10:40' }), '第二次进区')
      H.eq(enter2.round.roundNo, 2, '生成第 2 轮污染状态')
      H.eq(enter2.staff.cleanEligible, false, '尽管第 1 轮有 CLEARED 清洁记录，新进入立即重新受限')

      const b = must(await H.U.admin.get('/zone/board'), '板')
      const w1 = b.staff.find(s => s.code === 'W1')
      H.eq(w1.activeRound.roundNo, 2, '当前生效的是第 2 轮（最新进入事实）')
      const mine = b.rounds.filter(r => r.staffCode === 'W1').sort((x, y) => x.roundNo - y.roundNo)
      H.eq(mine.length, 2, 'W1 有两轮独立记录')
      H.eq(mine[0].status, 'CLEARED', '第 1 轮仍是历史 CLEARED（不被删改）')
      H.eq(mine[1].status, 'IN_DIRTY', '第 2 轮 IN_DIRTY 覆盖当前资格')
      const claim = await H.U.admin.post('/zone/tasks/C/claim', { staffCode: 'W1' })
      H.eq(claim.data?.code, 'STAFF_IN_DIRTY_ZONE', '净区领取按最新第 2 轮拦截')
    }
  }
]

// ---------- 运行入口（与 scenarios.mjs 同形） ----------

export async function runZoneVerification(options = {}) {
  const base = options.baseUrl ?? ''
  const startedAt = new Date().toISOString()

  async function login(username, password) {
    const r = await raw(base, 'POST', '/api/auth/login', { username, password })
    if (!r.ok) throw new Error('登录失败 ' + username + ' ' + r.status)
    return r.data.token
  }
  const [adminTok, w1Tok] = await Promise.all([
    login('admin', 'admin123'),
    login('w1', '123456')
  ])
  const mk = (token) => ({
    raw: (m, p, b) => raw(base, m, p, b, token),
    get: (p) => raw(base, 'GET', '/api' + p, undefined, token),
    post: (p, b) => raw(base, 'POST', '/api' + p, b === undefined ? {} : b, token)
  })
  const U = { admin: mk(adminTok), w1: mk(w1Tok) }

  const scenarios = []
  for (const sc of SCENARIOS) {
    const checks = []
    const H = {
      U,
      reset: async () => must(await U.admin.post('/zone/reset', {}), '跨区基线重置'),
      check: (cond, name, detail) => checks.push({ name, ok: !!cond, detail: detail ?? '' }),
      eq: (actual, expected, name, detailExtra) => {
        const ok = actual === expected
        checks.push({
          name, ok,
          detail: ok ? '' : `期望 ${fmt(expected)}；实际 ${fmt(actual)}` + (detailExtra ? `（${detailExtra}）` : '')
        })
      },
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
    suite: 'zone-cross-ppe',
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

function fmt(v) {
  if (typeof v === 'number') return String(v)
  try { return JSON.stringify(v) } catch { return String(v) }
}
