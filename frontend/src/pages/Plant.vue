<template>
  <div class="page">
    <div class="page-title">处置厂工作台</div>
    <div class="page-sub">核实资质、封条与重量后实接；差异未结显示厂内待核且不可退回在途，补称结案后签收。签收单组批 → 处置模拟器执行 → 签发有效完成回执才能结束委托；回执撤回/丢失会生成复核任务。</div>

    <el-tabs v-model="tab">
      <el-tab-pane label="到厂核实与签收" name="intake">
        <el-card shadow="never"><ManifestTable :rows="manifests" @reload="loadManifests" /></el-card>
      </el-tab-pane>

      <el-tab-pane label="处置批次 · 完成回执模拟器" name="batch">
        <el-card shadow="never" style="margin-bottom:14px">
          <template #header><b>已签收交接单组批</b></template>
          <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
            <el-select v-model="selected" multiple filterable placeholder="选择一张或多张 SIGNED 交接单" style="min-width:420px;flex:1">
              <el-option v-for="m in signedManifests" :key="m.code"
                :label="`${m.code}（${m.boxCount}箱 / ${yuan(m.totalRecognizedNetKg)}kg）`" :value="m.code" />
            </el-select>
            <el-button type="primary" @click="createBatch">组成处置批次</el-button>
            <el-button @click="loadAll">刷新</el-button>
          </div>
        </el-card>

        <el-card v-for="b in batches" :key="b.code" shadow="never" style="margin-bottom:13px">
          <div style="display:flex;align-items:center;gap:11px;flex-wrap:wrap">
            <b style="font-size:15px">{{ b.code }}</b>
            <el-tag :type="b.status==='DISPOSED'?'success':'info'">{{ b.status==='DISPOSED'?'已处置':'待处置' }}</el-tag>
            <span class="muted">交接单：{{ (b.manifestCodes||[]).join('，') }}</span>
            <span style="flex:1"></span>
            <template v-if="b.status==='CREATED'">
              <el-select v-model="performForm[b.code].deviceCode" size="small" style="width:210px">
                <el-option v-for="d in devices" :key="d.code" :label="`${d.name}（${d.code}）`" :value="d.code" />
              </el-select>
              <el-input v-model="performForm[b.code].method" size="small" style="width:200px" placeholder="处置工艺（虚构）" />
              <el-button size="small" type="warning" @click="perform(b.code)">处置设备执行</el-button>
            </template>
            <el-button v-else size="small" type="success" @click="issue(b.code)">签发处置完成回执</el-button>
          </div>
          <div v-if="b.performedAt" class="muted" style="margin:7px 0">
            {{ b.disposalMethod }} · 设备 {{ b.deviceCode }} · {{ b.performedByName }} · {{ fmtTime(b.performedAt) }}
          </div>

          <el-table :data="receiptsByBatch[b.code] || []" size="small" border style="margin-top:9px">
            <el-table-column prop="code" label="回执号" width="150" />
            <el-table-column prop="seqInBatch" label="批次内序号" width="90" />
            <el-table-column label="状态" width="110">
              <template #default="{row}">
                <el-tag size="small" :type="row.status==='VALID'?'success':row.status==='LOST'?'info':'danger'">
                  {{ {VALID:'有效',WITHDRAWN:'已撤回',LOST:'已丢失'}[row.status] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="issuedByName" label="签发人" width="100" />
            <el-table-column label="签发时间" width="150"><template #default="{row}">{{ fmtTime(row.issuedAt) }}</template></el-table-column>
            <el-table-column prop="reason" label="失效原因" min-width="160" />
            <el-table-column label="回执模拟操作" width="180">
              <template #default="{row}">
                <template v-if="row.status==='VALID'">
                  <el-button link type="warning" size="small" @click="invalidate(row.code,'withdraw')">撤回</el-button>
                  <el-button link type="danger" size="small" @click="invalidate(row.code,'lose')">模拟丢失</el-button>
                </template>
                <span v-else class="muted">历史保留</span>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top:8px">
            <el-button size="small" text type="primary" @click="issue(b.code)">+ 补开/重开新回执（旧回执保留）</el-button>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="`复核任务 (${openTasks.length})`" name="review">
        <el-card shadow="never">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <b>回执撤回/丢失触发的复核任务</b>
              <el-radio-group v-model="taskFilter" size="small">
                <el-radio-button label="OPEN">未结</el-radio-button>
                <el-radio-button label="ALL">全部</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <el-table :data="shownTasks" border size="small">
            <el-table-column prop="code" label="复核号" width="130" />
            <el-table-column label="类型" width="130">
              <template #default="{row}">
                <el-tag size="small" :type="row.taskType==='RECEIPT_LOST'?'info':'danger'">
                  {{ row.taskType==='RECEIPT_LOST'?'回执丢失':'回执撤回' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="receiptCode" label="原回执" width="150" />
            <el-table-column prop="batchCode" label="批次" width="150" />
            <el-table-column prop="reason" label="原因" min-width="180" />
            <el-table-column label="状态" width="90">
              <template #default="{row}"><el-tag size="small" :type="row.status==='OPEN'?'danger':'success'">{{ row.status==='OPEN'?'未结':'已复核' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{row}">
                <el-button v-if="row.status==='OPEN'" size="small" type="primary" @click="handleTask(row.code)">办结</el-button>
                <span v-else class="muted">{{ row.handleNote }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, yuan, fmtTime } from '../api.js'
import ManifestTable from '../components/ManifestTable.vue'

const tab = ref('intake')
const manifests = ref([])
const batches = ref([])
const receipts = ref([])
const tasks = ref([])
const devices = ref([])
const selected = ref([])
const taskFilter = ref('OPEN')
const performForm = reactive({})

const signedManifests = computed(() => manifests.value.filter(m => m.status === 'SIGNED'))
const openTasks = computed(() => tasks.value.filter(t => t.status === 'OPEN'))
const shownTasks = computed(() => taskFilter.value === 'OPEN' ? openTasks.value : tasks.value)
const receiptsByBatch = computed(() => {
  const map = {}
  for (const r of receipts.value) { (map[r.batchCode] ||= []).push(r) }
  return map
})

async function loadManifests() { manifests.value = await api.get('/manifests') }
async function loadBatches() {
  batches.value = await api.get('/disposal-batches')
  for (const b of batches.value) {
    if (!performForm[b.code]) performForm[b.code] = { deviceCode: 'DISP-1', method: b.disposalMethod || '高温蒸煮（虚构工艺）' }
  }
}
async function loadReceipts() { receipts.value = await api.get('/receipts') }
async function loadTasks() { tasks.value = await api.get('/review-tasks') }
async function loadAll() { await Promise.all([loadManifests(), loadBatches(), loadReceipts(), loadTasks()]) }

async function createBatch() {
  if (!selected.value.length) return ElMessage.warning('请选择已签收交接单')
  try { const b = await api.post('/disposal-batches', { manifestCodes: selected.value }); ElMessage.success('批次 ' + b.code + ' 已创建'); selected.value = []; await loadBatches() }
  catch (e) { ElMessage.error(e.message) }
}
async function perform(code) {
  const f = performForm[code]
  try { await api.post(`/disposal-batches/${code}/perform`, { deviceCode: f.deviceCode, method: f.method }); ElMessage.success('处置设备模拟执行完成'); await loadBatches() }
  catch (e) { ElMessage.error(e.message) }
}
async function issue(code) {
  try { const r = await api.post(`/disposal-batches/${code}/issue-receipt`, {}); ElMessage.success('回执已签发：' + r.code); await loadReceipts(); await loadManifests() }
  catch (e) { ElMessage.error(e.message) }
}
async function invalidate(receiptCode, kind) {
  try {
    const { value } = await ElMessageBox.prompt(kind === 'lose' ? '说明丢失情况（模拟）' : '撤回原因（模拟）',
      kind === 'lose' ? '模拟完成回执丢失' : '模拟完成回执撤回', { confirmButtonText: '确定', inputValue: kind === 'lose' ? '纸质回执在传递中遗失' : '监管核验要求撤回' })
    const r = await api.post(`/receipts/${receiptCode}/${kind}`, { reason: value })
    ElMessage.warning(`回执已${kind === 'lose' ? '标记丢失' : '撤回'}，复核任务 ${r.reviewTask.code} 已生成，委托转为复核中`)
    await loadReceipts(); await loadTasks(); await loadManifests()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function handleTask(code) {
  try {
    const { value } = await ElMessageBox.prompt('复核结论/备注（通常先补开新回执）', '办结复核任务', { confirmButtonText: '办结', inputValue: '已核对真实交接与处置记录，补开新回执' })
    await api.post(`/review-tasks/${code}/handle`, { note: value })
    ElMessage.success('复核任务已办结')
    await loadTasks()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

onMounted(async () => {
  devices.value = await api.get('/sim/disposal-devices')
  await loadAll()
})
</script>
