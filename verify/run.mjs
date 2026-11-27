#!/usr/bin/env node
// 一键端到端验收：原有 8 场景（92 断言）+ feature-4 跨区/防护 13 场景（114 断言）。
// 用法：node verify/run.mjs [BASE_URL]
// 默认访问本机 8080 的 Spring Boot；可传 http://127.0.0.1:5173 走 Vite 代理。
import { runVerification } from '../frontend/src/verify/scenarios.mjs'
import { runZoneVerification } from '../frontend/src/verify/zoneScenarios.mjs'

const base = process.argv[2] || process.env.BASE_URL || 'http://127.0.0.1:8080'

function printSuite(result, label) {
  let failedChecks = 0
  console.log(`\n${label}`)
  console.log(`目标 API：${base}\n`)
  for (const s of result.scenarios) {
    console.log(`${s.pass ? '✅' : '❌'} ${s.id} — ${s.title}`)
    for (const c of s.checks) {
      if (!c.ok) {
        failedChecks++
        console.log(`   ✗ ${c.name}`)
        if (c.detail) console.log(`     └ ${String(c.detail).split('\n').slice(0, 4).join('\n       ')}`)
      }
    }
    if (s.pass) console.log(`   （${s.checks.length} 项断言全部通过）`)
    console.log('')
  }
  return failedChecks
}

console.log(`\n医疗废物收运交接平台 · 全量端到端验收`)

const core = await runVerification({ baseUrl: base })
const coreFailed = printSuite(core, '【一】原有六场景 + 附加核对（核心套件）')
console.log('────────────────────────────────────────')
console.log(`核心：场景 ${core.summary.passed}/${core.summary.scenarios} 通过；断言 ${core.summary.checkPassed}/${core.summary.checkTotal} 通过`)

const zone = await runZoneVerification({ baseUrl: base })
const zoneFailed = printSuite(zone, '【二】feature-4 跨区人员任务与防护用品（跨区套件）')
console.log('────────────────────────────────────────')
console.log(`跨区：场景 ${zone.summary.passed}/${zone.summary.scenarios} 通过；断言 ${zone.summary.checkPassed}/${zone.summary.checkTotal} 通过`)

const totalScenarios = core.summary.scenarios + zone.summary.scenarios
const passedScenarios = core.summary.passed + zone.summary.passed
const totalChecks = core.summary.checkTotal + zone.summary.checkTotal
const passedChecks = core.summary.checkPassed + zone.summary.checkPassed
const failedScenarios = totalScenarios - passedScenarios
const failedChecks = totalChecks - passedChecks

console.log('════════════════════════════════════════')
console.log(`合计：场景 ${passedScenarios}/${totalScenarios}；断言 ${passedChecks}/${totalChecks}`)
if (coreFailed > 0 || zoneFailed > 0 || failedScenarios > 0) {
  console.log(`\nALL_DONE: false（${failedScenarios} 个场景 / ${failedChecks} 项断言失败）`)
  process.exit(1)
}
console.log(`\nALL_DONE: true（核心 ${core.summary.checkPassed} 项 + 跨区 ${zone.summary.checkPassed} 项，共 ${passedChecks} 项断言全部通过）`)
process.exit(0)
