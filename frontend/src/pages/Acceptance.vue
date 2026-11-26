<template>
  <div class="page">
    <div class="page-title">六场景端到端验收自检</div>
    <div class="page-sub">与命令行 <span class="mono">node verify/run.mjs</span> 共用同一份场景脚本，全部走真实 HTTP API 与 PostgreSQL 状态/事务。运行前会自动调用 <span class="mono">/api/dev/reset</span> 重建虚构基线。</div>

    <el-card shadow="never" style="margin-bottom:14px">
      <div style="display:flex;align-items:center;gap:14px;flex-wrap:wrap">
        <el-button type="primary" size="large" :loading="running" @click="run">
          <el-icon><VideoPlay /></el-icon>&nbsp;{{ running ? '正在执行…' : '重置并运行全部验收场景' }}
        </el-button>
        <el-tag v-if="result" size="large" :type="result.summary.failed===0?'success':'danger'">
          场景 {{ result.summary.passed }}/{{ result.summary.scenarios }} 通过 ·
          断言 {{ result.summary.checkPassed }}/{{ result.summary.checkTotal }}
        </el-tag>
        <span v-if="result" class="muted">耗时 {{ ((new Date(result.finishedAt)-new Date(result.startedAt))/1000).toFixed(1) }}s · 后缀 {{ result.runSuffix }}</span>
      </div>
      <el-progress v-if="running" :percentage="100" :indeterminate="true" :duration="2" style="margin-top:12px" />
    </el-card>

    <template v-if="result">
      <el-result :icon="result.summary.failed===0?'success':'error'"
        :title="result.summary.failed===0?'ALL_DONE: true — 全部场景通过':'ALL_DONE: false — 存在失败断言'"
        :sub-title="`${result.summary.checkPassed}/${result.summary.checkTotal} 项断言通过；可在各角色页面查看本次生成的数据`" />

      <el-collapse v-model="activeNames">
        <el-collapse-item v-for="s in result.scenarios" :key="s.id" :name="s.id">
          <template #title>
            <span style="display:flex;align-items:center;gap:9px">
              <span style="font-size:17px">{{ s.pass ? '✅' : '❌' }}</span>
              <b>{{ s.title }}</b>
              <el-tag size="small" :type="s.pass?'success':'danger'">{{ s.checks.filter(c=>c.ok).length }}/{{ s.checks.length }}</el-tag>
              <span class="mono muted">{{ s.id }}</span>
            </span>
          </template>
          <div v-for="(c,i) in s.checks" :key="i" style="padding:4px 0;font-size:13.5px">
            <span :class="c.ok?'check-line-pass':'check-line-fail'">{{ c.ok ? '✓' : '✗' }}</span>
            <span style="margin-left:7px">{{ c.name }}</span>
            <div v-if="!c.ok && c.detail" class="fail-detail">{{ c.detail }}</div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>
    <el-empty v-else description="点击按钮运行：两人收同一箱 / 合托缺一箱 / 到厂少2kg / 袋码跨箱重复 / 旧称量修订 / 处置回执丢失，外加两路净重20kg与空箱生命周期核对" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { runVerification } from '../verify/scenarios.mjs'

const running = ref(false)
const result = ref(null)
const activeNames = ref([])

async function run() {
  running.value = true
  result.value = null
  try {
    // 浏览器内走 vite/nginx 同源代理，baseUrl 为空
    result.value = await runVerification({ baseUrl: '' })
    activeNames.value = result.value.scenarios.filter(s => !s.pass).map(s => s.id)
    if (result.value.summary.failed === 0) ElMessage.success('全部验收场景通过')
    else ElMessage.error('存在失败场景，请展开查看')
  } catch (e) {
    ElMessage.error('验收执行异常：' + e.message)
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.fail-detail {
  margin: 5px 0 5px 22px; padding: 8px 10px; background: #fef2f2; border-left: 3px solid #ef4444;
  border-radius: 4px; color: #991b1b; font-size: 12.5px; white-space: pre-wrap; word-break: break-all;
  max-height: 220px; overflow: auto;
}
</style>
