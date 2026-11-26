<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="left">
        <el-icon :size="40" color="#fff"><FirstAidKit /></el-icon>
        <h1>医疗废物收运交接与重量证据平台</h1>
        <p>医院封箱 · 收运扫码 · 两路称重 · 到厂核实 · 差异闭环 · 处置回执</p>
        <ul>
          <li>一次性袋唯一封口码；周转箱编号必带本轮封装号</li>
          <li>逐箱复称保留各箱重量；合托合称只证明托内总量</li>
          <li>差 &gt; 0.5kg 转差异核实；厂内待核不可退回在途</li>
          <li>有效处置完成回执才能结束委托，回执丢失/撤回留痕并复核</li>
        </ul>
        <span class="fictional-badge light">全部规则与机构均为随题虚构</span>
      </div>
      <div class="right">
        <h2>选择测试账号登录</h2>
        <div class="acct-list">
          <div v-for="a in accounts" :key="a.username" class="acct" @click="login(a.username)">
            <el-tag :type="tag(a.username)" size="small">{{ role(a.username) }}</el-tag>
            <div class="acct-main">
              <div class="acct-name">{{ a.label }}</div>
              <div class="muted mono">{{ a.username }} / {{ a.password }}</div>
            </div>
            <el-button size="small" type="primary" plain :loading="loading===a.username">进入</el-button>
          </div>
        </div>
        <el-divider>或手动登录</el-divider>
        <el-form @submit.prevent>
          <el-form-item><el-input v-model="username" placeholder="用户名" size="large" /></el-form-item>
          <el-form-item><el-input v-model="password" type="password" placeholder="密码" size="large" show-password @keyup.enter="login()" /></el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="!!loading" @click="login()">登录</el-button>
        </el-form>
        <el-button link type="primary" style="width:100%;margin-top:10px" @click="$router.push('/')">
          无需登录，先体验公开追踪 →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, setSession } from '../api.js'
import { DEMO_ACCOUNTS } from '../verify/scenarios.mjs'

const router = useRouter()
const route = useRoute()
const accounts = DEMO_ACCOUNTS
const username = ref('hospital01')
const password = ref('123456')
const loading = ref('')

const ROLE_NAME = { hospital01: '医院', hospital02: '医院', kangning01: '医院', transport01: '收运', transport02: '收运', shunda01: '收运', plant01: '处置厂', plant02: '处置厂', admin: '管理员' }
const role = (u) => ROLE_NAME[u] || '用户'
const tag = (u) => ({ 医院: 'success', 收运: 'primary', 处置厂: 'warning', 管理员: 'danger' }[role(u)])

async function login(presetUser) {
  const u = presetUser ? presetUser : username.value
  const p = presetUser ? (DEMO_ACCOUNTS.find(a => a.username === u)?.password || '123456') : password.value
  loading.value = u
  try {
    const r = await api.post('/auth/login', { username: u, password: p })
    setSession(r.token, r.user)
    ElMessage.success(`欢迎，${r.user.displayName}`)
    const redirect = route.query.redirect
    if (redirect && typeof redirect === 'string') router.push(redirect)
    else router.push(homeFor(r.user.role))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = ''
  }
}
function homeFor(role) {
  return { HOSPITAL: '/hospital', TRANSPORTER: '/transport', PLANT: '/plant', ADMIN: '/acceptance' }[role] || '/'
}
</script>

<style scoped>
.login-wrap { min-height: 100vh; display: grid; place-items: center; background: linear-gradient(135deg, #0f766e22, #1d4ed822); padding: 24px; }
.login-card { display: grid; grid-template-columns: 1.05fr .95fr; background: #fff; border-radius: 18px; overflow: hidden; max-width: 1080px; width: 100%; box-shadow: 0 24px 70px rgba(15,23,42,.18); }
.left { background: linear-gradient(150deg, #0f766e, #155e75 55%, #1e3a8a); color: #fff; padding: 40px 36px; }
.left h1 { font-size: 27px; line-height: 1.35; margin: 18px 0 10px; }
.left p { opacity: .92; font-size: 14px; }
.left ul { padding-left: 18px; font-size: 13.5px; line-height: 2.1; opacity: .95; margin: 18px 0 26px; }
.fictional-badge.light { background: rgba(254,243,199,.95); }
.right { padding: 36px 38px; }
.right h2 { margin: 0 0 18px; font-size: 19px; }
.acct-list { display: grid; gap: 9px; margin-bottom: 8px; max-height: 330px; overflow: auto; padding-right: 4px; }
.acct { display: flex; align-items: center; gap: 11px; border: 1px solid #e5e7eb; border-radius: 10px; padding: 9px 12px; cursor: pointer; transition: .15s; }
.acct:hover { border-color: #0f766e; background: #f0fdfa; transform: translateY(-1px); }
.acct-main { flex: 1; min-width: 0; }
.acct-name { font-size: 14px; font-weight: 600; }
@media (max-width: 860px) { .login-card { grid-template-columns: 1fr; } .left { display: none; } }
</style>
