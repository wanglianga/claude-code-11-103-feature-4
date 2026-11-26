import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './api.js'

const routes = [
  { path: '/login', component: () => import('./pages/Login.vue'), meta: { public: true } },
  { path: '/', component: () => import('./pages/Track.vue'), meta: { public: true, title: '公开追踪' } },
  { path: '/hospital', component: () => import('./pages/Hospital.vue'), meta: { roles: ['HOSPITAL', 'ADMIN'], title: '医院工作台' } },
  { path: '/transport', component: () => import('./pages/Transport.vue'), meta: { roles: ['TRANSPORTER', 'ADMIN'], title: '收运工作台' } },
  { path: '/plant', component: () => import('./pages/Plant.vue'), meta: { roles: ['PLANT', 'ADMIN'], title: '处置厂工作台' } },
  { path: '/manifest/:code', component: () => import('./pages/ManifestDetail.vue'), meta: { title: '交接单详情' } },
  { path: '/discrepancies', component: () => import('./pages/Discrepancies.vue'), meta: { title: '差异中心' } },
  { path: '/simulator', component: () => import('./pages/Simulator.vue'), meta: { title: '硬件/回执模拟器' } },
  { path: '/acceptance', component: () => import('./pages/Acceptance.vue'), meta: { title: '六场景验收自检' } },
  { path: '/orgs', component: () => import('./pages/Orgs.vue'), meta: { title: '虚构机构与资质' } }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!session.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.roles && !to.meta.roles.includes(session.user.role)) {
    return { path: '/' }
  }
  return true
})

export default router
