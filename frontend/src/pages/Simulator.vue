<template>
  <div class="page">
    <div class="page-title">虚构硬件与回执模拟器</div>
    <div class="page-sub">所有设备均为软件模拟：可注入称重仪稳定读数、模拟回执撤回/丢失；「重置基线数据」会清空业务数据并恢复虚构机构、账号、资质、C1–C6、P1–P2。</div>

    <el-row :gutter="14">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" style="margin-bottom:14px">
          <template #header><b>称重仪模拟器（{{ scales.length }}）</b></template>
          <div v-for="s in scales" :key="s.code" class="device">
            <div style="flex:1">
              <b>{{ s.name }}</b>
              <div class="muted mono">{{ s.code }} · {{ s.location }}</div>
            </div>
            <el-input-number v-model="reading[s.code]" :min="0" :precision="3" :step="0.5" size="small" style="width:130px" />
            <span>kg</span>
            <el-button size="small" type="primary" @click="setReading(s.code)">注入读数</el-button>
            <el-button size="small" @click="read(s.code)">读取</el-button>
          </div>
        </el-card>

        <el-card shadow="never" style="margin-bottom:14px">
          <template #header><b>处置设备模拟器</b></template>
          <div v-for="d in devices" :key="d.code" class="device">
            <div style="flex:1"><b>{{ d.name }}</b><div class="muted mono">{{ d.code }} · {{ d.location }}</div></div>
            <el-tag type="success">在线（模拟）</el-tag>
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header><b>环境与数据</b></template>
          <el-button type="danger" plain @click="reset">重置为虚构基线数据</el-button>
          <el-button type="primary" plain @click="$router.push('/acceptance')">前往六场景验收自检</el-button>
          <div class="muted" style="margin-top:10px">后端健康：{{ health }}</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <b>完成回执模拟器（撤回 / 丢失，历史不删除）</b>
              <el-button size="small" @click="loadReceipts">刷新</el-button>
            </div>
          </template>
          <el-table :data="receipts" size="small" border max-height="640">
            <el-table-column prop="code" label="回执号" width="140" />
            <el-table-column prop="batchCode" label="批次" width="140" />
            <el-table-column label="状态" width="90">
              <template #default="{row}">
                <el-tag size="small" :type="row.status==='VALID'?'success':row.status==='LOST'?'info':'danger'">
                  {{ {VALID:'有效',WITHDRAWN:'撤回',LOST:'丢失'}[row.status] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{row}">
                <template v-if="row.status==='VALID'">
                  <el-button link type="warning" size="small" @click="invalidate(row.code,'withdraw')">撤回</el-button>
                  <el-button link type="danger" size="small" @click="invalidate(row.code,'lose')">丢失</el-button>
                </template>
                <span v-else class="muted">已留痕</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="muted" style="margin-top:8px">失效后请至「处置厂端 → 复核任务」补开回执并办结复核。</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api.js'

const scales = ref([])
const devices = ref([])
const receipts = ref([])
const reading = reactive({})
const health = ref('检查中…')

async function loadScales() {
  scales.value = await api.get('/sim/scales')
  scales.value.forEach(s => { reading[s.code] = Number(s.readingKg) })
}
async function loadReceipts() { receipts.value = await api.get('/receipts') }
async function setReading(code) {
  try { await api.patch(`/sim/scales/${code}`, { readingKg: reading[code] }); ElMessage.success(`${code} 读数已设为 ${reading[code]}kg`) }
  catch (e) { ElMessage.error(e.message) }
}
async function read(code) {
  try { const r = await api.get(`/sim/scales/${code}/read`); reading[code] = Number(r.readingKg); ElMessage.success(`读数 ${r.readingKg}kg（stable=${r.stable}）`) }
  catch (e) { ElMessage.error(e.message) }
}
async function invalidate(code, kind) {
  try {
    const { value } = await ElMessageBox.prompt('请输入原因（模拟）', kind === 'lose' ? '模拟回执丢失' : '模拟回执撤回',
      { inputValue: kind === 'lose' ? '传递途中遗失' : '监管要求撤回', confirmButtonText: '确定' })
    const r = await api.post(`/receipts/${code}/${kind}`, { reason: value })
    ElMessage.warning(`已${kind === 'lose' ? '标记丢失' : '撤回'}，复核任务 ${r.reviewTask.code} 已生成`)
    loadReceipts()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function reset() {
  try {
    await ElMessageBox.confirm('将清空所有业务数据并恢复基线，确定？（验收自检前通常会自动重置）', '重置基线', { type: 'warning' })
    await api.post('/dev/reset', {})
    ElMessage.success('已重置为虚构基线数据')
    await loadScales(); await loadReceipts()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
onMounted(async () => {
  await loadScales()
  devices.value = await api.get('/sim/disposal-devices')
  await loadReceipts()
  try { const h = await fetch('/api/meta/health').then(r => r.json()); health.value = h.ok ? '正常 ' + h.service : '异常' } catch { health.value = '不可用' }
})
</script>

<style scoped>
.device { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px dashed #e5e7eb; }
.device:last-child { border-bottom: none; }
</style>
