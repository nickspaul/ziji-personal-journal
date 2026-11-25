<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, ArrowDown, ArrowUp, ChevronLeft, ChevronRight, ClipboardList, Pencil, Plus, Ruler, Scale, Trash2 } from 'lucide-vue-next'
import type { EChartsOption } from 'echarts'
import Chart from './Chart.vue'
import { state } from '../api'
import { bodyNumber, calculateBmi, formatBodyValue, sortBodyRows } from '../bodyMetrics'
import { shanghaiDate, shortDate } from '../types'
import type { Row } from '../types'

const emit = defineEmits<{ edit: [row?: Row]; remove: [row: Row] }>()
const rows = computed(() => sortBodyRows(state.collections.body || []))
const range = ref(90), page = ref(1), otherMetric = ref('bodyFatPct')
const pageSize = 12
const metricDefs = [
  { key: 'weightKg', label: '体重', unit: 'kg', color: '#81d4b2', lightColor: '#27795b', icon: Scale },
  { key: 'bodyFatPct', label: '体脂率', unit: '%', color: '#baabdb', lightColor: '#7e63a6', icon: Activity },
  { key: 'bmi', label: 'BMI', unit: 'kg/m²', color: '#a9b8eb', lightColor: '#546ba8', icon: Activity },
  { key: 'heightCm', label: '身高', unit: 'cm', color: '#b1b9c9', lightColor: '#54697a', icon: Ruler },
  { key: 'waistCm', label: '腰围', unit: 'cm', color: '#dfba91', lightColor: '#946831', icon: Ruler },
]
const value = (row: Row, key: string) => key === 'bmi' ? calculateBmi(row) : bodyNumber(row[key])
const cards = computed(() => metricDefs.map(metric => {
  const latest = rows.value.find(row => value(row, metric.key) != null)
  return { ...metric, date: latest?.measuredAt, value: latest ? value(latest, metric.key) : null }
}))
const startDate = computed(() => {
  if (!range.value) return ''
  const date = new Date(`${shanghaiDate()}T12:00:00+08:00`)
  date.setUTCDate(date.getUTCDate() - range.value + 1)
  return date.toISOString().slice(0, 10)
})
const filtered = computed(() => rows.value.filter(row => !startDate.value || String(row.measuredAt).slice(0, 10) >= startDate.value))
const chartRows = computed(() => filtered.value.slice().reverse())
const sameDayLabels = computed(() => {
  const grouped: Record<string, Row[]> = {}
  for (const row of rows.value.slice().reverse()) (grouped[String(row.measuredAt).slice(0, 10)] ||= []).push(row)
  const labels: Record<string, string> = {}
  for (const group of Object.values(grouped)) group.forEach((row, index) => { labels[row.id] = group.length > 1 ? `当日第 ${index + 1} 笔` : '' })
  return labels
})
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visibleRows = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(range, () => { page.value = 1 })
watch(totalPages, total => { page.value = Math.min(page.value, total) })
const otherDef = computed(() => metricDefs.find(metric => metric.key === otherMetric.value)!)
function points(key: string) { return chartRows.value.filter(row => value(row, key) != null) }
function change(key: string): number | null {
  const available = points(key)
  return available.length >= 2 ? Number(value(available[available.length - 1]!, key)) - Number(value(available[0]!, key)) : null
}
function chartOption(key: string): EChartsOption {
  const metric = metricDefs.find(item => item.key === key)!
  const light = state.settings.theme === 'light'
  const textColor = light ? '#53645d' : '#a4b0aa'
  const lineColor = light ? '#d9ded9' : '#35403d'
  const seriesColor = light ? metric.lightColor : metric.color
  return {
    animation: !state.settings.reducedMotion,
    grid: { left: 48, right: 18, top: 28, bottom: 43 },
    tooltip: {
      trigger: 'axis', confine: true, backgroundColor: light ? '#fff' : '#242e2a', borderColor: lineColor,
      textStyle: { color: textColor, fontSize: 11 },
      formatter: (params: any) => {
        const item = Array.isArray(params) ? params[0] : params
        const row = chartRows.value[item?.dataIndex]
        return row ? `${shortDate(row.measuredAt)} ${sameDayLabels.value[row.id] || ''}<br/>${metric.label}：${formatBodyValue(value(row, key))} ${metric.unit}` : ''
      },
    },
    xAxis: { type: 'category', data: chartRows.value.map(row => `${String(row.measuredAt).slice(5, 10)}${sameDayLabels.value[row.id] ? ` · ${sameDayLabels.value[row.id].replace('当日第 ', '').replace(' 笔', '')}` : ''}`), boundaryGap: true, axisTick: { show: false }, axisLine: { lineStyle: { color: lineColor } }, axisLabel: { color: textColor, fontSize: 11, hideOverlap: true } },
    yAxis: { type: 'value', name: metric.unit, scale: true, splitNumber: 4, axisLabel: { color: textColor, fontSize: 11 }, nameTextStyle: { color: textColor, fontSize: 11 }, splitLine: { lineStyle: { color: lineColor, type: 'dashed' } } },
    series: [{ type: 'line', name: metric.label, data: chartRows.value.map(row => value(row, key)), connectNulls: false, smooth: false, showSymbol: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2, color: seriesColor }, itemStyle: { color: seriesColor }, emphasis: { scale: true } }],
  }
}
</script>

<template>
  <div class="body-dashboard">
    <section v-if="!rows.length" class="panel first-measurement">
      <Scale :size="24" />
      <div><h3>还没有身体测量</h3><p>只填这次测到的项目即可。填入身高和体重后，会自动算出 BMI。</p></div>
      <button class="button primary" @click="emit('edit')"><Plus :size="15" />记第一次测量</button>
    </section>
    <template v-else>
    <section class="panel measurement-summary" aria-labelledby="latest-measurements">
      <header class="body-section-title">
        <h3 id="latest-measurements">最近测量值</h3>
        <span>各项取最近一次有数值的测量</span>
      </header>
      <div class="body-cards sensitive">
        <article v-for="metric in cards" :key="metric.key" class="body-card">
          <div class="body-card-title"><component :is="metric.icon" :size="15" /><span>{{ metric.label }}</span></div>
          <strong>{{ formatBodyValue(metric.value) }}<small>{{ metric.unit }}</small></strong>
          <span class="body-card-date">{{ metric.date ? shortDate(metric.date) : '尚未记录' }}</span>
        </article>
      </div>
    </section>
    <section class="panel trend-board" aria-label="身体变化趋势">
    <div class="body-range-heading"><div><h3>变化趋势</h3><p>按测量日期绘制，缺少的项目留空；同日按录入顺序编号，分别保留。</p></div><label><span class="visually-hidden">身体数据时间范围</span><select v-model.number="range"><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option><option :value="180">最近 180 天</option><option :value="0">全部记录</option></select></label></div>
    <div class="body-chart-grid sensitive">
      <section class="body-chart-panel"><header><div><h3>体重</h3><p v-if="change('weightKg') != null"><component :is="change('weightKg')! < 0 ? ArrowDown : ArrowUp" v-if="change('weightKg') !== 0" :size="12" />较所选范围首笔 {{ change('weightKg')! > 0 ? '+' : '' }}{{ formatBodyValue(change('weightKg')) }} kg</p><p v-else>至少两次有效测量后，可比较变化</p></div><span>{{ points('weightKg').length }} 次测量</span></header><Chart v-if="points('weightKg').length" :option="chartOption('weightKg')" label="体重测量趋势；数值及同日多次记录可在下方测量历史中查看" /><div v-else class="body-chart-empty"><Scale :size="27" /><p>这个时间范围还没有体重数据</p><button class="text-link" @click="emit('edit')">添加一次测量</button></div></section>
      <section class="body-chart-panel"><header><div><label class="body-chart-selector"><span class="visually-hidden">选择身体趋势指标</span><select v-model="otherMetric"><option value="bodyFatPct">体脂率</option><option value="bmi">BMI</option><option value="waistCm">腰围</option></select></label><p v-if="change(otherMetric) != null">较所选范围首笔 {{ change(otherMetric)! > 0 ? '+' : '' }}{{ formatBodyValue(change(otherMetric)) }} {{ otherMetric === 'bodyFatPct' ? '个百分点' : otherDef.unit }}</p><p v-else>{{ otherMetric === 'bmi' ? '同笔记录的身高、体重齐全后自动计算' : '填入测量值后展示趋势' }}</p></div><span>{{ points(otherMetric).length }} 次测量</span></header><Chart v-if="points(otherMetric).length" :option="chartOption(otherMetric)" :label="`${otherDef.label}测量趋势；详细数据可在下方测量历史中查看`" /><div v-else class="body-chart-empty"><Activity :size="27" /><p>{{ otherMetric === 'bmi' ? '还没有同时包含身高、体重的记录' : `这个时间范围还没有${otherDef.label}数据` }}</p><button class="text-link" @click="emit('edit')">添加一次测量</button></div></section>
    </div>
    </section>
    <section class="panel body-history"><header><div><h3>测量历史</h3><p>所选范围内 {{ filtered.length }} 笔 · 可以修正或删除单次测量</p></div><ClipboardList :size="19" /></header><div v-if="visibleRows.length" class="table-scroll sensitive"><table><thead><tr><th>测量日期</th><th>体重 kg</th><th>体脂率 %</th><th>BMI</th><th>身高 cm</th><th>腰围 cm</th><th>备注</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleRows" :key="row.id"><td>{{ shortDate(row.measuredAt) }}<small v-if="sameDayLabels[row.id]">{{ sameDayLabels[row.id] }}</small></td><td>{{ formatBodyValue(row.weightKg) }}</td><td>{{ formatBodyValue(row.bodyFatPct) }}</td><td>{{ formatBodyValue(calculateBmi(row)) }}</td><td>{{ formatBodyValue(row.heightCm) }}</td><td>{{ formatBodyValue(row.waistCm) }}</td><td class="measurement-note" :title="row.notes || ''">{{ row.notes || '—' }}</td><td><div class="row-actions"><button class="icon-button" :title="`编辑 ${shortDate(row.measuredAt)} ${sameDayLabels[row.id] || ''} 测量`" @click="emit('edit', row)"><Pencil :size="14" /></button><button class="icon-button" :title="`删除 ${shortDate(row.measuredAt)} ${sameDayLabels[row.id] || ''} 测量`" @click="emit('remove', row)"><Trash2 :size="14" /></button></div></td></tr></tbody></table></div><div v-else class="empty-state compact"><p>{{ rows.length ? '这个时间范围还没有测量，可以切换“全部记录”查看。' : '这里会保留你每次填写的身体数据。先记下今天测到的项目就好。' }}</p><button class="button secondary" @click="emit('edit')"><Plus :size="15" />新增测量</button></div><footer v-if="filtered.length"><span>第 {{ page }} / {{ totalPages }} 页</span><div><button class="icon-button" title="上一页测量" :disabled="page <= 1" @click="page--"><ChevronLeft :size="17" /></button><button class="icon-button" title="下一页测量" :disabled="page >= totalPages" @click="page++"><ChevronRight :size="17" /></button></div></footer></section>
    </template>
    <p class="body-source-note">BMI = 体重（kg）÷ 身高（m）²，使用同一笔测量的数据。它不能直接测量体脂率，也不能单独判断健康状况。<a href="https://www.cdc.gov/bmi/about/" target="_blank" rel="noopener noreferrer">计算说明 · CDC <ChevronRight :size="11" /></a></p>
  </div>
</template>

<style scoped>
.body-dashboard { display: grid; gap: 16px; min-width: 0; container: measurements / inline-size; }
.body-section-title, .body-range-heading, .body-history > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 18px; border-bottom: 1px solid var(--border); }
.body-dashboard h3 { margin: 0; font-size: 16px; font-weight: 640; }
.first-measurement { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 20px; }
.first-measurement > svg { color: var(--mint); }
.first-measurement p { margin: 4px 0 0; font-size: 13px; color: var(--muted); }
.first-measurement button { margin-left: auto; }
.body-section-title span, .body-range-heading p, .body-history header p, .body-source-note { font-size: 12px; color: var(--muted); line-height: 1.65; }
.body-cards { display: grid; grid-template-columns: repeat(5,minmax(0,1fr)); }
.body-card { padding: 15px 18px; min-width: 0; }
.body-card + .body-card { border-left: 1px solid var(--border); }
.body-card-title { display: flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 550; }
.body-card-title svg { color: var(--mint); }
.body-card > strong { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px; margin: 6px 0 2px; font-size: clamp(23px,2.3vw,30px); line-height: 1.3; font-weight: 560; font-variant-numeric: tabular-nums; }
.body-card small, .body-card-date { font-size: 11px; color: var(--muted); font-weight: 400; }
.body-range-heading p, .body-history header p { margin: 3px 0 0; }
.body-range-heading select { width: auto; padding: 6px 9px; font-size: 13px; }
.body-range-heading label { flex-shrink: 0; }
.body-chart-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); }
.body-chart-panel { min-width: 0; }
.body-chart-panel + .body-chart-panel { border-left: 1px solid var(--border); }
.body-chart-panel > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; min-height: 60px; padding: 14px 17px 0; }
.body-chart-panel header p, .body-chart-panel header > span { display: flex; align-items: center; gap: 4px; color: var(--muted); margin: 4px 0 0; font-size: 12px; line-height: 1.6; }
.body-chart-panel header > span { white-space: nowrap; margin: 0; }
.body-chart-panel h3, .body-chart-selector select { font-size: 14px; font-weight: 600; }
.body-chart-selector select { width: auto; margin: -2px 0 0 -2px; padding: 2px; color: var(--text); background: var(--panel); border: 0; }
.body-chart-panel :deep(.chart) { height: 202px; }
.body-chart-empty { min-height: 160px; padding: 20px; display: grid; place-content: center; justify-items: center; gap: 6px; color: var(--muted); font-size: 13px; }
.body-chart-empty svg { color: var(--subtle); }
.body-history { overflow: hidden; }
.body-history .table-scroll { max-width: 100%; }
.body-history table { width: 100%; font-size: 13px; font-variant-numeric: tabular-nums; }
.body-history th, .body-history td { padding: 9px 13px; white-space: nowrap; }
.body-history th { color: var(--muted); font-size: 12px; font-weight: 500; }
.body-history td > small { display: block; margin-top: 2px; font-size: 11px; color: var(--muted); }
.body-history .measurement-note { max-width: 180px; overflow: hidden; text-overflow: ellipsis; color: var(--muted); }
.row-actions, .body-history > footer, .body-history > footer > div { display: flex; align-items: center; gap: 4px; }
.row-actions .icon-button, .body-history > footer .icon-button { width: 30px; height: 30px; min-width: 30px; }
.body-history > footer { justify-content: space-between; border-top: 1px solid var(--border); padding: 6px 14px; font-size: 12px; color: var(--muted); }
.body-source-note a { display: inline-flex; align-items: center; gap: 2px; margin-left: 6px; color: var(--mint); text-decoration: underline; text-underline-offset: 3px; }
@container measurements (max-width: 700px) {
  .body-card { padding: 12px; }
  .body-chart-grid { grid-template-columns: minmax(0,1fr); }
  .body-chart-panel + .body-chart-panel { border-left: 0; border-top: 1px solid var(--border); }
}
@container measurements (max-width: 490px) {
  .body-cards { grid-template-columns: repeat(6,minmax(0,1fr)); }
  .body-card { grid-column: span 2; }
  .body-card:nth-child(n+4) { grid-column: span 3; border-top: 1px solid var(--border); }
  .body-card:nth-child(4) { border-left: 0; }
  .body-section-title, .body-range-heading, .body-history > header { padding: 12px; }
  .body-section-title { flex-wrap: wrap; gap: 3px 10px; }
  .body-range-heading { align-items: flex-start; }
  .body-history th, .body-history td { padding: 8px 10px; }
}
</style>
