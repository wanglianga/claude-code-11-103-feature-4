<template>
  <div class="page">
    <div class="hero">
      <h1>包件保管与差异公开查询</h1>
      <p>输入一次性封口码、箱码（如 C1 或 C1#R1）、交接单号 MD-…、托盘号或委托/回执号，即可查询当前由谁保管、认可重量、未结差异与各角色下一步。无需登录。</p>
      <div style="display:flex;gap:10px;margin-top:16px;max-width:720px">
        <el-input v-model="code" size="large" placeholder="例如 FD-SEAL-0001 / C1 / MD-XXXX" clearable @keyup.enter="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button size="large" type="warning" :loading="loading" @click="search">查询</el-button>
      </div>
      <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
        <span class="muted" style="color:#dbeafe">快速试试：</span>
        <el-button v-for="c in quick" :key="c" size="small" round @click="code=c;search()">{{ c }}</el-button>
        <span class="muted" style="color:#bfdbfe">（先运行「验收自检」可生成完整链路单号）</span>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-result v-else-if="error" icon="warning" :title="error.code || '查询失败'" :sub-title="error.message" />

    <template v-else-if="data">
      <el-card shadow="never" style="margin-bottom:14px">
        <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
          <el-tag size="large" effect="plain">{{ data.typeLabel }}</el-tag>
          <span class="mono" style="font-size:16px;font-weight:700">{{ data.query }}</span>
          <el-tag size="large" :type="statusType(data.status)">{{ data.statusLabel || data.status }}</el-tag>
          <span style="flex:1"></span>
          <el-button v-if="data.manifestCode" text type="primary" @click="$router.push(`/manifest/${data.manifestCode}`)">
            查看交接单 {{ data.manifestCode }}<el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </el-card>

      <el-row :gutter="14">
        <el-col :xs="24" :md="15">
          <el-card v-if="data.custodian" shadow="never" style="margin-bottom:14px">
            <template #header><b>当前保管责任</b></template>
            <div class="holder-card">
              <div style="font-size:18px;font-weight:800;color:#1e3a8a">
                <el-icon><UserFilled /></el-icon> {{ data.custodian.text }}
              </div>
              <div class="muted" style="margin-top:6px">责任起始：{{ fmtTime(data.custodian.since) }}</div>
            </div>
          </el-card>

          <el-card v-if="data.weight" shadow="never" style="margin-bottom:14px">
            <template #header><b>认可重量</b></template>
            <div style="display:flex;align-items:baseline;gap:14px;flex-wrap:wrap">
              <div><span class="stat-num weight-ok">{{ yuan(data.weight.totalRecognizedNetKg) }}</span> kg 总净重</div>
              <el-tag>{{ basisLabel(data.weight.basis) }}</el-tag>
            </div>
            <div class="muted" style="margin-top:6px">{{ data.weight.basisLabel }}</div>
            <div v-if="data.recognizedNetKg != null" style="margin-top:8px">
              当前轮次分箱认可净重：<b>{{ yuan(data.recognizedNetKg) }} kg</b>
            </div>
          </el-card>

          <el-card shadow="never" style="margin-bottom:14px">
            <template #header><b>每个角色的下一步</b></template>
            <el-empty v-if="!data.nextSteps || !data.nextSteps.length" description="暂无待办动作" :image-size="60" />
            <div v-for="(s, i) in data.nextSteps" :key="i" class="step-row" :class="{ 'step-disabled': !s.enabled }">
              <el-tag :type="roleTag(s.role)" size="small" style="min-width:96px;justify-content:center">{{ s.roleLabel }}</el-tag>
              <div style="flex:1">
                <div :style="{fontWeight: s.enabled ? 600 : 400}">{{ s.label }}</div>
                <div v-if="s.disabledReason" class="muted">原因：{{ s.disabledReason }}</div>
              </div>
              <el-tag v-if="s.enabled" type="success" size="small">可办理</el-tag>
              <el-tag v-else type="info" size="small">暂不可办</el-tag>
            </div>
          </el-card>

          <el-card v-if="data.items && data.items.length" shadow="never" style="margin-bottom:14px">
            <template #header><b>箱清单与各箱状态</b></template>
            <el-table :data="data.items" size="small" border>
              <el-table-column label="箱/轮次" prop="qrCode" width="120" />
              <el-table-column label="认可净重kg" width="100">
                <template #default="{ row }">{{ yuan(row.recognizedNetKg) }}</template>
              </el-table-column>
              <el-table-column label="箱状态" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="boxTag(row.boxStatus)">{{ boxLabel(row.boxStatus) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="保管方">
                <template #default="{ row }">{{ row.custodyOrgName || party(row.custodyParty) }}<span v-if="row.custodyUserName"> · {{ row.custodyUserName }}</span></template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never">
            <template #header><b>真实交接时间线（只追加，不可改）</b></template>
            <el-timeline class="mini-timeline">
              <el-timeline-item v-for="e in data.timeline || []" :key="e.id" :timestamp="fmtTime(e.at)" placement="top"
                :type="e.action?.includes('HANDOVER') ? 'primary' : e.action?.includes('PENDING') || e.action?.includes('LOST') ? 'danger' : 'success'">
                <b>{{ actionLabel(e.action) }}</b>
                <div class="muted">{{ e.actorName }}<span v-if="e.detail"> — {{ e.detail }}</span></div>
              </el-timeline-item>
            </el-timeline>
          </el-card>
        </el-col>

        <el-col :xs="24" :md="9">
          <el-alert v-if="data.mandate" :type="data.mandate.status==='COMPLETED' ? 'success' : 'error'"
            :title="`委托 ${data.mandate.code}：${mandateLabel(data.mandate.status)}`" :closable="false" show-icon style="margin-bottom:14px" />
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header>
              <div style="display:flex;justify-content:space-between;align-items:center">
                <b>未解决差异（{{ (data.openDiscrepancies||[]).length }}）</b>
                <el-button text type="primary" @click="$router.push('/discrepancies')">差异中心</el-button>
              </div>
            </template>
            <el-empty v-if="!(data.openDiscrepancies||[]).length" description="无未结差异" :image-size="55" />
            <div v-for="d in data.openDiscrepancies" :key="d.code" class="disc">
              <el-tag type="danger" size="small">{{ discType(d.type) }}</el-tag>
              <div style="font-weight:600;margin:5px 0">{{ d.title }}</div>
              <div class="muted">{{ d.description }}</div>
              <div v-if="d.diffValue != null" style="margin-top:4px">
                期望 {{ d.expectedValue }} / 实测 {{ d.observedValue }} /
                <span class="weight-bad">差 {{ d.diffValue }}kg</span>（阈值 {{ d.thresholdKg }}）
              </div>
              <div class="mono muted" style="margin-top:4px">{{ d.code }}</div>
            </div>
          </el-card>
          <el-card v-if="data.openReviewTasks && data.openReviewTasks.length" shadow="never" style="margin-bottom:14px">
            <template #header><b>回执复核任务</b></template>
            <el-alert v-for="t in data.openReviewTasks" :key="t.code" type="error" :closable="false"
              :title="`${t.code} · ${t.taskType==='RECEIPT_LOST'?'回执丢失复核':'回执撤回复核'}`" :description="t.reason" style="margin-bottom:8px" />
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-card shadow="never" style="margin-top:18px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <b>全市虚构机构当前所有未结差异（任何人可查）</b>
          <el-button size="small" @click="loadOpen">刷新</el-button>
        </div>
      </template>
      <el-table :data="openList" size="small" border>
        <el-table-column prop="code" label="差异号" width="130" />
        <el-table-column label="类型" width="130"><template #default="{row}"><el-tag type="danger" size="small">{{ discType(row.type) }}</el-tag></template></el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column prop="subjectCode" label="主体" width="150" />
        <el-table-column label="差值kg" width="90"><template #default="{row}"><span class="weight-bad">{{ row.diffValue ?? '—' }}</span></template></el-table-column>
        <el-table-column label="发生时间" width="150"><template #default="{row}">{{ fmtTime(row.createdAt) }}</template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { api, fmtTime, yuan, MANIFEST_STATUS, BOX_STATUS } from '../api.js'

const route = useRoute()
const code = ref(route.query.code || 'FD-SEAL-0001')
const data = ref(null)
const loading = ref(false)
const error = ref(null)
const openList = ref([])
const quick = ['FD-SEAL-0001', 'C1', 'P1']

async function search() {
  if (!code.value) return
  loading.value = true; error.value = null; data.value = null
  try { data.value = await api.get('/track/' + encodeURIComponent(code.value.trim())) }
  catch (e) { error.value = e }
  finally { loading.value = false }
}
async function loadOpen() {
  try { openList.value = await api.get('/track/open-discrepancies') } catch { /* ignore */ }
}
onMounted(() => { search(); loadOpen() })

function statusType(s) { return MANIFEST_STATUS[s]?.type || BOX_STATUS[s]?.type || 'info' }
function boxLabel(s) { return BOX_STATUS[s]?.label || s }
function boxTag(s) { return BOX_STATUS[s]?.type || 'info' }
function party(p) { return { HOSPITAL: '医院', TRANSPORTER: '收运方', PLANT: '处置厂', CLEANING: '待清洁区' }[p] || p }
function roleTag(r) { return { HOSPITAL: 'success', TRANSPORTER: 'primary', PLANT: 'warning', PUBLIC: 'danger' }[r] || 'info' }
function basisLabel(b) {
  return { BOX_DIRECT: '逐箱证据', PALLET_COMBINED: '合托总量', MIXED: '混合', PLANT_ADJUSTED: '补称后实测', NONE: '暂无重量' }[b] || b
}
function mandateLabel(s) { return { ACTIVE: '处置进行中', COMPLETED: '已结束', REVIEW_REQUIRED: '回执复核中·未结束' }[s] || s }
function discType(t) {
  return { WEIGHT_MISMATCH: '重量差异', MISSING_BOX: '缺箱/清单不符', DUPLICATE_BAG: '袋码重复', SEAL_BROKEN: '封条异常', QUALIFICATION_INVALID: '资质异常' }[t] || t
}
function actionLabel(a) {
  return {
    BOX_REGISTERED: '登记箱', ROUND_STARTED: '开始封装轮次', BOX_SEALED: '封箱',
    MANIFEST_CREATED: '创建交接单', CUSTODY_HANDOVER: '确认交车·保管转移',
    VEHICLE_ARRIVED: '车辆到厂', PLANT_INTAKE: '处置厂实接',
    PLANT_PENDING_CHECK: '转入厂内待核', PLANT_INTAKE_OK: '实接核验通过',
    PLANT_SIGNED: '处置厂签收', EMPTY_RETURNED: '空箱取回至待清洁区',
    CLEANING_DONE: '清洁完成可复用', MANIFEST_CANCELLED: '交接单取消',
    DISPOSAL_PERFORMED: '模拟器完成处置', RECEIPT_ISSUED: '处置回执签发',
    RECEIPT_LOST: '处置回执丢失', RECEIPT_WITHDRAWN: '处置回执撤回'
  }[a] || a
}
</script>

<style scoped>
.step-row { display: flex; align-items: center; gap: 12px; padding: 9px 4px; border-bottom: 1px dashed #eef0f4; }
.step-row:last-child { border-bottom: none; }
.disc { border: 1px solid #fecaca; background: #fef2f2; border-radius: 8px; padding: 9px 11px; margin-bottom: 9px; font-size: 13px; }
</style>
