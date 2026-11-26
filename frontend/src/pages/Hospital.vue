<template>
  <div class="page">
    <div class="page-title">医院交接员工作台</div>
    <div class="page-sub">封箱 ≠ 已交车。每轮封装记录本轮袋码清单与<b>实际空箱重</b>；在「交接办理」中与收运员复称并确认后，保管责任才转移。</div>

    <el-tabs v-model="tab" @tab-change="reload">
      <!-- ============ 箱袋与封箱 ============ -->
      <el-tab-pane label="箱袋 · 本轮封装 · 封箱" name="boxes">
        <div style="margin-bottom:12px;display:flex;gap:10px">
          <el-button type="primary" @click="boxDlg=true"><el-icon><Plus /></el-icon>登记周转箱</el-button>
          <el-button @click="bagDlg=true"><el-icon><Postcard /></el-icon>发放封口码/袋</el-button>
          <el-button @click="loadBoxes"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
        <el-row :gutter="14">
          <el-col :xs="24" :md="10">
            <el-table :data="boxes" border size="small" highlight-current-row @row-click="selectBox" height="640">
              <el-table-column prop="code" label="箱号" width="78" />
              <el-table-column label="状态" min-width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="BOX_STATUS[row.status]?.type">{{ BOX_STATUS[row.status]?.label || row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="轮/袋" width="70">
                <template #default="{ row }">R{{ row.currentRoundNo }} · {{ row.bagCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="认可净重" width="84">
                <template #default="{ row }">{{ yuan(row.recognizedNetKg) }}</template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :xs="24" :md="14">
            <el-card v-if="!cur" shadow="never"><el-empty description="点击左侧箱子办理本轮封装" /></el-card>
            <el-card v-else shadow="never">
              <template #header>
                <div style="display:flex;align-items:center;gap:10px">
                  <b style="font-size:16px">{{ cur.code }}</b>
                  <el-tag size="small" :type="BOX_STATUS[cur.status]?.type">{{ BOX_STATUS[cur.status]?.label || cur.status }}</el-tag>
                  <span style="flex:1"></span>
                  <el-tag v-if="cur.currentRound" type="info">第 {{ cur.currentRound.roundNo }} 轮</el-tag>
                </div>
              </template>

              <!-- 清洁可用：开始新一轮 -->
              <div v-if="cur.status==='AVAILABLE_CLEAN'" class="action-box">
                <el-alert type="success" :closable="false" title="箱体清洁可用。开始新一轮时轮次号 +1，并必须称量本轮实际空箱重。" style="margin-bottom:12px" />
                <el-input-number v-model="roundForm.tare" :min="0" :precision="3" :step="0.5" /> kg 实际空箱重
                <el-button size="small" @click="readTare">从 SC-H1 读秤</el-button>
                <el-button type="primary" @click="startRound">开始第 {{ cur.currentRoundNo + 1 }} 轮封装</el-button>
              </div>

              <!-- 待清洁 -->
              <div v-else-if="cur.status==='PENDING_CLEANING'" class="action-box">
                <el-alert type="info" :closable="false" show-icon
                  title="空箱取回后只进入待清洁区，不能直接作为新一轮可用箱。" style="margin-bottom:12px" />
                <el-input-number v-model="cleanTare" :min="0" :precision="3" :step="0.1" /> kg 清洁后复称皮重
                <el-button size="small" @click="readCleanTare">读秤</el-button>
                <el-button type="primary" @click="cleanBox">完成清洁/检查并转为可用</el-button>
              </div>

              <!-- 封装中：扫袋 + 封箱 -->
              <template v-else-if="cur.currentRound && cur.currentRound.status==='DRAFT'">
                <el-alert type="warning" :closable="false" style="margin-bottom:10px">
                  本轮箱扫码（复用编号必须带封装号）：
                  <b class="mono" style="font-size:15px">{{ cur.currentRound.qrCode }}</b>
                  —— 旧轮次码不能领取新袋
                </el-alert>
                <div style="display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap">
                  <el-select v-model="claimSeal" filterable allow-create placeholder="选择/扫描唯一封口码" style="width:260px">
                    <el-option v-for="b in unusedBags" :key="b.sealCode" :label="`${b.sealCode}（${b.categoryCode}）`" :value="b.sealCode" />
                  </el-select>
                  <el-button type="primary" @click="claimBag">扫袋码加入本箱清单</el-button>
                </div>
                <el-table :data="cur.currentRound.bags || []" size="small" border style="margin-bottom:12px">
                  <el-table-column prop="sealCode" label="封口码" width="180" />
                  <el-table-column prop="categoryCode" label="类别" width="110" />
                  <el-table-column prop="addedByName" label="加入人" />
                  <el-table-column label="操作" width="80">
                    <template #default="{ row }">
                      <el-button link type="danger" size="small" @click="removeBag(row.sealCode)">移出</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                  <span>空箱重 <b>{{ cur.currentRound.actualTareKg }}</b> kg；</span>
                  <span>封箱毛重</span>
                  <el-input-number v-model="sealGross" :min="0" :precision="3" :step="1" />
                  <el-button size="small" @click="readGross">从 SC-H1 读秤</el-button>
                  <el-button type="danger" @click="sealBox">核对清单并封箱</el-button>
                </div>
              </template>

              <!-- 已封箱及之后 -->
              <template v-else>
                <el-descriptions :column="2" border size="small" style="margin-bottom:12px">
                  <el-descriptions-item label="本轮QR">{{ cur.currentRound?.qrCode }}</el-descriptions-item>
                  <el-descriptions-item label="袋数">{{ (cur.currentRound?.bags||[]).length }}</el-descriptions-item>
                  <el-descriptions-item label="实际空箱重">{{ cur.currentRound?.actualTareKg }} kg</el-descriptions-item>
                  <el-descriptions-item label="封箱毛重">{{ cur.currentRound?.sealGrossKg ?? '—' }} kg</el-descriptions-item>
                  <el-descriptions-item label="认可净重">{{ yuan(cur.currentRound?.recognizedNetKg) }} kg</el-descriptions-item>
                  <el-descriptions-item label="封箱时间">{{ fmtTime(cur.currentRound?.sealedAt) }}</el-descriptions-item>
                </el-descriptions>
                <el-button v-if="activeManifestCode" type="primary" @click="$router.push(`/manifest/${activeManifestCode}`)">
                  前往交接单 {{ activeManifestCode }}
                </el-button>
              </template>

              <el-divider>历史轮次与重量证据（旧称量修订留痕）</el-divider>
              <el-collapse>
                <el-collapse-item v-for="r in cur.rounds" :key="r.id" :name="r.id" :title="`第 R${r.roundNo} 轮 · ${r.qrCode} · ${r.status}`">
                  <el-table :data="r.weights || []" size="small" border>
                    <el-table-column prop="stage" label="环节" width="120" />
                    <el-table-column prop="mode" label="口径" width="140" />
                    <el-table-column prop="version" label="版本" width="55" />
                    <el-table-column label="毛/皮/净(kg)" min-width="170">
                      <template #default="{ row }">{{ row.grossKg ?? '—' }} / {{ row.tareBoxKg ?? 0 }}{{ row.tarePalletKg ? '+'+row.tarePalletKg : '' }} → <b>{{ row.netKg }}</b></template>
                    </el-table-column>
                    <el-table-column label="状态" width="120">
                      <template #default="{ row }"><el-tag size="small" :type="WEIGHT_STATUS[row.status]?.type">{{ WEIGHT_STATUS[row.status]?.label || row.status }}</el-tag></template>
                    </el-table-column>
                    <el-table-column prop="revisionReason" label="修订原因" min-width="140" />
                  </el-table>
                </el-collapse-item>
              </el-collapse>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ============ 交接确认 ============ -->
      <el-tab-pane label="交接复称与确认" name="handovers">
        <ManifestTable :rows="manifests" @reload="loadManifests" />
      </el-tab-pane>

      <!-- ============ 待清洁区 ============ -->
      <el-tab-pane :label="`待清洁区 (${cleaning.length})`" name="cleaning">
        <el-alert type="info" :closable="false" style="margin-bottom:12px"
          title="空箱取回只进入待清洁区；清洁/检查并称量新皮重后才转为「清洁可用」，下一轮封装号递增，旧轮 QR 失效。" />
        <el-table :data="cleaning" border size="small">
          <el-table-column prop="code" label="箱号" width="100" />
          <el-table-column label="状态"><template #default="{ row }"><el-tag type="info">{{ BOX_STATUS[row.status]?.label }}</el-tag></template></el-table-column>
          <el-table-column prop="custodyUserName" label="取回人" width="120" />
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-input-number v-model="cleanTareMap[row.code]" :min="0" :precision="3" size="small" placeholder="新皮重" style="width:120px" />
              <el-button size="small" type="primary" @click="cleanBoxRow(row.code)">清洁完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新箱 -->
    <el-dialog v-model="boxDlg" title="登记虚构周转箱" width="380px">
      <el-input v-model="newBoxCode" placeholder="箱编号，如 C7（留空自动生成）" />
      <template #footer>
        <el-button @click="boxDlg=false">取消</el-button>
        <el-button type="primary" @click="createBox">登记</el-button>
      </template>
    </el-dialog>

    <!-- 新袋 -->
    <el-dialog v-model="bagDlg" title="发放一次性袋 / 唯一封口码" width="440px">
      <el-form label-width="92px">
        <el-form-item label="封口码"><el-input v-model="newSeal" placeholder="留空自动生成" /></el-form-item>
        <el-form-item label="虚构类别">
          <el-select v-model="newCat" style="width:100%">
            <el-option v-for="c in categories" :key="c.code" :label="`${c.code} ${c.name}`" :value="c.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bagDlg=false">取消</el-button>
        <el-button type="primary" @click="createBag">发放</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, BOX_STATUS, WEIGHT_STATUS, yuan, fmtTime } from '../api.js'
import { readScale } from '../util.js'
import ManifestTable from '../components/ManifestTable.vue'

const router = useRouter()
const tab = ref('boxes')
const boxes = ref([])
const bags = ref([])
const categories = ref([])
const manifests = ref([])
const cur = ref(null)
const selectedCode = ref(null)

const roundForm = ref({ tare: 2 })
const sealGross = ref(10)
const claimSeal = ref('')
const cleanTare = ref(2)
const cleanTareMap = ref({})
const boxDlg = ref(false)
const bagDlg = ref(false)
const newBoxCode = ref('')
const newSeal = ref('')
const newCat = ref('FW-INF')

const unusedBags = computed(() => bags.value.filter(b => b.status === 'UNUSED'))
const cleaning = computed(() => boxes.value.filter(b => b.status === 'PENDING_CLEANING'))
const activeManifestCode = computed(() => {
  if (!cur.value?.activeManifestId) return null
  const m = manifests.value.find(x => x.id === cur.value.activeManifestId)
  return m?.code
})

async function loadBoxes() {
  boxes.value = await api.get('/boxes')
  if (selectedCode.value) {
    try { cur.value = await api.get('/boxes/' + selectedCode.value) } catch { cur.value = null }
  }
}
async function loadBags() { bags.value = await api.get('/bags') }
async function loadManifests() { manifests.value = await api.get('/manifests') }
async function reload() { await Promise.all([loadBoxes(), loadBags(), loadManifests()]) }

async function selectBox(row) {
  selectedCode.value = row.code
  cur.value = await api.get('/boxes/' + row.code)
}

async function readTare() { const v = await readScale('SC-H1'); if (v != null) roundForm.value.tare = v }
async function readCleanTare() { const v = await readScale('SC-H1'); if (v != null) cleanTare.value = v }
async function readGross() { const v = await readScale('SC-H1'); if (v != null) sealGross.value = v }

async function startRound() {
  try {
    await api.post(`/boxes/${cur.value.code}/rounds`, { actualTareKg: roundForm.value.tare, deviceCode: 'SC-H1' })
    ElMessage.success('已开始新一轮封装')
    await reload(); await selectBox({ code: cur.value.code })
  } catch (e) { ElMessage.error(e.message) }
}
async function claimBag() {
  if (!claimSeal.value) return ElMessage.warning('请选择或扫描封口码')
  try {
    await api.post('/scan/claim-bag', { boxQr: cur.value.currentRound.qrCode, sealCode: claimSeal.value })
    ElMessage.success('袋码已加入本轮有效清单')
    claimSeal.value = ''
    await reload(); await selectBox({ code: cur.value.code })
  } catch (e) {
    ElMessage.error(e.data?.message || e.message)
  }
}
async function removeBag(seal) {
  await api.del(`/box-rounds/${cur.value.currentRound.id}/bags/${seal}`)
  await reload(); await selectBox({ code: cur.value.code })
}
async function sealBox() {
  try {
    await ElMessageBox.confirm('封箱后清单锁定，封箱不等于交车。确认封箱？', '封箱确认', { type: 'warning' })
    await api.post(`/box-rounds/${cur.value.currentRound.id}/seal`, { grossKg: sealGross.value, deviceCode: 'SC-H1' })
    ElMessage.success('封箱成功，已形成 SEAL 重量证据')
    await reload(); await selectBox({ code: cur.value.code })
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function cleanBox() {
  try {
    await api.post(`/boxes/${cur.value.code}/clean`, { newTareKg: cleanTare.value })
    ElMessage.success('清洁完成，箱体转为可用')
    await reload(); await selectBox({ code: cur.value.code })
  } catch (e) { ElMessage.error(e.message) }
}
async function cleanBoxRow(code) {
  const tare = cleanTareMap.value[code]
  if (tare == null) return ElMessage.warning('请填写清洁后皮重')
  try { await api.post(`/boxes/${code}/clean`, { newTareKg: tare }); ElMessage.success('已转为清洁可用'); await loadBoxes() }
  catch (e) { ElMessage.error(e.message) }
}
async function createBox() {
  try { await api.post('/boxes', { code: newBoxCode.value || undefined }); boxDlg.value = false; newBoxCode.value = ''; ElMessage.success('已登记'); await loadBoxes() }
  catch (e) { ElMessage.error(e.message) }
}
async function createBag() {
  try { await api.post('/bags', { sealCode: newSeal.value || undefined, categoryCode: newCat.value }); bagDlg.value = false; newSeal.value = ''; ElMessage.success('封口码已发放'); await loadBags() }
  catch (e) { ElMessage.error(e.message) }
}

onMounted(async () => {
  categories.value = await api.get('/categories')
  await reload()
  if (boxes.value[0]) selectBox(boxes.value[0])
})
</script>

<style scoped>
.action-box { padding: 6px 0 12px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
