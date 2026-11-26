#!/usr/bin/env node
// 用法：node verify/run.mjs [BASE_URL]
// 默认访问本机 8080 的 Spring Boot。
import { runVerification } from '../frontend/src/verify/scenarios.mjs'

const base = process.argv[2] || process.env.BASE_URL || 'http://127.0.0.1:8080'

console.log(`\n医疗废物收运交接 · 六场景端到端验收`)
console.log(`目标 API：${base}\n`)

const result = await runVerification({ baseUrl: base })

let failedChecks = 0
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

const { summary } = result
console.log('────────────────────────────────────────')
console.log(`场景 ${summary.passed}/${summary.scenarios} 通过；断言 ${summary.checkPassed}/${summary.checkTotal} 通过`)
if (summary.failed > 0) {
  console.log(`\nALL_DONE: false（${summary.failed} 个场景 / ${summary.checkFailed} 项断言失败）`)
  process.exit(1)
}
console.log('\nALL_DONE: true（六场景及附加核对全部通过）')
process.exit(0)
