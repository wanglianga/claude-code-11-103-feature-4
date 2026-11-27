<template>
  <router-view v-if="route.path === '/login'" />
  <el-container v-else class="app-shell">
    <el-header class="topbar">
      <div class="brand" @click="$router.push('/')">
        <el-icon :size="22" color="#0f766e"><FirstAidKit /></el-icon>
        <div>
          <div class="brand-title">医疗废物收运交接平台</div>
          <div class="brand-sub">封口码 · 轮次箱 · 托盘皮重 · 两路重量证据</div>
        </div>
      </div>
      <el-menu :default-active="activeMenu" mode="horizontal" router class="nav-menu" ellipsis>
        <el-menu-item index="/"><el-icon><Search /></el-icon>公开追踪</el-menu-item>
        <el-menu-item v-if="can('HOSPITAL')" index="/hospital"><el-icon><OfficeBuilding /></el-icon>医院端</el-menu-item>
        <el-menu-item v-if="can('TRANSPORTER')" index="/transport"><el-icon><Van /></el-icon>收运端</el-menu-item>
        <el-menu-item v-if="can('PLANT')" index="/plant"><el-icon><SetUp /></el-icon>处置厂端</el-menu-item>
        <el-menu-item index="/zone"><el-icon><Switch /></el-icon>跨区派发/防护</el-menu-item>
        <el-menu-item index="/discrepancies"><el-icon><Warning /></el-icon>差异中心</el-menu-item>
        <el-menu-item index="/simulator"><el-icon><Cpu /></el-icon>模拟器</el-menu-item>
        <el-menu-item index="/acceptance"><el-icon><CircleCheck /></el-icon>验收自检</el-menu-item>
        <el-menu-item index="/orgs"><el-icon><Collection /></el-icon>虚构机构</el-menu-item>
      </el-menu>
      <div class="user-area">
        <span class="fictional-badge">随题虚构规则</span>
        <el-dropdown v-if="session.user" @command="onUser">
          <span class="user-chip">
            <el-tag :type="roleTag" size="small" effect="dark">{{ roleLabel }}</el-tag>
            <span class="uname">{{ session.user.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="accounts">切换测试账号</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button v-else size="small" type="primary" @click="$router.push('/login')">登录</el-button>
      </div>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
    <el-footer class="footer">
      所有机构、人员、资质、类别与 0.5kg 差异容限均为随题虚构；不连接真实监管或处置设备。
    </el-footer>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session, logout } from './api.js'

const route = useRoute()
const router = useRouter()
const activeMenu = computed(() => {
  if (route.path.startsWith('/manifest')) return ''
  return route.path
})

const ROLE_LABEL = { HOSPITAL: '医院', TRANSPORTER: '收运', PLANT: '处置厂', ADMIN: '管理员' }
const roleLabel = computed(() => ROLE_LABEL[session.user?.role] || session.user?.role || '')
const roleTag = computed(() => ({ HOSPITAL: 'success', TRANSPORTER: 'primary', PLANT: 'warning', ADMIN: 'danger' }[session.user?.role] || 'info'))

function can(role) {
  return session.user && (session.user.role === role || session.user.role === 'ADMIN')
}
function onUser(cmd) {
  if (cmd === 'logout') { logout(); router.push('/login') }
  if (cmd === 'accounts') router.push('/login?switch=1')
}
</script>

<style scoped>
.app-shell { min-height: 100%; }
.topbar {
  display: flex; align-items: center; gap: 18px; background: #fff;
  border-bottom: 1px solid #e5e7eb; padding: 0 18px; height: 60px;
  position: sticky; top: 0; z-index: 100;
}
.brand { display: flex; align-items: center; gap: 9px; cursor: pointer; white-space: nowrap; }
.brand-title { font-weight: 800; font-size: 16px; color: #0f172a; line-height: 1.2; }
.brand-sub { font-size: 11px; color: #94a3b8; }
.nav-menu { flex: 1; border-bottom: none !important; min-width: 0; }
.user-area { display: flex; align-items: center; gap: 12px; white-space: nowrap; }
.user-chip { display: flex; align-items: center; gap: 7px; cursor: pointer; outline: none; }
.uname { font-size: 13px; font-weight: 600; }
.main { padding: 0; background: #f4f6fa; }
.footer {
  text-align: center; color: #9ca3af; font-size: 12px; padding: 18px; height: auto;
}
</style>
