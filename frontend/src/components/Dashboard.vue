<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { ArrowUpRight,Plus,Cigarette,Wine,Heart,Wallet,Clock3,ChevronDown,Pencil } from 'lucide-vue-next'
import Chart from './Chart.vue'
import AlcoholEstimate from './AlcoholEstimate.vue'
import AssociationSummary from './AssociationSummary.vue'
import { state } from '../api'
import HomeDayReview from './HomeDayReview.vue'
import HomeMomentList, { type HomeMoment } from './HomeMomentList.vue'
import { localNow,money,number,shortDate,privateActivityLabels } from '../types'
import type { Row,PrivateActivityType,TrendPoint } from '../types'

type Scope = '7' | '30' | 'all'
const props = defineProps<{ from: string; to: string; days: number; quickSaving: boolean; scope: Scope; selectedDate: string; loading: boolean }>()
const emit = defineEmits<{
  record: [kind: 'smoking' | 'alcohol' | 'intimacy']; quick: [activityType: PrivateActivityType];
  view: [page: string]; repair: [detail: Record<string,any>]; edit: [row: Row, collection: string];
  scope: [value: Scope]; selectDay: [date: string]; month: [date: string]
}>()
const scopes = [{ value: '7', label: '近 7 天' }, { value: '30', label: '近 30 天' }, { value: 'all', label: '累计' }] as const
const series = {
  smoking: {key: 'smokingCount', title: '吸烟', unit: '根', dark: '#a8e6ba', light: '#36734d'},
  alcohol: {key: 'ethanolEquivalent42Ml', title: '饮酒（42°）', unit: 'ml', dark: '#9aabff', light: '#536bc5'},
  intimacy: {key: 'intimacyCount', title: '私人', unit: '次', dark: '#e5a7cc', light: '#a15384'}
} as const
const chartKind = ref<keyof typeof series>('smoking')
const analytics = computed(() => state.analytics.from === props.from && state.analytics.to === props.to ? state.analytics : null)
const daily = computed(() => analytics.value?.daily || [])
const alcohol = computed(() => analytics.value?.alcohol || {})
const isLifetime = computed(() => props.scope === 'all')
const total = computed(() => (isLifetime.value ? state.lifetimeSummary.totals : analytics.value?.totals) || {})
const rangeLabel = computed(() => isLifetime.value
  ? `${props.from.slice(0,4)} 年 ${Number(props.from.slice(5,7))} 月`
  : `近 ${props.scope} 天`)
const summaryDates = computed(() => {
  if (!isLifetime.value) return shortDate(props.from) + ' — ' + shortDate(props.to)
  if (state.lifetimeSummary.from) return shortDate(state.lifetimeSummary.from) + ' — ' + shortDate(state.lifetimeSummary.to)
  return state.lifetimeSummary.totals ? '尚无历史记录' : ''
})
const windowError = computed(() => analytics.value && !analytics.value.daily ? analytics.value.warnings?.join(' ') || '' : '')
const summaryError = computed(() => isLifetime.value ? state.lifetimeError : windowError.value)
const summaryPending = computed(() => !summaryError.value && !(isLifetime.value ? state.lifetimeSummary.totals : analytics.value?.totals))
const reviewedAt = ref(localNow())
watch(() => state.analytics, () => { reviewedAt.value = localNow() })
const moments = computed(() => {
  const dates = new Map<string, HomeMoment[]>()
  const now = reviewedAt.value
  const drinks = (state.collections.drinks || []).filter(row => row.occurredAt && row.occurredAt <= now)
  const sessionDays = new Set(drinks.map(row => row.sessionId + ':' + String(row.occurredAt).slice(0, 10)))
  for (const kind of ['smoking', 'drinks', 'intimacy', 'sessions'] as const) {
    for (const row of state.collections[kind] || []) {
      const at = String(row.occurredAt || row.startedAt || '')
      const date = at.slice(0, 10)
      if (!date || date < props.from || date > props.to || at > now || (kind === 'sessions' && sessionDays.has(row.id + ':' + date))) continue
      const entries = dates.get(date) ?? []
      entries.push({ row, kind, at })
      dates.set(date, entries)
    }
  }
  return dates
})
const recent = computed(() => [...moments.value.values()].flat().sort((a, b) => b.at.localeCompare(a.at)).slice(0, 5))
const dayEntries = computed(() => [...(moments.value.get(props.selectedDate) || [])].sort((a, b) => b.at.localeCompare(a.at)))
const chartMetric = computed(() => series[chartKind.value])
const chartColor = computed(() => state.settings.theme === 'light' ? chartMetric.value.light : chartMetric.value.dark)
const trendPoints = computed<TrendPoint[]>(() => isLifetime.value ? state.lifetimeSummary.trend?.points || [] : daily.value)
const trendUnit = computed(() => isLifetime.value ? state.lifetimeSummary.trend?.unit || 'day' : 'day')
const trendTitle = computed(() => isLifetime.value ? '全部历史趋势' : rangeLabel.value + '趋势')
const trendCaption = computed(() => {
  if (!isLifetime.value || !trendPoints.value.length) return ''
  const unitLabel = {day:'按日', month:'按月', year:'按年'}[trendUnit.value]
  return summaryDates.value + ' · ' + unitLabel + '统计'
})
const trendStatus = computed(() => {
  if (summaryError.value) return '趋势暂时无法读取'
  if (summaryPending.value) return '正在读取趋势…'
  if (isLifetime.value && !state.lifetimeSummary.from) return '尚无历史行为记录'
  return trendPoints.value.length ? '' : '趋势暂时无法读取'
})
const chartOption = computed(() => {
  const gridColor = state.settings.theme === 'light' ? '#dce2de' : '#333640'
  const labelColor = state.settings.theme === 'light' ? '#606e65' : '#98a1ae'
  const points = trendPoints.value
  const history = isLifetime.value
  const unit = trendUnit.value
  const dates = points.map(({date}) => {
    if (!history) return date.slice(5).replace('-', '/')
    if (unit === 'month') return date.slice(0, 7)
    if (unit === 'year') return date.slice(0, 4)
    return date
  })
  const historyTicks = history && dates.length > 7
    ? new Set(Array.from({length: 5}, (_, i) => Math.round(i * (dates.length - 1) / 4)))
    : undefined
  return {
    animation: !state.settings.reducedMotion,
    grid: {left: 42, right: history ? 32 : 16, top: 22, bottom: history && unit === 'day' ? 42 : 26},
    tooltip: {
      trigger: 'axis' as const, valueFormatter: (value: unknown) => value == null ? '资料待补' : `${value} ${chartMetric.value.unit}`
    },
    xAxis: {
      type: 'category' as const,
      data: dates, axisLine: {lineStyle: {color: gridColor}}, axisTick: {show: false},
      axisLabel: {
        color: labelColor, fontSize: 11, hideOverlap: true,
        interval: historyTicks ? (index: number) => historyTicks.has(index) : undefined,
        showMinLabel: history || undefined, showMaxLabel: history || undefined,
        formatter: history && unit === 'day' ? (value: string) => value.slice(0, 4) + '\n' + value.slice(5).replace('-', '/') : undefined
      }
    },
    yAxis: {
      type: 'value' as const, minInterval: chartKind.value === 'alcohol' ? undefined : 1,
      axisLabel: {color: labelColor, fontSize: 11}, splitLine: {lineStyle: {color: gridColor, type: 'dashed' as const}}
    },
    series: [{
      name: chartMetric.value.title, type: 'line' as const, connectNulls: false, symbolSize: points.length > 45 ? 3 : 6,
      data: points.map(point => point[chartMetric.value.key]), lineStyle: {color: chartColor.value, width: 2},
      itemStyle: {color: chartColor.value}, areaStyle: {opacity: 0.07, color: chartColor.value}
    }]
  }
})
const priceMissing=computed(()=>(Number(total.value.smokingUnknownCostCount)||0)+(Number(total.value.alcoholUnknownCostCount)||0))
function displayCount(value:unknown){return value!=null?number(value):'—'}
function openMoment(moment: HomeMoment) {
  if (moment.kind === 'sessions') emit('repair', {collection: 'sessions', id: moment.row.id})
  else emit('edit', moment.row, moment.kind)
}
</script>
<template>
<div class="dashboard">
  <section class="week-summary panel" aria-labelledby="week-heading">
    <header class="week-header">
      <div class="summary-title"><h2 id="week-heading">行为概览</h2><span>{{ summaryDates }}</span></div>
      <div role="group" class="segmented scope-tabs" aria-label="概览统计范围">
        <button v-for="period in scopes" :key="period.value" :class="{active: scope === period.value}"
          :aria-pressed="scope === period.value" @click="emit('scope', period.value)">{{ period.label }}</button>
      </div>
    </header>
    <p v-if="summaryError" class="summary-status error-box" role="alert">{{ summaryError }}</p>
    <p v-else-if="summaryPending" class="summary-status muted small" role="status">正在读取概览…</p>
    <div class="week-values" :aria-busy="summaryPending">
      <article>
        <h3><Cigarette :size="16"/>吸烟</h3>
        <strong class="week-value sensitive">{{displayCount(total.smokingCount)}} <small>根</small></strong>
        <p v-if="total.smokingCount != null" class="sensitive">按用量估算 {{money(total.smokingCost)}}</p>
        <button class="week-action" @click="emit('record','smoking')"><Plus :size="14"/>记吸烟</button>
      </article>
      <article>
        <h3><Wine :size="16"/>饮酒</h3>
        <strong class="week-value sensitive">{{displayCount(total.alcoholMl)}} <small>ml</small></strong>
        <p v-if="total.alcoholSessions != null" class="sensitive">{{displayCount(total.alcoholSessions)}} 次 · 折合 42° 白酒 {{displayCount(total.ethanolEquivalent42Ml)}} ml<template v-if="total.alcoholUnknownEthanolCount"> · {{total.alcoholUnknownEthanolCount}} 条待补饮用资料</template></p>
        <button class="week-action" @click="emit('record','alcohol')"><Plus :size="14"/>记饮酒</button>
      </article>
      <article>
        <h3><Heart :size="16"/>私人记录</h3>
        <strong class="week-value sensitive">{{displayCount(total.intimacyCount)}} <small>次</small></strong>
        <p v-if="total.intimacyCount != null" class="sensitive">自慰 {{displayCount(total.masturbationCount)}} 次 · 性生活 {{displayCount(total.sexCount)}} 次</p>
        <div class="week-action-row">
          <button v-for="(label,type) in privateActivityLabels" :key="type" class="week-action" :disabled="quickSaving"
            :aria-label="`快速记录一次${label}`" :title="`快速记录一次${label}`" @click="emit('quick',type)"><Plus :size="13"/>{{label}}</button>
          <button class="week-detail" title="填写私人记录详情" aria-label="填写私人记录详情" @click="emit('record','intimacy')"><Pencil :size="15"/></button>
        </div>
      </article>
      <article>
        <h3><Wallet :size="16"/>烟酒估算费用</h3>
        <strong class="week-value sensitive"><small>¥</small> {{total.consumptionCost == null ? '—' : number(total.consumptionCost,2)}}</strong>
        <p v-if="priceMissing" class="sensitive">{{priceMissing}} 条待补价格，仅计已知费用</p>
        <p v-else-if="total.consumptionCost != null" class="sensitive">烟 {{money(total.smokingCost)}} · 酒 {{money(total.alcoholCost)}}</p>
        <details v-if="isLifetime" class="cost-disclosure"><summary>计算依据</summary><p>烟按根数分摊每包单价，酒按饮用量分摊每瓶单价。累计金额为各笔已知费用之和。</p></details>
        <button v-else class="week-detail" @click="emit('view','analytics')">计算依据 <ArrowUpRight :size="14"/></button>
      </article>
    </div>
  </section>

  <HomeDayReview :from="from" :to="to" :selected-date="selectedDate" :daily="daily" :scope="scope"
    :earliest="state.lifetimeSummary.from || null" :loading="loading && !windowError" :error="windowError"
    @select="emit('selectDay', $event)" @month="emit('month', $event)">
    <HomeMomentList v-if="dayEntries.length" class="day-list" :entries="dayEntries" @open="openMoment"/>
  </HomeDayReview>

  <div class="overview-panels">
    <section class="panel trend-panel">
      <header class="panel-header">
        <div><h3>{{ trendTitle }}</h3><p v-if="trendCaption" class="trend-caption">{{ trendCaption }}</p></div>
        <div class="segmented chart-tabs" aria-label="趋势指标">
          <button v-for="(metric, kind) in series" :key="kind" :class="{active:chartKind===kind}"
            :aria-pressed="chartKind===kind" @click="chartKind=kind">{{metric.title}}</button>
        </div>
      </header>
      <div v-if="trendStatus" class="chart-status muted small" role="status">{{ trendStatus }}</div>
      <div v-else class="chart-wrap sensitive"><Chart :option="chartOption" :label="[trendTitle,chartMetric.title,trendCaption].filter(Boolean).join(' · ')"/></div>
      <footer class="chart-footer"><span><i class="legend-line" :style="{background:chartColor}"></i>{{chartKind==='alcohol'?'折合 42° 白酒（ml）':chartKind==='intimacy'?'私人记录（次）':'吸烟（根）'}}</span><span>未记录按 0<template v-if="chartKind==='alcohol'">，仅折算已知酒量</template></span></footer>
    </section>

    <section class="panel recent-panel">
      <header class="panel-header">
        <h3>最近记录 <small>{{ rangeLabel }}</small></h3>
        <button class="text-link" @click="emit('view','history')">全部记录 <ArrowUpRight :size="14"/></button>
      </header>
      <HomeMomentList v-if="recent.length" class="recent-list" :entries="recent" show-date @open="openMoment"/>
      <div v-else class="empty-state compact"><Clock3 :size="23"/><strong>这段时间没有行为记录</strong></div>
    </section>
  </div>
  <div v-if="analytics?.associations" class="range-associations">
    <p class="association-scope">关联统计 · {{ rangeLabel }}</p>
    <AssociationSummary compact @details="emit('view','analytics')"/>
  </div>
  <details v-if="alcohol.status==='ESTIMATE'||alcohol.status==='INSUFFICIENT_DATA'" class="history-summary panel estimate-disclosure">
    <summary><span><strong>最近一次饮酒估算</strong><small>{{alcohol.status==='INSUFFICIENT_DATA'?'有资料待补': '模型时长与计算依据'}}</small></span><ChevronDown :size="16"/></summary>
    <AlcoholEstimate compact @view="emit('view',$event)" @repair="emit('repair',$event)"/>
  </details>
</div>
</template>

<style scoped>
.week-summary { margin-bottom: 16px; overflow: hidden; }
.week-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; padding: 15px 20px; border-bottom: 1px solid var(--border); }
.week-header h2, .panel-header h3 { margin: 0; font-size: 15px; font-weight: 600; }
.summary-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 12px; }
.summary-title span { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.scope-tabs button { min-height: 30px; padding: 4px 12px; font-size: 12px; }
.scope-tabs button.active { color: var(--mint); background: var(--mint-bg); }
.summary-status { margin: 12px 20px 0; }
.cost-disclosure { margin-top: auto; font-size: 12px; color: var(--muted); }
.cost-disclosure summary { display: list-item; padding: 6px 0; cursor: pointer; }
.cost-disclosure p { margin-bottom: 2px; }
.week-values { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.week-values article { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; padding: 16px 20px 14px; }
.week-values article + article { border-left: 1px solid var(--border); }
.week-values h3 { display: flex; align-items: center; gap: 7px; font-size: 13px; color: var(--text); font-weight: 500; }
.week-values h3 svg { color: var(--muted); }
.week-value { margin: 6px 0 3px; font-size: clamp(22px, 2.1vw, 30px); font-weight: 550; line-height: 1.3; letter-spacing: -.03em; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.week-value small { color: var(--muted); font-size: 12px; font-weight: 400; letter-spacing: normal; }
.week-values p { margin: 0 0 9px; font-size: 11px; line-height: 1.6; color: var(--muted); }
.week-action-row { display: flex; align-items: center; gap: 9px; margin-top: auto; flex-wrap: wrap; }
.week-action, .week-detail { display: inline-flex; gap: 4px; align-items: center; margin-top: auto; min-height: 30px; font-size: 12px; font-weight: 500; }
.week-action { background: var(--panel-soft); border: 1px solid var(--border); color: var(--text); padding: 3px 9px; border-radius: 6px; }
.week-detail { padding: 3px 0; color: var(--muted); }
.week-action:hover, .week-detail:hover { color: var(--text); text-decoration: underline; text-underline-offset: 3px; }
.overview-panels { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; align-items: start; margin-bottom: 20px; }
.overview-panels .panel { min-width: 0; min-height: 0; }
.overview-panels .panel-header { align-items: center; flex-wrap: wrap; padding: 16px 18px 6px; gap: 10px; }
.chart-wrap { height: 212px; }
.trend-caption { margin: 4px 0 0; color: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.chart-footer { font-size: 10px; color: var(--muted); flex-wrap: wrap; padding: 4px 18px 14px; }
.chart-tabs button { font-size: 11px; padding: 5px 7px; }
.recent-list { margin: 0 18px 6px; }
.day-list { max-height: 270px; overflow-y: auto; }
.recent-panel h3 small { margin-left: 5px; font-size: 11px; font-weight: 400; color: var(--muted); }
.chart-status { min-height: 212px; display: grid; place-items: center; }
.association-scope { font-size: 11px; color: var(--muted); margin-bottom: 5px; }
.estimate-disclosure :deep(.alcohol-reference) { border-width: 1px 0 0; border-radius: 0; }
.dashboard :is(button, summary):focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }
@media (max-width: 1000px) {
  .week-values article { padding: 12px; }
  .overview-panels { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
}
@media (max-width: 760px) {
  .week-values { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .week-values article:nth-child(3) { border-left: 0; }
  .week-values article:nth-child(n+3) { border-top: 1px solid var(--border); }
  .overview-panels { grid-template-columns: minmax(0, 1fr); }
  .week-action, .week-detail { min-height: 36px; }
}
@media (max-width: 470px) {
  .week-header { padding: 12px; gap: 6px; flex-wrap: wrap; }
  .summary-title span { font-size: 11px; }
  .overview-panels .panel-header { padding: 14px 12px 6px; }
  .chart-tabs button { font-size: 10px; }
  .recent-list { margin-inline: 12px; }
}
</style>
