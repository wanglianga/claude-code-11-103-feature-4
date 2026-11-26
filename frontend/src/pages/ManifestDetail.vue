<template>
  <div class="page" v-loading="!mf">
    <template v-if="mf">
      <div style="display:flex;align-items:center;gap:12px;margin-bottom:6px;flex-wrap:wrap">
        <el-button :icon="ArrowLeft" text @click="$router.back()">返回</el-button>
        <span class="page-title" style="margin:0">交接单 <span class="mono">{{ mf.code }}</span></span>
        <el-tag size="large" :type="MANIFEST_STATUS[mf.status]?.type">{{ MANIFEST_STATUS[mf.status]?.label || mf.status }}</el-tag>
        <el-tag v-if="mf.weightBasis" effect="plain">{{ basisLabel(mf.weightBasis) }}</el-tag>
        <el-tag v-if="mf.openDiscrepancies?.length" type="danger">{{ mf.openDiscrepancies.length }} 项未结差异</el-tag>
        <span style="flex:1"></span>
        <el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
      <div class="page-sub">{{ mf.hospitalOrgName || '' }} → {{ mf.transporterOrgName || '' }} · 全部规则为随题虚构</div>

      <!-- 状态操作条 -->
      <el-card shadow="never" style="margin-bottom:14px">
        <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
          <el-input v-if="['DRAFT','SUBMITTED'].includes(mf.status)" v-model="scanCode" class="scan-input"
            style="width:300px" placeholder="扫描封口码 / C1#R1 / 箱号" @keyup.enter="scan">
            <template #prefix><el-icon><Aim /></el-icon></template>
          </el-input>
          <el-button v-if="['DRAFT','SUBMITTED'].includes(mf.status)" type="primary" @click="scan">扫码加入清单</el-button>
          <el-button v-if="['DRAFT'].includes(mf.status) && can('TRANSPORTER')" type="warning" @click="act('submit',()=>api.post(`/manifests/${code}/submit`),'已提交医院确认')">提交医院确认</el-button>
          <el-button v-if="['DRAFT','SUBMITTED'].includes(mf.status) && can('HOSPITAL')" type="danger" @click="handover">
            医院确认交车（转移保管责任）
          </el-button>
          <el-button v-if="['DRAFT','SUBMITTED'].includes(mf.status)" @click="cancel">作废此单</el-button>
          <el-button v-if="mf.status==='HANDED_OVER' && can('TRANSPORTER')" type="primary" @click="act('arrive',()=>api.post(`/manifests/${code}/arrive`),'已登记到厂')">车辆到厂登记</el-button>
          <el-button v-if="mf.status==='READY_TO_SIGN' && can('PLANT')" type="success" @click="sign">核实无误·正式签收</el-button>
          <el-button v-if="mf.status==='SIGNED' && (can('TRANSPORTER')||can('PLANT'))" @click="returnEmpties">取回空箱至待清洁区</el-button>
          <el-button @click="tryReturnTransit" plain>规则演示：尝试退回在途</el-button>
        </div>
      </el-card>

      <el-row :gutter="14">
        <el-col :xs="24" :lg="15">
          <!-- 基本信息 -->
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header><b>批次信息与认可重量</b></template>
            <div class="kv">
              <div class="k">车辆/车牌</div><div>{{ mf.vehiclePlate || '未指定' }}</div>
              <div class="k">收运员</div><div>{{ mf.transporterName }}</div>
              <div class="k">医院确认人</div><div>{{ mf.hospitalConfirmerName || '尚未确认' }}</div>
              <div class="k">交车时间</div><div>{{ fmtTime(mf.handedAt) }}</div>
              <div class="k">到厂时间</div><div>{{ fmtTime(mf.arrivedAt) }}</div>
              <div class="k">实接时间</div><div>{{ fmtTime(mf.intakeAt) }}</div>
              <div class="k">签收时间</div><div>{{ fmtTime(mf.signedAt) }}</div>
              <div class="k">认可总净重</div><div><b class="weight-ok" style="font-size:16px">{{ yuan(mf.totalRecognizedNetKg) }} kg</b>（{{ basisLabel(mf.weightBasis) }}）</div>
            </div>
            <el-alert v-if="mf.mandate" style="margin-top:10px" :closable="false" show-icon
              :type="mf.mandate.status==='COMPLETED' ? 'success' : 'error'"
              :title="`委托单 ${mf.mandate.code}：${mandateLabel(mf.mandate.status)}`" />
            <div v-if="mf.plantReceipt" style="margin-top:8px" class="muted">
              厂签收单 {{ mf.plantReceipt.code }} · {{ mf.plantReceipt.signedByName }} · 总重 {{ mf.plantReceipt.weightTotalKg }}kg
            </div>
          </el-card>

          <!-- 箱清单 + 逐箱复称 -->
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header>
              <div style="display:flex;justify-content:space-between;align-items:center">
                <b>箱清单与逐箱复称（BOX_DIRECT，保留各箱重量）</b>
                <el-button v-if="['DRAFT','SUBMITTED'].includes(mf.status)" size="small" @click="showPalletBox=!showPalletBox">
                  {{ showPalletBox ? '收起封托' : '组织封托合称' }}
                </el-button>
              </div>
            </template>

            <!-- 建封托 -->
            <div v-if="showPalletBox && ['DRAFT','SUBMITTED'].includes(mf.status)" style="border:1px dashed #cbd5e1;border-radius:8px;padding:10px;margin-bottom:12px">
              <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
                <el-select v-model="newLoad.palletCode" placeholder="选择托盘（皮重）" style="width:170px">
                  <el-option v-for="p in pallets" :key="p.code" :label="`${p.code}（皮重${p.tareKg}）`" :value="p.code" />
                </el-select>
                <el-select v-model="newLoad.qrs" multiple placeholder="纳入完整封托清单的箱" style="min-width:320px;flex:1">
                  <el-option v-for="i in mf.items" :key="i.qrCode" :label="`${i.qrCode}（袋${i.bagCount}）`" :value="i.qrCode" />
                </el-select>
                <el-button type="primary" @click="createLoad">生成封托清单</el-button>
              </div>
            </div>

            <el-table :data="mf.items" size="small" border>
              <el-table-column prop="qrCode" label="箱/轮次" width="110" />
              <el-table-column label="袋数" width="55" prop="bagCount" />
              <el-table-column label="空箱重" width="70"><template #default="{row}">{{ row.actualTareKg }}</template></el-table-column>
              <el-table-column label="封箱认可净" width="85"><template #default="{row}">{{ yuan(row.recognizedNetKg) }}</template></el-table-column>
              <el-table-column v-if="['DRAFT','SUBMITTED'].includes(mf.status)" label="交接复称毛重" min-width="230">
                <template #default="{ row }">
                  <div style="display:flex;gap:5px;align-items:center">
                    <el-input-number v-model="boxGross[row.boxRoundId]" :min="0" :precision="3" size="small" controls="false" style="width:105px" placeholder="毛重" />
                    <el-button size="small" @click="readForBox(row.boxRoundId)">读秤</el-button>
                    <el-button size="small" type="primary" @click="proposeBox(row.qrCode)">录入</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>

            <!-- 托盘装载与合称 -->
            <div v-for="l in mf.palletLoads" :key="l.id" style="margin-top:12px;border:1px solid #e2e8f0;border-radius:8px;padding:10px">
              <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
                <b>托盘 {{ l.palletCode }}</b>
                <el-tag size="small" :type="l.status==='COMPLETE'?'success':l.status==='BLOCKED'?'danger':'info'">
                  声明 {{ l.declaredCount ?? l.declaredQrs?.length }} / 实扫 {{ l.scannedCount ?? l.scannedQrs?.length }} · {{ l.status }}
                </el-tag>
                <span class="muted">皮重 {{ l.tarePalletKg }}kg；认可托总净 {{ yuan(l.recognizedTotalNetKg) }}kg</span>
              </div>
              <div class="muted" style="margin:5px 0">声明：{{ (l.declaredQrs||[]).join('，') || '—' }}；已扫：{{ (l.scannedQrs||[]).join('，') || '—' }}</div>
              <div v-if="['DRAFT','SUBMITTED'].includes(mf.status)" style="display:flex;gap:8px;margin-top:6px;flex-wrap:wrap">
                <el-input v-model="loadScan[l.id]" size="small" style="width:180px" placeholder="扫入本托箱QR" />
                <el-button size="small" @click="scanLoad(l.id)">扫箱入托</el-button>
                <el-input-number v-model="palletGross[l.id]" :min="0" :precision="3" size="small" controls="false" style="width:120px" placeholder="合称毛重" />
                <el-button size="small" @click="readPallet(l.id)">读 SC-P1</el-button>
                <el-button size="small" type="warning" @click="proposePallet(l.id)">录入合托毛重</el-button>
              </div>
            </div>
          </el-card>

          <!-- 重量证据链 -->
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header><b>重量证据链（提议 → 共同确认；旧版本修订留痕）</b></template>
            <el-table :data="mf.weights" size="small" border>
              <el-table-column prop="subjectLabel" label="称量项" min-width="180" />
              <el-table-column label="环节/口径" width="150">
                <template #default="{row}">{{ stageLabel(row.stage) }} · {{ modeLabel(row.mode) }} <span class="muted">v{{ row.version }}</span></template>
              </el-table-column>
              <el-table-column label="毛/箱皮/托皮 → 净 (kg)" min-width="190">
                <template #default="{row}">
                  {{ row.grossKg ?? '—' }} / {{ row.tareBoxKg ?? 0 }} / {{ row.tarePalletKg ?? 0 }} → <b>{{ row.netKg }}</b>
                  <span v-if="row.diffKg != null" :class="Math.abs(row.diffKg)>0.5?'weight-bad':'weight-ok'">（差{{ row.diffKg }}）</span>
                </template>
              </el-table-column>
              <el-table-column label="状态/确认" width="150">
                <template #default="{row}">
                  <el-tag size="small" :type="WEIGHT_STATUS[row.status]?.type">{{ WEIGHT_STATUS[row.status]?.label || row.status }}</el-tag>
                  <div class="muted">{{ row.recordedByName }}<span v-if="row.confirmedByName"> / {{ row.confirmedByName }}</span></div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="170" fixed="right">
                <template #default="{row}">
                  <el-button v-if="row.status==='PROPOSED'" link type="success" size="small" @click="confirmWeight(row.id)">共同确认</el-button>
                  <el-button link type="primary" size="small" @click="openRevise(row)">修订</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- 到厂核实 -->
          <el-card v-if="['AT_PLANT','IN_PLANT_PENDING_CHECK','READY_TO_SIGN','SIGNED'].includes(mf.status)" shadow="never" style="margin-bottom:14px">
            <template #header><b>处置厂：资质 · 封条 · 到厂重量核实与实接</b></template>
            <template v-if="mf.status==='AT_PLANT' || mf.status==='IN_PLANT_PENDING_CHECK'">
              <el-table :data="mf.items" size="small" border style="margin-bottom:10px">
                <el-table-column prop="qrCode" label="箱" width="120" />
                <el-table-column label="到厂复称毛重" min-width="230">
                  <template #default="{ row }">
                    <el-input-number v-model="plantGross[row.boxRoundId]" :min="0" :precision="3" size="small" controls="false" style="width:130px" />
                    <el-button size="small" @click="readPlant(row.boxRoundId)">读 SC-P1</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button type="warning" @click="plantWeigh">提交到厂逐箱核实称（合计与认可总量比较，差&gt;0.5kg 自动挂差异）</el-button>
              <el-divider />
              <div style="display:flex;gap:18px;align-items:center;flex-wrap:wrap;margin-bottom:10px">
                <span>资质核验 <el-switch v-model="intake.qualOk" active-text="通过" inactive-text="异常" /></span>
                <span>封条核验 <el-switch v-model="intake.sealOk" active-text="完好" inactive-text="异常" /></span>
              </div>
              <el-input v-model="intake.note" type="textarea" :rows="2" placeholder="核验备注（资质/封条异常时说明）" style="margin-bottom:10px" />
              <el-button type="danger" @click="intakeSubmit">确认实物实接入厂（保管责任转处置厂）</el-button>
            </template>
            <el-result v-else icon="success" title="已完成实接/签收" :sub-title="`资质 ${mf.qualOk?'通过':'异常'}，封条 ${mf.sealOk?'完好':'异常'}`" />
          </el-card>

          <!-- 时间线 -->
          <el-card shadow="never">
            <template #header><b>保管与办理时间线</b></template>
            <el-timeline>
              <el-timeline-item v-for="e in mf.custodyEvents" :key="e.id" :timestamp="fmtTime(e.at)" placement="top">
                <b>{{ actionLabel(e.action) }}</b> · {{ e.actorName }}
                <div class="muted">{{ e.detail }}</div>
              </el-timeline-item>
            </el-timeline>
          </el-card>
        </el-col>

        <!-- 差异办理侧栏 -->
        <el-col :xs="24" :lg="9">
          <el-card shadow="never" style="margin-bottom:14px">
            <template #header><b>差异核实与补称（{{ mf.discrepancies?.length || 0 }}）</b></template>
            <el-empty v-if="!mf.discrepancies?.length" description="暂无差异案件" :image-size="60" />
            <div v-for="d in mf.discrepancies" :key="d.code" class="disc-card" :class="{resolved:d.status==='RESOLVED'}">
              <div style="display:flex;gap:8px;align-items:center">
                <el-tag :type="d.status==='OPEN'?'danger':'success'" size="small">{{ d.status==='OPEN'?'未结':'已结案' }}</el-tag>
                <b>{{ discType(d.type) }}</b>
                <span class="mono muted" style="margin-left:auto">{{ d.code }}</span>
              </div>
              <div style="margin:7px 0;font-size:13px">{{ d.title }}</div>
              <div class="muted" style="font-size:12.5px">{{ d.description }}</div>
              <div v-if="d.diffValue!=null" style="margin:6px 0;font-size:13px">
                期望 {{ d.expectedValue }} / 实测 {{ d.observedValue }} / <b :class="Math.abs(d.diffValue)>0.5?'weight-bad':''">差 {{ d.diffValue }}kg</b>
              </div>

              <template v-if="d.status==='OPEN'">
                <!-- 重量差异：逐箱补称 -->
                <div v-if="d.type==='WEIGHT_MISMATCH'" style="background:#fff;border-radius:7px;padding:8px;margin:8px 0">
                  <div class="muted" style="margin-bottom:6px">安排逐箱补称（必须取得真实读数，不能按袋数比例摊造）：</div>
                  <div v-for="i in mf.items" :key="i.boxRoundId" style="display:flex;gap:6px;align-items:center;margin-bottom:5px">
                    <span class="mono" style="width:96px;font-size:12px">{{ i.qrCode }}</span>
                    <el-input-number v-model="suppGross[i.boxRoundId]" :min="0" :precision="3" size="small" controls="false" style="width:120px" placeholder="补称毛重" />
                  </div>
                  <el-button size="small" type="warning" @click="supplement(d.code)">提交补称证据</el-button>
                </div>
                <el-select v-model="resForm[d.code].type" size="small" style="width:100%;margin:6px 0">
                  <el-option v-for="o in resolutionOptions(d.type)" :key="o.value" :label="o.label" :value="o.value" />
                </el-select>
                <el-input v-model="resForm[d.code].note" type="textarea" :rows="2" size="small" placeholder="处理说明（缺箱另单/维持原重原因等）" />
                <el-button size="small" type="success" style="margin-top:7px" @click="resolve(d.code)">结案</el-button>
              </template>
              <div v-else class="muted" style="margin-top:6px;font-size:12.5px">
                {{ d.resolutionType }} · {{ d.resolvedByName }}：{{ d.resolutionNote }}
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 修订弹窗 -->
      <el-dialog v-model="reviseDlg" title="修订旧称量（新增版本，不覆盖历史）" width="420px">
        <el-form label-width="92px">
          <el-form-item label="新毛重 kg"><el-input-number v-model="reviseForm.grossKg" :min="0" :precision="3" style="width:100%" /></el-form-item>
          <el-form-item label="修订原因"><el-input v-model="reviseForm.reason" type="textarea" :rows="3" placeholder="必须填写，例如旧秤零点漂移/抄录错误" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reviseDlg=false">取消</el-button>
          <el-button type="primary" @click="submitRevise">提交修订</el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { api, session, MANIFEST_STATUS, WEIGHT_STATUS, yuan, fmtTime } from '../api.js'
import { readScale } from '../util.js'

const route = useRoute()
const code = route.params.code
const mf = ref(null)
const pallets = ref([])
const scanCode = ref('')
const boxGross = reactive({})
const palletGross = reactive({})
const plantGross = reactive({})
const suppGross = reactive({})
const loadScan = reactive({})
const newLoad = reactive({ palletCode: 'P1', qrs: [], show: false })
const showPalletBox = ref(false)
const intake = reactive({ qualOk: true, sealOk: true, note: '' })
const resForm = reactive({})
const reviseDlg = ref(false)
const reviseForm = reactive({ weightId: null, grossKg: 10, reason: '' })

function can(...roles) { return session.user && (session.user.role === 'ADMIN' || roles.includes(session.user.role)) }

async function load() {
  try {
    mf.value = await api.get('/manifests/' + code)
    for (const d of mf.value.discrepancies || []) {
      if (!resForm[d.code]) resForm[d.code] = { type: resolutionOptions(d.type)[0]?.value, note: '' }
    }
  } catch (e) { ElMessage.error(e.message) }
}
onMounted(async () => { pallets.value = await api.get('/pallets'); load() })

async function act(what, fn, okMsg) {
  try { await fn(); ElMessage.success(okMsg); await load() } catch (e) { ElMessage.error(e.data?.message || e.message) }
}

async function scan() {
  try { await api.post(`/manifests/${code}/scan`, { scanCode: scanCode.value }); scanCode.value = ''; ElMessage.success('已扫入'); await load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}
async function proposeBox(qr) {
  const roundId = mf.value.items.find(i => i.qrCode === qr)?.boxRoundId
  const gross = boxGross[roundId]
  if (gross == null) return ElMessage.warning('请填写或读取复称毛重')
  try { await api.post('/weighing/handover-box', { manifestCode: code, boxQr: qr, grossKg: gross, deviceCode: 'SC-T1' }); ElMessage.success('复称读数已录入，待对方共同确认'); await load() }
  catch (e) { ElMessage.error(e.message) }
}
async function readForBox(rid) { const v = await readScale('SC-T1'); if (v != null) boxGross[rid] = v }
async function confirmWeight(id) {
  try {
    const r = await api.post(`/weighing/${id}/confirm`, {})
    if (r.blocked) { ElMessage.error(r.message || '封托清单不完整，已转缺箱差异'); }
    else if (r.code === 'WEIGHT_DISPUTED') ElMessage.warning('差值超 0.5kg，已挂差异核实，认可重量不变')
    else ElMessage.success('共同确认完成，重量证据生效')
    await load()
  } catch (e) { ElMessage.error(e.data?.message || e.message) }
}
async function createLoad() {
  if (!newLoad.palletCode || !newLoad.qrs.length) return ElMessage.warning('选择托盘和至少一箱')
  try { await api.post(`/manifests/${code}/pallet-load`, { palletCode: newLoad.palletCode, boxQrs: newLoad.qrs }); ElMessage.success('封托清单已建立'); showPalletBox.value = false; newLoad.qrs = []; await load() }
  catch (e) { ElMessage.error(e.message) }
}
async function scanLoad(id) {
  try { await api.post(`/pallet-loads/${id}/scan`, { boxQr: loadScan[id] }); loadScan[id] = ''; ElMessage.success('已扫入托'); await load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}
async function proposePallet(id) {
  const gross = palletGross[id]
  if (gross == null) return ElMessage.warning('请填写合称毛重')
  try { await api.post('/weighing/pallet', { loadId: id, grossKg: gross, deviceCode: 'SC-P1' }); ElMessage.success('合托毛重已录入，待共同确认（仅证明托总量）'); await load() }
  catch (e) { ElMessage.error(e.message) }
}
async function readPallet(id) { const v = await readScale('SC-P1'); if (v != null) palletGross[id] = v }

async function handover() {
  try {
    await ElMessageBox.confirm('确认后箱/托保管责任立即由医院转移给收运方，且并发的第二接收单会失败。是否确认交车？', '医院交接确认', { type: 'warning' })
    await api.post(`/manifests/${code}/handover`, {})
    ElMessage.success('交车确认成功，保管责任已转移')
    await load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.data?.message || e.message) }
}
async function cancel() {
  try { await ElMessageBox.confirm('确定作废该扫码单？未发生保管转移。', '作废', { type: 'warning' }); await api.post(`/manifests/${code}/cancel`, {}); ElMessage.success('已作废'); await load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function tryReturnTransit() {
  try { await api.post(`/manifests/${code}/return-to-transit`, {}); ElMessage.success('操作成功（不应发生）') }
  catch (e) { ElMessageBox.alert(e.data?.message || e.message, e.data?.code || '规则拦截', { type: 'error', title: '不能退回在途' }) }
}
async function plantWeigh() {
  const readings = mf.value.items.map(i => ({ boxRoundId: i.boxRoundId, grossKg: plantGross[i.boxRoundId], deviceCode: 'SC-P1' })).filter(r => r.grossKg != null)
  if (!readings.length) return ElMessage.warning('至少填写一箱毛重')
  try {
    const r = await api.post(`/manifests/${code}/plant-weigh`, { readings })
    if (r.discrepancy) ElMessage.error(`合计差 ${r.diffKg}kg，超过 0.5kg，已转厂内差异核实`)
    else ElMessage.success(`到厂合计净 ${r.plantTotalNetKg}kg，在容差内`)
    await load()
  } catch (e) { ElMessage.error(e.message) }
}
async function readPlant(rid) { const v = await readScale('SC-P1'); if (v != null) plantGross[rid] = v }
async function intakeSubmit() {
  try { await api.post(`/manifests/${code}/intake`, { ...intake }); ElMessage.success('实接完成，保管责任转处置厂'); await load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}
async function sign() {
  try { await ElMessageBox.confirm('差异全部结案后才能签收。确认签收？', '厂签收', { type: 'success' }); await api.post(`/manifests/${code}/sign`, { note: '核实无误签收' }); ElMessage.success('已签收'); await load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.data?.message || e.message) }
}
async function returnEmpties() {
  try { await ElMessageBox.confirm('卸料后取回空箱？空箱将进入「待清洁区」，不能直接复用。', '空箱取回', { type: 'info' }); const r = await api.post(`/manifests/${code}/return-empties`, {}); ElMessage.success(`回送单 ${r.returnCode}，${r.boxCodes.length} 个空箱进入待清洁区`); await load() }
  catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}
async function supplement(discCode) {
  const readings = mf.value.items.map(i => ({ boxRoundId: i.boxRoundId, grossKg: suppGross[i.boxRoundId], deviceCode: 'SC-P2' })).filter(r => r.grossKg != null)
  if (!readings.length) return ElMessage.warning('请填写至少一箱的真实补称毛重')
  try { await api.post(`/discrepancies/${discCode}/supplement-weigh`, { readings, note: '双方在场补称' }); ElMessage.success('补称证据已保存，可继续结案'); await load() }
  catch (e) { ElMessage.error(e.message) }
}
function resolutionOptions(type) {
  if (type === 'WEIGHT_MISMATCH') return [
    { value: 'ACCEPT_MEASURED', label: '按补称/复称实测认可' },
    { value: 'KEEP_DECLARED', label: '核实后维持原认可重量' }
  ]
  if (type === 'MISSING_BOX') return [
    { value: 'BOX_HANDLED_SEPARATELY', label: '缺箱已找回/另单交接' },
    { value: 'OTHER', label: '其他处理（必填说明）' }
  ]
  return [{ value: 'OTHER', label: '其他处理（必填说明）' }]
}
async function resolve(discCode) {
  const f = resForm[discCode]
  try { await api.post(`/discrepancies/${discCode}/resolve`, { resolutionType: f.type, note: f.note }); ElMessage.success('差异已结案'); await load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}
function openRevise(row) { reviseForm.weightId = row.id; reviseForm.grossKg = Number(row.grossKg); reviseForm.reason = ''; reviseDlg.value = true }
async function submitRevise() {
  if (!reviseForm.reason) return ElMessage.warning('必须填写修订原因')
  try { await api.post(`/weighing/${reviseForm.weightId}/revise`, { grossKg: reviseForm.grossKg, reason: reviseForm.reason }); ElMessage.success('已生成新版本，历史记录保留'); reviseDlg.value = false; await load() }
  catch (e) { ElMessage.error(e.data?.message || e.message) }
}

function basisLabel(b) { return { BOX_DIRECT: '逐箱证据', PALLET_COMBINED: '合托总量', MIXED: '混合口径', PLANT_ADJUSTED: '补称后实测', NONE: '尚无重量' }[b] || b || '' }
function mandateLabel(s) { return { ACTIVE: '处置进行中，回执未完成', COMPLETED: '有效回执在档，委托结束', REVIEW_REQUIRED: '回执失效复核中，委托未结束' }[s] || s }
function stageLabel(s) { return { SEAL: '封箱', HANDOVER_JOINT: '交接复称', PLANT_CHECK: '到厂核实', SUPPLEMENT: '差异补称' }[s] || s }
function modeLabel(m) { return { BOX_DIRECT: '逐箱', PALLET_COMBINED: '合托', AGGREGATE: '合计' }[m] || m }
function discType(t) { return { WEIGHT_MISMATCH: '重量差异', MISSING_BOX: '缺箱/清单不符', DUPLICATE_BAG: '袋码重复', SEAL_BROKEN: '封条异常', QUALIFICATION_INVALID: '资质异常' }[t] || t }
function actionLabel(a) {
  return { ROUND_STARTED: '开始封装轮次', BOX_SEALED: '封箱', MANIFEST_CREATED: '创建交接单', CUSTODY_HANDOVER: '确认交车·保管转移', VEHICLE_ARRIVED: '车辆到厂', PLANT_INTAKE: '处置厂实接', PLANT_PENDING_CHECK: '转入厂内待核', PLANT_SIGNED: '处置厂签收', EMPTY_RETURNED: '空箱取回待清洁', MANIFEST_CANCELLED: '交接单取消', CLEANING_DONE: '清洁完成' }[a] || a
}
</script>

<style scoped>
.disc-card { border: 1px solid #fecaca; background: #fef2f2; border-radius: 9px; padding: 10px 12px; margin-bottom: 11px; }
.disc-card.resolved { border-color: #bbf7d0; background: #f0fdf4; }
</style>
