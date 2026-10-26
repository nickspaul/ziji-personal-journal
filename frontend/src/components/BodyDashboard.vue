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
  { key: 'weightKg', label: '体重', unit: 'kg', color: '#a5d9b6', icon: Scale },
  { key: 'bodyFatPct', label: '体脂率', unit: '%', color: '#c1b1db', icon: Activity },
  { key: 'bmi', label: 'BMI', unit: 'kg/m²', color: '#a4b4e8', icon: Activity },
  { key: 'heightCm', label: '身高', unit: 'cm', color: '#b1b9c9', icon: Ruler },
  { key: 'waistCm', label: '腰围', unit: 'cm', color: '#d7bfa6', icon: Ruler },
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
  const textColor = state.settings.theme === 'light' ? '#616a78' : '#9196a7'
  const lineColor = state.settings.theme === 'light' ? '#dde1e7' : '#2b2e3a'
  return {
    animation: !state.settings.reducedMotion,
    grid: { left: 48, right: 18, top: 28, bottom: 43 },
    tooltip: {
      trigger: 'axis', backgroundColor: state.settings.theme === 'light' ? '#fff' : '#222530', borderColor: lineColor,
      textStyle: { color: textColor, fontSize: 11 },
      formatter: (params: any) => {
        const item = Array.isArray(params) ? params[0] : params
        const row = chartRows.value[item?.dataIndex]
        return row ? `${shortDate(row.measuredAt)} ${sameDayLabels.value[row.id] || ''}<br/>${metric.label}：${formatBodyValue(value(row, key))} ${metric.unit}` : ''
      },
    },
    xAxis: { type: 'category', data: chartRows.value.map(row => `${String(row.measuredAt).slice(5, 10)}${sameDayLabels.value[row.id] ? ` · ${sameDayLabels.value[row.id].replace('当日第 ', '').replace(' 笔', '')}` : ''}`), boundaryGap: true, axisTick: { show: false }, axisLine: { lineStyle: { color: lineColor } }, axisLabel: { color: textColor, fontSize: 10, hideOverlap: true } },
    yAxis: { type: 'value', name: metric.unit, scale: true, splitNumber: 4, axisLabel: { color: textColor, fontSize: 10 }, nameTextStyle: { color: textColor, fontSize: 10 }, splitLine: { lineStyle: { color: lineColor, type: 'dashed' } } },
    series: [{ type: 'line', name: metric.label, data: chartRows.value.map(row => value(row, key)), connectNulls: false, smooth: false, showSymbol: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2, color: metric.color }, itemStyle: { color: metric.color }, emphasis: { scale: true } }],
  }
}
</script>

<template>
  <div class="body-dashboard">
    <section class="body-intro panel"><div><h2>按自己的节奏，记下身体的变化。</h2><p>测量时记一笔，之后补记也可以。每个数字都保留对应的日期。</p></div><Activity :size="28" class="body-intro-icon" /></section>
    <div class="body-section-title"><h3>最近测量值</h3><span>每项取最近测量日期；同日取最后录入的有效值</span></div>
    <section class="body-cards sensitive" aria-label="最近身体数据">
      <article v-for="metric in cards" :key="metric.key" class="panel body-card"><div class="body-card-title"><span>{{ metric.label }}</span><component :is="metric.icon" :size="16" /></div><strong>{{ formatBodyValue(metric.value) }}<small>{{ metric.unit }}</small></strong><span class="body-card-date">{{ metric.date ? shortDate(metric.date) : '尚未记录' }}</span></article>
    </section>
    <div class="body-range-heading"><div><h3>变化趋势</h3><p>按测量日期绘制，缺少的项目留空；同日按录入顺序编号，分别保留。</p></div><label><span class="visually-hidden">身体数据时间范围</span><select v-model.number="range"><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option><option :value="180">最近 180 天</option><option :value="0">全部记录</option></select></label></div>
    <div class="body-chart-grid sensitive">
      <section class="panel body-chart-panel"><header><div><h3>体重</h3><p v-if="change('weightKg') != null"><component :is="change('weightKg')! < 0 ? ArrowDown : ArrowUp" v-if="change('weightKg') !== 0" :size="12" />较所选范围首笔 {{ change('weightKg')! > 0 ? '+' : '' }}{{ formatBodyValue(change('weightKg')) }} kg</p><p v-else>至少两次有效测量后，可比较变化</p></div><span>{{ points('weightKg').length }} 次测量</span></header><Chart v-if="points('weightKg').length" :option="chartOption('weightKg')" label="体重测量趋势；数值及同日多次记录可在下方测量历史中查看" /><div v-else class="body-chart-empty"><Scale :size="27" /><p>这个时间范围还没有体重数据</p><button class="text-link" @click="emit('edit')">添加一次测量</button></div></section>
      <section class="panel body-chart-panel"><header><div><label class="body-chart-selector"><span class="visually-hidden">选择身体趋势指标</span><select v-model="otherMetric"><option value="bodyFatPct">体脂率</option><option value="bmi">BMI</option><option value="waistCm">腰围</option></select></label><p v-if="change(otherMetric) != null">较所选范围首笔 {{ change(otherMetric)! > 0 ? '+' : '' }}{{ formatBodyValue(change(otherMetric)) }} {{ otherMetric === 'bodyFatPct' ? '个百分点' : otherDef.unit }}</p><p v-else>{{ otherMetric === 'bmi' ? '同笔记录的身高、体重齐全后自动计算' : '填入测量值后展示趋势' }}</p></div><span>{{ points(otherMetric).length }} 次测量</span></header><Chart v-if="points(otherMetric).length" :option="chartOption(otherMetric)" :label="`${otherDef.label}测量趋势；详细数据可在下方测量历史中查看`" /><div v-else class="body-chart-empty"><Activity :size="27" /><p>{{ otherMetric === 'bmi' ? '还没有同时包含身高、体重的记录' : `这个时间范围还没有${otherDef.label}数据` }}</p><button class="text-link" @click="emit('edit')">添加一次测量</button></div></section>
    </div>
    <section class="panel body-history"><header><div><h3>测量历史</h3><p>所选范围内 {{ filtered.length }} 笔 · 可以修正或删除单次测量</p></div><ClipboardList :size="19" /></header><div v-if="visibleRows.length" class="table-scroll sensitive"><table><thead><tr><th>测量日期</th><th>体重 kg</th><th>体脂率 %</th><th>BMI</th><th>身高 cm</th><th>腰围 cm</th><th>备注</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleRows" :key="row.id"><td>{{ shortDate(row.measuredAt) }}<small v-if="sameDayLabels[row.id]">{{ sameDayLabels[row.id] }}</small></td><td>{{ formatBodyValue(row.weightKg) }}</td><td>{{ formatBodyValue(row.bodyFatPct) }}</td><td>{{ formatBodyValue(calculateBmi(row)) }}</td><td>{{ formatBodyValue(row.heightCm) }}</td><td>{{ formatBodyValue(row.waistCm) }}</td><td class="measurement-note" :title="row.notes || ''">{{ row.notes || '—' }}</td><td><div class="row-actions"><button class="icon-button" :title="`编辑 ${shortDate(row.measuredAt)} ${sameDayLabels[row.id] || ''} 测量`" @click="emit('edit', row)"><Pencil :size="14" /></button><button class="icon-button" :title="`删除 ${shortDate(row.measuredAt)} ${sameDayLabels[row.id] || ''} 测量`" @click="emit('remove', row)"><Trash2 :size="14" /></button></div></td></tr></tbody></table></div><div v-else class="empty-state compact"><p>{{ rows.length ? '这个时间范围还没有测量，可以切换“全部记录”查看。' : '这里会保留你每次填写的身体数据。先记下今天测到的项目就好。' }}</p><button class="button secondary" @click="emit('edit')"><Plus :size="15" />新增测量</button></div><footer v-if="filtered.length"><span>第 {{ page }} / {{ totalPages }} 页</span><div><button class="icon-button" title="上一页测量" :disabled="page <= 1" @click="page--"><ChevronLeft :size="17" /></button><button class="icon-button" title="下一页测量" :disabled="page >= totalPages" @click="page++"><ChevronRight :size="17" /></button></div></footer></section>
    <p class="body-source-note">BMI = 体重（kg）÷ 身高（m）²，使用同一笔测量的数据。它不能直接测量体脂率，也不能单独判断健康状况。<a href="https://www.cdc.gov/bmi/about/" target="_blank" rel="noopener noreferrer">计算说明 · CDC <ChevronRight :size="11" /></a></p>
  </div>
</template>

<style scoped>
.body-dashboard{min-width:0}.body-intro{padding:18px 21px;display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:21px;background:linear-gradient(115deg,var(--panel-soft),var(--panel));border-radius:12px}.body-eyebrow{font-size:8px;letter-spacing:2px;color:var(--mint)}.body-intro h2{font-size:18px;font-weight:500;margin:8px 0}.body-intro p{font-size:10px;line-height:1.8;color:var(--muted);margin:0}.body-intro .button{flex-shrink:0;font-size:11px;padding:10px 14px}.body-section-title,.body-range-heading{display:flex;justify-content:space-between;align-items:center;gap:14px;margin-bottom:11px}.body-section-title h3,.body-range-heading h3{font-size:13px;font-weight:500;margin:0}.body-section-title>span{color:var(--muted);font-size:9px}.body-cards{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-bottom:25px}.body-card{padding:16px;border-radius:11px;min-width:0}.body-card-title{display:flex;align-items:center;justify-content:space-between;gap:8px;color:var(--muted);font-size:11px}.body-card-title svg{color:var(--subtle)}.body-card>strong{display:block;font-size:27px;font-weight:500;font-variant-numeric:tabular-nums;margin:13px 0 9px;line-height:1.2;white-space:nowrap}.body-card>strong>small{font-size:9px;color:var(--muted);font-weight:400;margin-left:5px}.body-card-date{font-size:9px;color:var(--muted)}.body-range-heading{margin-bottom:13px}.body-range-heading p{font-size:9px;line-height:1.8;color:var(--muted);margin:5px 0 0}.body-range-heading select{font-size:10px;padding:9px 12px;border:1px solid var(--border);border-radius:7px;background:var(--panel);color:var(--text)}.body-chart-grid{display:grid;grid-template-columns:1fr 1fr;gap:15px;margin-bottom:20px}.body-chart-panel{min-width:0;overflow:hidden;border-radius:12px}.body-chart-panel>header{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;padding:18px 18px 0;min-height:66px}.body-chart-panel h3{font-size:12px;font-weight:500;margin:0}.body-chart-panel header p{font-size:9px;color:var(--muted);margin:8px 0 0;display:flex;align-items:center;gap:4px;line-height:1.6}.body-chart-panel header>span{font-size:9px;color:var(--muted);white-space:nowrap;margin-top:2px}.body-chart-selector select{font-size:12px;font-weight:500;color:var(--text);border:0;background:transparent;margin:-5px 0 -3px -4px;padding:4px;max-width:110px}.body-chart-panel :deep(.chart){height:222px}.body-chart-empty{height:222px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:10px;color:var(--subtle);padding:25px}.body-chart-empty p{color:var(--muted);font-size:10px;line-height:1.8;margin:0;text-align:center}.body-chart-empty .text-link{font-size:10px}.body-history{border-radius:12px;overflow:hidden}.body-history>header{display:flex;justify-content:space-between;align-items:center;padding:18px 20px;color:var(--muted)}.body-history h3{font-size:13px;font-weight:500;color:var(--text);margin:0}.body-history header p{font-size:9px;margin:7px 0 0;line-height:1.7}.body-history table{font-size:11px}.body-history th,.body-history td{padding:13px 15px;white-space:nowrap;font-variant-numeric:tabular-nums}.body-history th{font-size:9px;color:var(--muted);font-weight:400}.body-history td>small{display:block;font-size:8px;color:var(--muted);margin-top:5px}.body-history .measurement-note{max-width:180px;overflow:hidden;text-overflow:ellipsis;color:var(--muted);font-size:10px}.row-actions{display:flex;gap:5px}.row-actions .icon-button{width:28px;height:28px;min-width:28px}.body-history>footer{display:flex;align-items:center;justify-content:space-between;padding:10px 17px;border-top:1px solid var(--border);color:var(--muted);font-size:10px}.body-history>footer>div{display:flex;gap:7px}.body-history>footer .icon-button{width:29px;height:29px}.body-history .empty-state{padding:30px 22px}.body-history .empty-state p{font-size:11px;line-height:1.9;max-width:450px}.body-source-note{font-size:9px;line-height:1.9;color:var(--muted);margin:15px 1px 0}.body-source-note a{display:inline-flex;align-items:center;gap:3px;margin-left:8px;text-decoration:underline;text-underline-offset:3px}@media(max-width:1100px){.body-cards{gap:9px}.body-card{padding:13px 11px}.body-card>strong{font-size:24px}.body-card>strong>small{font-size:8px}.body-card-title{font-size:10px}.body-card-title svg{width:13px}}@media(max-width:850px){.body-cards{grid-template-columns:repeat(3,minmax(0,1fr))}.body-chart-grid{grid-template-columns:1fr}.body-chart-panel :deep(.chart),.body-chart-empty{height:210px}.body-card{padding:14px}.body-card>strong{font-size:26px}.body-intro{padding:16px}.body-intro h2{font-size:16px}}@media(max-width:580px){.body-intro{align-items:flex-start;gap:12px}.body-intro h2{font-size:14px;line-height:1.7;max-width:200px}.body-intro p{font-size:9px}.body-intro .button{font-size:10px;padding:9px 11px;min-height:36px;margin-top:20px}.body-intro .button svg{width:13px}.body-eyebrow{font-size:7px;letter-spacing:1.4px}.body-section-title{align-items:flex-start;flex-direction:column;gap:6px}.body-cards{gap:8px;margin-bottom:22px}.body-card{padding:13px 10px}.body-card>strong{font-size:23px;display:flex;align-items:baseline;flex-wrap:wrap;gap:3px}.body-card>strong>small{margin:0}.body-card-date{font-size:8px}.body-card-title{font-size:10px}.body-range-heading{gap:10px;align-items:flex-start}.body-range-heading p{font-size:8px;max-width:220px}.body-range-heading select{font-size:9px;padding:8px 7px}.body-history>header{padding:16px}.body-history th,.body-history td{padding:12px}.body-history table{font-size:10px}.body-source-note{font-size:8px}}
</style>
