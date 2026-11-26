<template>
  <div class="page">
    <div class="page-title">虚构机构、资质与规则</div>
    <div class="page-sub">下列主体与证照均为随题虚构，不连接真实监管或处置设备。同一机构的同批箱袋资料同时支持逐箱与合托两种重量路径。</div>

    <el-card shadow="never" style="margin-bottom:14px">
      <template #header><b>虚构规则约定</b></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="称重差异容限">同一口径下 |差| &gt; <b>0.5 kg</b> 即转差异核实</el-descriptions-item>
        <el-descriptions-item label="合托净重公式">毛重 − 托盘皮重 − Σ各箱实际空箱重</el-descriptions-item>
        <el-descriptions-item label="禁止事项">合托差异只能补称，禁止按袋数比例摊造箱净重</el-descriptions-item>
        <el-descriptions-item label="委托结束条件">对应批次存在 VALID 处置完成回执</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top:10px">
        <el-tag v-for="c in categories" :key="c.code" style="margin:0 8px 8px 0" type="info">
          {{ c.code }} · {{ c.name }} <span class="muted">（{{ c.fictionalRule }}）</span>
        </el-tag>
      </div>
    </el-card>

    <el-tabs v-model="type">
      <el-tab-pane v-for="g in groups" :key="g.type" :label="g.label" :name="g.type">
        <el-row :gutter="14">
          <el-col v-for="o in orgs[g.type] || []" :key="o.id" :xs="24" :md="12" style="margin-bottom:14px">
            <el-card shadow="hover">
              <div style="display:flex;align-items:center;gap:10px">
                <b style="font-size:16px">{{ o.name }}</b>
                <el-tag size="small" type="info">{{ o.code }}</el-tag>
              </div>
              <el-table :data="o.qualifications || []" size="small" border style="margin-top:10px">
                <el-table-column prop="qualName" label="资质（虚构）" min-width="180" />
                <el-table-column prop="certNo" label="证号" width="150" />
                <el-table-column label="有效期" width="180">
                  <template #default="{row}">{{ row.validFrom }} ~ {{ row.validTo }}</template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{row}">
                    <el-tag size="small" :type="row.status==='VALID'?'success':'danger'">{{ qualStatus(row.status) }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>

    <el-card shadow="never">
      <template #header><b>虚构收运车辆</b></template>
      <el-table :data="vehicles" size="small" border>
        <el-table-column prop="plateNo" label="车牌（虚构编号）" width="160" />
        <el-table-column prop="orgName" label="所属机构" min-width="220" />
        <el-table-column prop="driverName" label="驾驶员" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{row}"><el-tag size="small" :type="row.status==='ACTIVE'?'success':'danger'">{{ row.status==='ACTIVE'?'营运':'暂停' }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api.js'

const groups = [
  { type: 'HOSPITAL', label: '医院（产生/交接）' },
  { type: 'TRANSPORTER', label: '收运机构' },
  { type: 'DISPOSAL', label: '处置厂' }
]
const type = ref('HOSPITAL')
const orgs = reactive({})
const vehicles = ref([])
const categories = ref([])

function qualStatus(s) { return { VALID: '有效', SUSPENDED: '暂停', REVOKED: '吊销', EXPIRED: '过期' }[s] || s }

onMounted(async () => {
  for (const g of groups) orgs[g.type] = await api.get('/orgs?type=' + g.type)
  vehicles.value = await api.get('/vehicles')
  categories.value = await api.get('/categories')
})
</script>
