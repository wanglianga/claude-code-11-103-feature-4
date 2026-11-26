<template>
  <div>
    <div style="margin-bottom:10px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable size="default" style="width:180px" @change="emit('reload')">
        <el-option v-for="(v,k) in MANIFEST_STATUS" :key="k" :label="v.label" :value="k" />
      </el-select>
      <el-button @click="emit('reload')"><el-icon><Refresh /></el-icon>刷新</el-button>
      <span class="muted">点击「办理/查看」进入扫码、两路称重、确认、到厂、差异与签收全流程</span>
    </div>
    <el-table :data="filtered" border size="small">
      <el-table-column prop="code" label="交接单号" width="150">
        <template #default="{ row }"><span class="code-chip" @click="go(row.code)">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }"><el-tag size="small" :type="MANIFEST_STATUS[row.status]?.type">{{ MANIFEST_STATUS[row.status]?.label || row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="vehiclePlate" label="车牌" width="100" />
      <el-table-column prop="transporterName" label="收运员" width="90" />
      <el-table-column label="箱数" width="60" prop="boxCount" />
      <el-table-column label="认可总净重" width="110">
        <template #default="{ row }">{{ yuan(row.totalRecognizedNetKg) }} <span class="muted">{{ basisShort(row.weightBasis) }}</span></template>
      </el-table-column>
      <el-table-column label="未结差异" width="80">
        <template #default="{ row }">
          <el-badge v-if="row.openDiscCount>0" :value="row.openDiscCount" type="danger" />
          <span v-else class="muted">0</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }"><el-button size="small" type="primary" @click="go(row.code)">办理/查看</el-button></template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { MANIFEST_STATUS, yuan, fmtTime } from '../api.js'

const props = defineProps({ rows: { type: Array, default: () => [] } })
const emit = defineEmits(['reload'])
const router = useRouter()
const statusFilter = ref('')
const filtered = computed(() => statusFilter.value ? props.rows.filter(r => r.status === statusFilter.value) : props.rows)
function go(code) { router.push('/manifest/' + code) }
function basisShort(b) { return { BOX_DIRECT: '逐箱', PALLET_COMBINED: '合托', MIXED: '混合', PLANT_ADJUSTED: '实测调整' }[b] || '' }
</script>
