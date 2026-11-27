<template>
  <div class="page zone-page" v-loading="loading">
    <div class="page-title">人员跨区任务 · 污染状态与防护用品派发</div>
    <div class="page-sub">
      收运人员从随题虚构的<b>污染区</b>回来后，岗位资质仍有效 ≠ 可以接触净区物品。
      系统把<b>岗位能力、实际所在区域、当前污染轮次、本次防护用品</b>分开记录；每进一次污染区即产生本轮状态，
      退出后必须完成<b>本轮流程卡（含检查）+ 独立复核 + 关键退出回执</b>才恢复净区资格。全部区域/流程均为随题虚构。
    </div>

    <!-- 顶部：模拟时钟 + 基线 + 验收 -->
    <el-card shadow="never" class="bar-card">
      <div class="bar">
        <div class="clock-box">
          <el-tag type="danger" effect="dark" size="large">模拟时刻 {{ board?.nowHHmm || '--:--' }}</el-tag>
          <span class="muted">转换（退出流程+独立复核）约 {{ board?.transitionMin ?? 10 }} 分钟</span>
          <el-button-group>
            <el-button size="small" @click="advance(5)">+5 分</el-button>
            <el-button size="small" @click="advance(10)">+10 分</el-button>
            <el-button size="small" @click="advance(15)">+15 分</el-button>
          </el-button-group>
          <el-input v-model="clockInput" size="small" style="width: 96px" placeholder="HH:mm" />
          <el-button size="small" @click="setClock">设时刻</el-button>
        </div>
        <div class="bar-right">
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="warning" plain @click="resetBaseline">重置跨区基线</el-button>
          <el-button size="small" type="primary" :loading="running" @click="runAcceptance">
            <el-icon><VideoPlay /></el-icon>&nbsp;运行跨区验收（13 场景）
          </el-button>
        </div>
      </div>
      <el-progress v-if="running" :percentage="100" :indeterminate="true" :duration="2" style="margin-top:10px" />
      <div v-if="result" style="margin-top:10px">
        <el-tag size="large" :type="result.summary.failed===0?'success':'danger'">
          跨区场景 {{ result.summary.passed }}/{{ result.summary.scenarios }} 通过 ·
          断言 {{ result.summary.checkPassed }}/{{ result.summary.checkTotal }}
        </el-tag>
        <el-button link type="primary" @click="showChecks = !showChecks" style="margin-left:10px">
          {{ showChecks ? '收起明细' : '查看明细' }}
        </el-button>
        <div v-if="showChecks" class="accept-box">
          <div v-for="s in result.scenarios" :key="s.id" class="acc-scenario">
            <div class="acc-title">{{ s.pass ? '✅' : '❌' }} {{ s.title }}
              <span class="mono muted">{{ s.checks.filter(c=>c.ok).length }}/{{ s.checks.length }}</span>
            </div>
            <div v-for="(c,i) in s.checks.filter(c=>!c.ok)" :key="i" class="acc-fail">✗ {{ c.name }}
              <div v-if="c.detail" class="fail-detail">{{ c.detail }}</div>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 两路派发方案 -->
    <el-row :gutter="14" class="section">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="plan-card">
          <template #header>
            <div class="plan-head">
              <b>方案一 · 固定分区</b>
              <el-tag type="success" size="small">{{ plans?.fixedZones?.staffCount }} 人 · C {{ plans?.fixedZones?.cStartHHmm }}–{{ plans?.fixedZones?.cEndHHmm }}</el-tag>
            </div>
          </template>
          <p class="plan-name">{{ plans?.fixedZones?.name }}</p>
          <div class="seg-line">
            <el-tag v-for="(g,i) in plans?.fixedZones?.segments" :key="i" size="small"
                    :type="zoneTag(g.zoneReq)" class="seg-tag">
              {{ g.staffCode }}·{{ g.taskCode }} {{ g.startHHmm }}-{{ g.endHHmm }}
            </el-tag>
          </div>
          <div class="plan-meta">
            转换 <b>0</b> 分钟 · W1 等前置 <b>{{ plans?.fixedZones?.dependencyWaitMin }}</b> 分钟 ·
            C {{ plans?.fixedZones?.cEndHHmm }} 完成
            <el-tag size="small" :type="plans?.fixedZones?.meetsDeadline?'success':'danger'" style="margin-left:6px">
              {{ plans?.fixedZones?.meetsDeadline ? '11:00 前完成 ✓' : '超过 11:00' }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="plan-card">
          <template #header>
            <div class="plan-head">
              <b>方案二 · 同一 W1 跨区复用</b>
              <el-tag type="warning" size="small">{{ plans?.reuseOne?.staffCount }} 人 · C {{ plans?.reuseOne?.cStartHHmm }}–{{ plans?.reuseOne?.cEndHHmm }}</el-tag>
            </div>
          </template>
          <p class="plan-name">{{ plans?.reuseOne?.name }}</p>
          <div class="seg-line">
            <el-tag v-for="(g,i) in plans?.reuseOne?.segments" :key="i" size="small"
                    :type="zoneTag(g.zoneReq)" class="seg-tag" :effect="g.zoneReq==='TRANSITION'?'plain':'dark'">
              {{ g.staffCode }}·{{ g.taskCode }} {{ g.startHHmm }}-{{ g.endHHmm }}
            </el-tag>
          </div>
          <div class="plan-meta">
            退出流程+独立复核 <b>{{ plans?.reuseOne?.transitionMin }}</b> 分钟 ·
            C {{ plans?.reuseOne?.cEndHHmm }} 完成（比固定分区晚 10 分钟）
            <el-tag size="small" :type="plans?.reuseOne?.meetsDeadline?'success':'danger'" style="margin-left:6px">
              {{ plans?.reuseOne?.meetsDeadline ? '仍在 11:00 前 ✓' : '超过 11:00' }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <!-- 可继续办理清单 -->
      <el-col :xs="24" :lg="13">
        <el-card shadow="never" class="section">
          <template #header><b>医院现场 · 可继续办理清单</b>
            <span class="muted" style="margin-left:8px">由人员状态 / 任务责任 / 库存 / 复核实时联动生成</span>
          </template>
          <div class="action-list">
            <div v-for="a in board?.nextActions" :key="a.code" class="action-row" :class="{disabled:!a.enabled}">
              <el-button size="small" :type="a.tone==='primary'?'primary':a.tone" :plain="a.tone==='info'"
                         :disabled="!a.enabled" @click="call(a.path,a.body)">
                {{ a.label }}
              </el-button>
              <span v-if="!a.enabled && a.reason" class="action-reason">{{ a.reason }}</span>
            </div>
            <el-empty v-if="!board?.nextActions?.length" description="当前没有待办动作" :image-size="60" />
          </div>
        </el-card>

        <!-- 任务与派发 -->
        <el-card shadow="never" class="section">
          <template #header><b>任务时段、责任与派工锁定</b>
            <span class="muted" style="margin-left:8px">确认即锁定人员可用段 + 该任务需领防护用品</span>
          </template>
          <div v-for="t in board?.tasks" :key="t.code" class="task-card">
            <div class="task-head">
              <el-tag :type="zoneTag(t.zoneReq)" size="small">{{ zoneLabel(t.zoneReq) }}</el-tag>
              <b class="task-code">{{ t.code }}</b>
              <span class="task-title">{{ t.title }}</span>
              <el-tag size="small" :type="taskStatusTag(t.status)" effect="plain">{{ taskStatusLabel(t.status) }}</el-tag>
              <span class="muted">{{ t.durationMin }} 分钟<span v-if="t.deadlineMin"> · 截止 {{ hhmm(t.deadlineMin) }}</span>
                <span v-if="t.dependsOn?.length"> · 依赖 {{ t.dependsOn.join(',') }}</span></span>
            </div>
            <div class="task-meta">
              <span v-if="t.assigneeCode">责任人 <b>{{ t.assigneeCode }}</b></span>
              <span v-if="t.startMin!=null" class="mono">锁定段 {{ hhmm(t.startMin) }}–{{ hhmm(t.endMin) }}</span>
              <span v-if="t.reservationCode" class="mono muted">用品单 {{ t.reservationCode }}</span>
              <span v-if="t.cancelReason" class="cancel-reason">取消原因：{{ t.cancelReason }}</span>
            </div>
            <div v-if="t.status==='OPEN'" class="task-ops">
              <el-select v-model="form[t.code].staffCode" size="small" style="width:120px">
                <el-option v-for="s in candidates(t)" :key="s.code" :label="`${s.code} ${s.name}`" :value="s.code" />
              </el-select>
              <el-input v-model="form[t.code].startAt" size="small" style="width:110px" placeholder="开始 HH:mm" />
              <el-button size="small" type="primary" @click="assign(t)">确认派发/锁定</el-button>
              <el-button size="small" @click="call(`/zone/tasks/${t.code}/claim`,{staffCode:form[t.code].staffCode})">此刻领取</el-button>
              <span class="muted">净区任务要求该人员当前即具备净区资格</span>
            </div>
            <div v-else-if="t.status==='CONFIRMED'" class="task-ops">
              <el-button size="small" :type="t.zoneReq==='DIRTY'?'warning':'success'" @click="call(`/zone/tasks/${t.code}/start`,{})">
                {{ t.zoneReq==='DIRTY' ? '开始并进入污染区（实耗用品）' : '开始净区任务' }}
              </el-button>
              <el-button size="small" type="danger" plain @click="call(`/zone/tasks/${t.code}/cancel`,{reason:'主管取消'})">主管取消</el-button>
            </div>
            <div v-else-if="t.status==='IN_PROGRESS'" class="task-ops">
              <el-button size="small" type="success" @click="call(`/zone/tasks/${t.code}/complete`,{})">完成任务</el-button>
              <el-button size="small" type="danger" plain @click="call(`/zone/tasks/${t.code}/cancel`,{reason:'主管取消'})">主管取消</el-button>
            </div>
          </div>

          <el-divider>新建临时任务</el-divider>
          <div class="task-ops">
            <el-input v-model="newTask.code" size="small" style="width:90px" placeholder="编号 如 Q2" />
            <el-input v-model="newTask.title" size="small" style="width:200px" placeholder="任务名称" />
            <el-select v-model="newTask.zoneReq" size="small" style="width:120px">
              <el-option label="净区 CLEAN" value="CLEAN" />
              <el-option label="污染区 DIRTY" value="DIRTY" />
            </el-select>
            <el-input-number v-model="newTask.durationMin" size="small" :min="5" :max="120" :step="5" />
            <el-button size="small" type="primary" plain @click="createTask">新建任务</el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 人员状态 -->
      <el-col :xs="24" :lg="11">
        <el-card shadow="never" class="section">
          <template #header><b>人员：资质 / 实际区域 / 污染状态 / 本次用品</b></template>
          <div v-for="s in board?.staff" :key="s.code" class="staff-card" :class="{reviewer:s.canReview}">
            <div class="staff-head">
              <b class="staff-code">{{ s.code }}</b>
              <span class="staff-name">{{ s.name }}</span>
              <el-tag size="small" :type="zoneTag(s.actualZone)">{{ zoneLabel(s.actualZone) }}</el-tag>
              <el-tag size="small" :type="s.cleanEligible?'success':'danger'" effect="dark">
                {{ s.cleanEligible ? '可接触净区物品' : '净区资格受限' }}
              </el-tag>
              <el-tag v-if="s.canReview" size="small" type="warning">独立复核/主管</el-tag>
            </div>
            <div class="staff-line muted">
              岗位资格：{{ (s.quals||[]).join('、') || '—' }}<br />
              当前污染状态：<b :class="s.cleanEligible?'ok-text':'bad-text'">{{ stateLabel(s.contamState) }}</b>
              <template v-if="s.activeRound"> · 本轮 <span class="mono">{{ s.activeRound.code }}</span>（第 {{ s.activeRound.roundNo }} 轮 · {{ roundStatusLabel(s.activeRound.status) }}）</template>
            </div>
            <div v-if="s.segments?.length" class="seg-line" style="margin-top:6px">
              <el-tag v-for="t in s.segments" :key="t.code" size="small" :type="taskStatusTag(t.status)" effect="plain">
                {{ t.code }} {{ hhmm(t.startMin) }}-{{ hhmm(t.endMin) }}
              </el-tag>
            </div>
          </div>
        </el-card>

        <!-- 防护用品库存 -->
        <el-card shadow="never" class="section">
          <template #header><b>防护用品库存与套件</b>
            <span class="muted" style="margin-left:8px">确认=HELD 预留；进区=实耗扣现货；未进取消=释放；已耗不退</span>
          </template>
          <el-table :data="board?.skus" size="small" border>
            <el-table-column prop="skuCode" label="编码" width="110" />
            <el-table-column prop="name" label="用品" min-width="110" />
            <el-table-column label="现货" width="64" prop="onHand" />
            <el-table-column label="预留" width="64" prop="heldQty" />
            <el-table-column label="可用" width="64">
              <template #default="{row}">
                <b :class="row.available < row.safetyStock ? 'bad-text' : 'ok-text'">{{ row.available }}</b>
              </template>
            </el-table-column>
            <el-table-column label="累计实耗" width="80" prop="consumedQty" />
            <el-table-column label="补货/缺件模拟" min-width="190">
              <template #default="{row}">
                <el-input-number v-model="restockQty[row.skuCode]" size="small" :min="1" :max="100" style="width:92px" />
                <el-button size="small" type="primary" link @click="restock(row)">补货</el-button>
                <el-button size="small" type="danger" link @click="zero(row)">调0(缺件)</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-for="k in board?.kits" :key="k.code" class="kit-box">
            <b>{{ k.name }}</b> <span class="mono muted">{{ k.code }}</span>
            <div class="seg-line" style="margin-top:4px">
              <el-tag v-for="l in k.lines" :key="l.skuCode" size="small" type="info" effect="plain">
                {{ l.name }} ×{{ l.qty }}{{ l.unit }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 污染轮次 / 流程卡 / 复核 -->
    <el-card shadow="never" class="section">
      <template #header><b>污染轮次 · 流程卡 · 独立复核 · 退出回执</b>
        <span class="muted" style="margin-left:8px">每进一次污染区一行；取消任务不删行，旧 CLEARED 不覆盖新轮</span>
      </template>
      <el-collapse>
        <el-collapse-item v-for="r in board?.rounds" :key="r.code" :name="r.code">
          <template #title>
            <span class="round-title">
              <el-tag size="small" :type="r.status==='CLEARED'?'success':'danger'">{{ roundStatusLabel(r.status) }}</el-tag>
              <b>{{ r.staffCode }}</b> 第{{ r.roundNo }}轮 <span class="mono muted">{{ r.code }}</span>
              <span class="muted">进入 {{ hhmm(r.entryMin) }}<template v-if="r.exitMin!=null"> · 退出 {{ hhmm(r.exitMin) }}</template><template v-if="r.clearedMin!=null"> · 结清 {{ hhmm(r.clearedMin) }}</template></span>
              <el-tag v-if="r.receiptLate" size="small" type="warning">回执迟到</el-tag>
            </span>
          </template>
          <div class="round-body">
            <div class="seg-line">
              <el-tag v-for="st in r.steps" :key="st.stepCode" size="small"
                      :type="stepTag(st.status, st.passed)" class="step-tag">
                {{ stepIcon(st.status, st.passed) }} {{ st.seq }}.{{ st.label }}
              </el-tag>
            </div>
            <div class="muted" style="margin-top:8px">
              流程卡：{{ r.cardStatus }} · 检查：{{ r.checkStatus }} · 独立复核：{{ r.reviewStatus }}
              <template v-if="r.reviewerCode">（复核人 {{ r.reviewerCode }} / {{ r.reviewerName }} · {{ hhmm(r.reviewedMin) }}）</template><br />
              退出回执：{{ r.exitReceiptStatus }}<template v-if="r.exitReceiptCode"> · {{ r.exitReceiptCode }} {{ r.exitReceiptMin!=null ? hhmm(r.exitReceiptMin) : '' }}</template>
              <template v-if="r.reviewNote"> · 复核意见：{{ r.reviewNote }}</template>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-if="!board?.rounds?.length" description="尚无人员进入污染区" :image-size="70" />
    </el-card>

    <!-- 只追加事件 -->
    <el-card shadow="never" class="section">
      <template #header><b>跨区现场事件（只追加）</b></template>
      <el-timeline>
        <el-timeline-item v-for="e in board?.events?.slice(0,30)" :key="e.id" :timestamp="`${hhmm(e.atMin)} · ${e.actorName||''}`" placement="top">
          <el-tag size="small" effect="plain">{{ e.action }}</el-tag>
          <span style="margin-left:8px;font-size:13px">{{ e.detail }}</span>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api.js'
import { runZoneVerification } from '../verify/zoneScenarios.mjs'

const board = ref(null)
const loading = ref(false)
const clockInput = ref('10:00')
const running = ref(false)
const result = ref(null)
const showChecks = ref(false)
const form = reactive({})
const restockQty = reactive({})
const newTask = reactive({ code: '', title: '', zoneReq: 'DIRTY', durationMin: 10 })

const plans = computed(() => board.value?.dispatchPlans || null)

function hhmm(m) {
  if (m === null || m === undefined) return '—'
  const n = Number(m)
  return `${String(Math.floor(n / 60)).padStart(2, '0')}:${String(n % 60).padStart(2, '0')}`
}
function candidates(t) {
  return (board.value?.staff || []).filter(s => !s.canReview &&
    (s.quals || []).some(q => (t.requiredQuals || []).includes(q)))
}
function initForms(b) {
  for (const t of b.tasks || []) {
    if (t.status === 'OPEN' && !form[t.code]) {
      const c = candidates(t)
      form[t.code] = { staffCode: c[0]?.code || 'W1', startAt: '' }
    }
  }
  for (const s of b.skus || []) {
    if (restockQty[s.skuCode] === undefined) restockQty[s.skuCode] = 5
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.get('/zone/board')
    initForms(board.value)
  } catch (e) {
    ElMessage.error('加载跨区总览失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function call(path, body) {
  try {
    const d = await api.post(path, body || {})
    await load()
    return d
  } catch (e) {
    ElMessage({ type: 'error', duration: 5200, message: (e.code ? `[${e.code}] ` : '') + e.message })
    await load()
    throw e
  }
}

async function resetBaseline() {
  try {
    await api.post('/zone/reset', {})
    ElMessage.success('已重置跨区虚构基线（W1/W2、A/P/C、库存、流程卡）')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}
function advance(min) { call('/zone/clock', { advanceMinutes: min }) }
function setClock() {
  if (!/^\d{1,2}:\d{2}$/.test(clockInput.value)) return ElMessage.warning('时刻格式应为 HH:mm')
  call('/zone/clock', { now: clockInput.value })
}
function assign(t) {
  const f = form[t.code] || { staffCode: 'W1', startAt: '' }
  return call(`/zone/tasks/${t.code}/assign`, {
    staffCode: f.staffCode,
    ...(f.startAt ? { startAt: f.startAt } : {})
  }).then(() => ElMessage.success(`已把 ${t.code} 锁定给 ${f.staffCode}`)).catch(() => {})
}
function restock(row) {
  return call(`/zone/ppe/stock/${row.skuCode}/restock`, { qty: restockQty[row.skuCode] || 5 })
}
function zero(row) {
  return call(`/zone/ppe/stock/${row.skuCode}/adjust`, { delta: -row.onHand, reason: '模拟缺件' })
}
async function createTask() {
  if (!newTask.title) return ElMessage.warning('请填写任务名称')
  try {
    await call('/zone/tasks', {
      code: newTask.code || undefined, title: newTask.title,
      zoneReq: newTask.zoneReq, durationMin: newTask.durationMin
    })
    newTask.code = ''; newTask.title = ''
    ElMessage.success('已新建任务')
  } catch { /* 已提示 */ }
}

async function runAcceptance() {
  running.value = true
  result.value = null
  try {
    result.value = await runZoneVerification({ baseUrl: '' })
    showChecks.value = result.value.summary.failed > 0
    if (result.value.summary.failed === 0) ElMessage.success('跨区 13 场景 / 114 断言全部通过')
    else ElMessage.error('存在失败断言，请展开明细')
    await api.post('/zone/reset', {})
    await load()
  } catch (e) {
    ElMessage.error('验收执行异常：' + e.message)
  } finally {
    running.value = false
  }
}

function zoneTag(z) {
  return ({ CLEAN: 'success', DIRTY: 'danger', TRANSITION: 'warning' })[z] || 'info'
}
function zoneLabel(z) {
  return ({ CLEAN: '净区', DIRTY: '污染区', TRANSITION: '转换中' })[z] || z
}
function taskStatusLabel(s) {
  return ({ OPEN: '待派发', CONFIRMED: '已确认·段/用品已锁', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消' })[s] || s
}
function taskStatusTag(s) {
  return ({ OPEN: 'info', CONFIRMED: 'warning', IN_PROGRESS: 'primary', DONE: 'success', CANCELLED: 'info' })[s] || ''
}
function roundStatusLabel(s) {
  return ({
    IN_DIRTY: '在污染区', EXITED: '已退出·流程未办', CARD_IN_PROGRESS: '流程卡办理中',
    CHECK_FAILED: '检查未过', INTERRUPTED: '流程中断', REVIEW_PENDING: '待独立复核',
    REVIEW_FAILED: '复核未过', AWAIT_RECEIPT: '待退出回执', CLEARED: '已结清·净区合格'
  })[s] || s
}
function stateLabel(s) {
  if (s === 'CLEAN') return '净区合格 / 未受限制'
  return roundStatusLabel(s)
}
function stepTag(status, passed) {
  if (status === 'DONE') return passed === false ? 'danger' : 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'INTERRUPTED') return 'warning'
  return 'info'
}
function stepIcon(status, passed) {
  if (status === 'DONE') return passed === false ? '✗' : '✓'
  if (status === 'FAILED') return '✗'
  if (status === 'INTERRUPTED') return '⏸'
  return '○'
}

onMounted(load)
</script>

<style scoped>
.zone-page { max-width: 1500px; }
.bar-card { margin-bottom: 14px; }
.bar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.clock-box { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.bar-right { display: flex; gap: 8px; flex-wrap: wrap; }
.section { margin-bottom: 14px; }
.plan-card { height: 100%; }
.plan-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.plan-name { font-size: 12.5px; color: #6b7280; margin: 0 0 8px; }
.seg-line { display: flex; flex-wrap: wrap; gap: 6px; }
.seg-tag { font-family: ui-monospace, Menlo, Consolas, monospace; }
.plan-meta { margin-top: 10px; font-size: 13px; color: #374151; }
.action-list { display: flex; flex-direction: column; gap: 8px; }
.action-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 4px 0; border-bottom: 1px dashed #eef0f3; }
.action-row.disabled { opacity: 0.85; }
.action-reason { font-size: 12px; color: #b45309; }
.task-card { border: 1px solid #eef0f3; border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; background: #fcfcfd; }
.task-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.task-code { font-size: 15px; }
.task-title { font-weight: 600; }
.task-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12.5px; color: #4b5563; margin: 6px 0; }
.task-ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px; }
.cancel-reason { color: #b91c1c; }
.staff-card { border: 1px solid #eef0f3; border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
.staff-card.reviewer { background: #fffbeb; }
.staff-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.staff-code { font-size: 15px; }
.staff-name { font-weight: 600; margin-right: 4px; }
.staff-line { font-size: 12.5px; line-height: 1.7; margin-top: 6px; }
.ok-text { color: #047857; }
.bad-text { color: #b91c1c; }
.kit-box { margin-top: 12px; padding-top: 10px; border-top: 1px dashed #e5e7eb; font-size: 13px; }
.round-title { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.round-body { padding: 4px 8px; }
.step-tag { margin: 3px 6px 3px 0; }
.accept-box { margin-top: 10px; max-height: 320px; overflow: auto; background: #0f172a; color: #e2e8f0; border-radius: 8px; padding: 10px 12px; }
.acc-scenario { margin-bottom: 8px; }
.acc-title { font-size: 13px; }
.acc-fail { color: #fca5a5; font-size: 12.5px; margin-left: 18px; }
.fail-detail { color: #fecaca; opacity: .85; white-space: pre-wrap; }
</style>
