<template>
  <div class="page">
    <div class="page-title">收运员工作台</div>
    <div class="page-sub">扫描当前封口码 / 带本轮封装号的箱 QR 与车辆建立交接清单；可逐箱复称或组织完整一托合称，提交医院交接员确认后保管责任才转移。到厂后登记，签收卸料后取回空箱（只进待清洁区）。</div>

    <el-row :gutter="14" style="margin-bottom:16px">
      <el-col :xs="24" :md="9">
        <el-card shadow="never">
          <template #header><b>扫码建单</b></template>
          <el-form label-width="78px">
            <el-form-item label="车辆">
              <el-select v-model="vehicleCode" style="width:100%">
                <el-option v-for="v in vehicles" :key="v.plateNo"
                  :label="`${v.plateNo} · ${v.driverName}（${v.status==='ACTIVE'?'营运':'暂停'}）`" :value="v.plateNo"
                  :disabled="v.status!=='ACTIVE'" />
              </el-select>
            </el-form-item>
            <el-form-item label="医院">
              <el-select v-model="hospitalOrg" style="width:100%">
                <el-option v-for="o in hospitals" :key="o.code" :label="o.name" :value="o.code" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-button type="primary" style="width:100%" :loading="creating" @click="create">
            <el-icon><Plus /></el-icon>建立车次交接单
          </el-button>
          <el-alert type="info" :closable="false" style="margin-top:12px"
            title="建单后在详情页连续扫描封口码/箱QR；两名收运员可同时建单扫同一箱，但医院确认交车时只有一单能成功。" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="15">
        <el-card shadow="never">
          <template #header><b>扫码识别小工具</b></template>
          <div style="display:flex;gap:8px">
            <el-input v-model="scan" placeholder="滴——扫描封口码 FD-… 或 C1#R1" @keyup.enter="resolve" />
            <el-button @click="resolve">识别</el-button>
          </div>
          <el-descriptions v-if="resolved" :column="1" border size="small" style="margin-top:12px">
            <el-descriptions-item label="识别为">{{ resolved.typeLabel }} · {{ resolved.query }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ resolved.statusLabel }}</el-descriptions-item>
            <el-descriptions-item label="保管方">{{ resolved.custodian?.text || '—' }}</el-descriptions-item>
            <el-descriptions-item label="下一步">{{ resolved.nextSteps?.map(s=>s.label).join('；') || '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header><b>我的交接单</b></template>
      <ManifestTable :rows="manifests" @reload="load" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api.js'
import ManifestTable from '../components/ManifestTable.vue'

const router = useRouter()
const vehicles = ref([])
const hospitals = ref([])
const manifests = ref([])
const vehicleCode = ref('X-A1201')
const hospitalOrg = ref('YY-RH')
const creating = ref(false)
const scan = ref('')
const resolved = ref(null)

async function load() { manifests.value = await api.get('/manifests') }
async function create() {
  creating.value = true
  try {
    const m = await api.post('/manifests', { vehicleCode: vehicleCode.value, hospitalOrgCode: hospitalOrg.value })
    ElMessage.success('已建单，请扫码加入箱清单')
    router.push('/manifest/' + m.code)
  } catch (e) { ElMessage.error(e.message) } finally { creating.value = false }
}
async function resolve() {
  try { resolved.value = await api.get('/track/' + encodeURIComponent(scan.value.trim())) }
  catch (e) { ElMessage.error(e.message) }
}
onMounted(async () => {
  vehicles.value = await api.get('/vehicles')
  hospitals.value = await api.get('/orgs?type=HOSPITAL')
  load()
})
</script>
