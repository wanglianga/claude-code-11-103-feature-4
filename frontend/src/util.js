import { api } from './api.js'
import { ElMessage } from 'element-plus'

/** 调用虚构称重仪模拟器读取当前稳定读数。 */
export async function readScale(device = 'SC-H1') {
  try {
    const r = await api.get(`/sim/scales/${device}/read`)
    ElMessage.success(`称重仪 ${device} 读数：${r.readingKg} kg（模拟）`)
    return Number(r.readingKg)
  } catch (e) {
    ElMessage.error('读秤失败：' + e.message)
    return null
  }
}

export async function loadScales() {
  try { return await api.get('/sim/scales') } catch { return [] }
}
