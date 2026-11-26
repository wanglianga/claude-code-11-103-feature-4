import { reactive } from 'vue'

const TOKEN_KEY = 'mw_token'
const USER_KEY = 'mw_user'

export const session = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
})

export function setSession(token, user) {
  session.token = token
  session.user = user
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function logout() {
  session.token = ''
  session.user = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export class ApiError extends Error {
  constructor(status, data) {
    super(data?.message || `请求失败（${status}）`)
    this.status = status
    this.code = data?.code
    this.data = data
  }
}

export async function request(method, path, body) {
  let res
  try {
    res = await fetch('/api' + path, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(session.token ? { Authorization: 'Bearer ' + session.token } : {})
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (e) {
    throw new ApiError(0, { code: 'NETWORK_ERROR', message: '无法连接后端服务：' + e.message })
  }
  const text = await res.text()
  let data = null
  if (text) {
    try { data = JSON.parse(text) } catch { data = { raw: text } }
  }
  if (!res.ok) {
    if (res.status === 401) logout()
    throw new ApiError(res.status, data || { message: res.statusText })
  }
  return data
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b) => request('POST', p, b ?? {}),
  patch: (p, b) => request('PATCH', p, b ?? {}),
  del: (p) => request('DELETE', p)
}

export function yuan(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v).toFixed(2).replace(/\.?0+$/, '')
}

export function fmtTime(t) {
  if (!t) return '—'
  try {
    const d = new Date(t)
    if (Number.isNaN(d.getTime())) return String(t)
    const p = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  } catch {
    return String(t)
  }
}

export const MANIFEST_STATUS = {
  DRAFT: { label: '建单中', type: 'info' },
  SUBMITTED: { label: '待医院确认', type: 'warning' },
  HANDED_OVER: { label: '已交车·在途', type: 'primary' },
  AT_PLANT: { label: '到厂·待实接', type: 'warning' },
  IN_PLANT_PENDING_CHECK: { label: '厂内待核', type: 'danger' },
  READY_TO_SIGN: { label: '待签收', type: 'warning' },
  SIGNED: { label: '厂已签收', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' }
}

export const BOX_STATUS = {
  AVAILABLE_CLEAN: { label: '清洁可用', type: 'success' },
  PACKING: { label: '封装中', type: 'warning' },
  SEALED: { label: '已封箱·未交车', type: 'warning' },
  IN_TRANSIT: { label: '在途（收运保管）', type: 'primary' },
  AT_PLANT: { label: '已到厂', type: 'primary' },
  PLANT_PENDING_CHECK: { label: '厂内待核', type: 'danger' },
  PLANT_RECEIVED: { label: '厂已签收', type: 'success' },
  PENDING_CLEANING: { label: '待清洁区', type: 'info' }
}

export const WEIGHT_STATUS = {
  PROPOSED: { label: '待共同确认', type: 'warning' },
  EFFECTIVE: { label: '有效', type: 'success' },
  DISPUTED: { label: '超差有争议', type: 'danger' },
  SUPERSEDED: { label: '已被修订替代', type: 'info' },
  HELD: { label: '暂挂（清单不完整）', type: 'danger' }
}
