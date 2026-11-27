#!/usr/bin/env node
// feature-4 跨区派发/防护用品验收。用法：node verify/run-zone.mjs [BASE_URL]
// 默认访问 http://127.0.0.1:8080 ；也可传 Vite 代理地址 http://127.0.0.1:5173。
import { runZoneVerification } from '../frontend/src/verify/zoneScenarios.mjs'

const base = process.argv[2] || process.env.BASE_URL || 'http://127.0.0.1:8080'
console.log(`\n跨区人员任务与防护用品 · 端到端验收（13 个场景）`)
console.log(`目标 API：${base}\n`)

const result = await runZoneVerification({ baseUrl: base })

let failedChecks = 0
for (const s of result.scenarios) {
  console.log(`${s.pass ? '✅' : '❌'} ${s.id} — ${s.title}`)
  for (const c of s.checks) {
    if (!c.ok) {
      failedChecks++
      console.log(`   ✗ ${c.name}`)
      if (c.detail) console.log(`     └ ${String(c.detail).split('\n').slice(0, 5).join('\n       ')}`)
    }
  }
  if (s.pass) console.log(`   （${s.checks.length} 项断言全部通过）`)
  console.log('')
}

const { summary } = result
console.log('────────────────────────────────────────')
console.log(`跨区场景 ${summary.passed}/${summary.scenarios} 通过；断言 ${summary.checkPassed}/${summary.checkTotal} 通过`)
if (summary.failed > 0) {
  console.log(`\nALL_DONE: false（${summary.failed} 个场景 / ${summary.checkFailed} 项断言失败）`)
  process.exit(1)
}
console.log('\nALL_DONE: true（跨区派发、状态前提、用品库存、独立复核与现场清单全部通过）')
process.exit(0)
