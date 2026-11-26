<template>
  <div class="page">
    <div class="page-title">差异核实中心</div>
    <div class="page-sub">称重差绝对值 &gt; 0.5kg、合托缺箱、袋码跨箱重复、封条/资质异常都会挂案。重量差异必须逐箱补称取得真实证据，禁止按袋数比例摊造箱净重。</div>
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;align-items:center;gap:12px">
          <el-radio-group v-model="filter" @change="load">
            <el-radio-button label="OPEN">未结</el-radio-button>
            <el-radio-button label="ALL">全部</el-radio-button>
          </el-radio-group>
          <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
      </template>
      <el-table :data="rows" border size="small">
        <el-table-column prop="code" label="差异号" width="130" />
        <el-table-column label="类型" width="130">
          <template #default="{row}"><el-tag :type="row.status==='OPEN'?'danger':'success'" size="small">{{ typeLabel(row.type) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="210" />
        <el-table-column prop="subjectCode" label="主体" width="150" />
        <el-table-column label="期望/实测/差 (kg)" width="180">
          <template #default="{row}">
            <span v-if="row.expectedValue!=null">{{ row.expectedValue }} / {{ row.observedValue }} / <b :class="Math.abs(row.diffValue)>0.5?'weight-bad':''">{{ row.diffValue }}</b></span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态/处理" min-width="200">
          <template #default="{row}">
            <el-tag size="small" :type="row.status==='OPEN'?'danger':'success'">{{ row.status==='OPEN'?'未结':'已结案' }}</el-tag>
            <div v-if="row.resolutionNote" class="muted" style="margin-top:3px">{{ row.resolutionType }}：{{ row.resolutionNote }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{row}">
            <el-button v-if="row.manifestId" link type="primary" size="small" @click="$router.push(`/manifest/${manifestCode(row)}`)">到交接单办理/补称</el-button>
            <el-button v-if="row.status==='OPEN'" link type="success" size="small" @click="openResolve(row)">快速结案</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dlg" title="差异结案" width="480px">
      <el-form label-width="92px">
        <el-form-item label="处理方式">
          <el-select v-model="form.type" style="width:100%">
            <el-option v-for="o in options" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.note" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <el-alert v-if="current?.type==='WEIGHT_MISMATCH'" type="warning" :closable="false"
        title="按实测认可前，请先到交接单完成逐箱补称；系统取最近补称/到厂记录更新认可重量。" style="margin-bottom:10px" />
      <template #footer>
        <el-button @click="dlg=false">取消</el-button>
        <el-button type="primary" @click="submit">确认结案</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api.js'

const rows = ref([])
const filter = ref('OPEN')
const dlg = ref(false)
const current = ref(null)
const form = reactive({ type: 'OTHER', note: '' })
const manifestMap = ref({})

const options = computed(() => {
  const t = current.value?.type
  if (t === 'WEIGHT_MISMATCH') return [
    { value: 'ACCEPT_MEASURED', label: '按补称/复称实测认可' },
    { value: 'KEEP_DECLARED', label: '维持原认可重量' }
  ]
  if (t === 'MISSING_BOX') return [{ value: 'BOX_HANDLED_SEPARATELY', label: '缺箱已找回/另单交接' }, { value: 'OTHER', label: '其他' }]
  return [{ value: 'OTHER', label: '其他（必填说明）' }]
})

async function load() {
  rows.value = await api.get('/discrepancies?status=' + filter.value)
  const ms = await api.get('/manifests?status=ALL')
  manifestMap.value = Object.fromEntries(ms.map(m => [m.id, m.code]))
}
function manifestCode(row) {
  // 列表行 manifestId 已被 Views 转成 manifestId（蛇形 manifest_id → manifestId）
  return manifestMap.value[row.manifestId] || ''
}
function openResolve(row) { current.value = row; form.type = options.value[0].value; form.note = ''; dlg.value = true }
async function submit() {
  try { await api.post(`/discrepancies/${current.value.code}/resolve`, { resolutionType: form.type, note: form.note }); ElMessage.success('已结案'); dlg.value = false; load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}
function typeLabel(t) {
  return { WEIGHT_MISMATCH: '重量差异', MISSING_BOX: '缺箱/清单不符', DUPLICATE_BAG: '袋码重复', SEAL_BROKEN: '封条异常', QUALIFICATION_INVALID: '资质异常' }[t] || t
}
onMounted(load)
</script>
