<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { ArrowUpRight,Plus,Cigarette,Wine,Heart,Wallet,Pencil } from 'lucide-vue-next'
import Chart from './Chart.vue'
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
  smoking: {key: 'smokingCount', title: '吸烟', unit: '根', dark: '#81d4b2', light: '#27795b'},
  alcohol: {key: 'ethanolEquivalent42Ml', title: '饮酒（42°）', unit: 'ml', dark: '#a9b8eb', light: '#546ba8'},
  intimacy: {key: 'intimacyCount', title: '私人', unit: '次', dark: '#d9a8c8', light: '#99577f'}
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
  const gridColor = state.settings.theme === 'light' ? '#d9ded9' : '#35403d'
  const labelColor = state.settings.theme === 'light' ? '#53645d' : '#a4b0aa'
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
      trigger: 'axis' as const, confine: true,
      backgroundColor: state.settings.theme === 'light' ? '#fff' : '#242e2a', borderColor: gridColor,
      textStyle: {color: labelColor}, axisPointer: {type: 'line' as const, lineStyle: {color: labelColor}},
      valueFormatter: (value: unknown) => value == null ? '资料待补' : `${value} ${chartMetric.value.unit}`
    },
    xAxis: {
      type: 'category' as const,
      data: dates, boundaryGap: false, axisLine: {lineStyle: {color: gridColor}}, axisTick: {show: false},
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
      name: chartMetric.value.title, type: 'line' as const, connectNulls: false, symbol: 'circle', symbolSize: points.length > 45 ? 3 : 5,
      data: points.map(point => point[chartMetric.value.key]), lineStyle: {color: chartColor.value, width: 2},
      itemStyle: {color: chartColor.value}, areaStyle: {opacity: 0.09, color: chartColor.value}
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
    <div class="overview-workspace">
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
        <button v-else class="week-detail" @click="emit('view','analytics/cost')">计算依据 <ArrowUpRight :size="14"/></button>
      </article>
    </div>
    <section class="trend-panel">
      <header class="panel-header">
        <div><h3>{{ trendTitle }}</h3><p v-if="trendCaption" class="trend-caption">{{ trendCaption }}</p></div>
        <div class="segmented chart-tabs" aria-label="趋势指标">
          <button v-for="(metric, kind) in series" :key="kind" :class="{active:chartKind===kind}"
            :aria-pressed="chartKind===kind" @click="chartKind=kind">{{metric.title}}</button>
        </div>
      </header>
      <div v-if="trendStatus" class="chart-status muted small" role="status">{{ trendStatus }}</div>
      <div v-else class="chart-wrap sensitive"><Chart :option="chartOption" :label="[trendTitle,chartMetric.title,trendCaption].filter(Boolean).join(' · ')"/></div>
      <footer class="chart-footer"><span><i class="legend-line" :style="{background:chartColor}"></i>{{chartKind==='alcohol'?'折合 42° 白酒（ml）':chartKind==='intimacy'?'私人记录（次）':'吸烟（根）'}}</span><span v-if="chartKind==='alcohol'">纯酒精 g ÷ 0.789 ÷ 0.42</span><span>未记录按 0<template v-if="chartKind==='alcohol'">，仅折算已知酒量</template></span></footer>
    </section>
    </div>
  </section>

  <HomeDayReview :from="from" :to="to" :selected-date="selectedDate" :daily="daily" :scope="scope"
    :earliest="state.lifetimeSummary.from || null" :loading="loading && !windowError" :error="windowError"
    @select="emit('selectDay', $event)" @month="emit('month', $event)">
    <template #all><button type="button" class="text-link" @click="emit('view','history')">全部行为记录 <ArrowUpRight :size="14"/></button></template>
    <template #recent>
      <p class="list-range">{{shortDate(from)}} — {{shortDate(to)}}</p>
      <HomeMomentList v-if="recent.length" class="day-list" :entries="recent" show-date @open="openMoment"/>
      <p v-else class="list-range">这段时间没有行为记录</p>
    </template>
    <HomeMomentList v-if="dayEntries.length" class="day-list" :entries="dayEntries" @open="openMoment"/>
  </HomeDayReview>

  <div v-if="analytics?.associations || alcohol.status==='ESTIMATE' || alcohol.status==='INSUFFICIENT_DATA'" class="analysis-links panel">
    <button v-if="analytics?.associations" @click="emit('view','analytics/association')">
      <strong>与饮酒关联的记录</strong>
      <span>{{rangeLabel}}</span>
      <span v-for="(label,key) in {smoking:'吸烟',intimacy:'私人'}" :key="key" class="sensitive">
        {{label}} {{analytics.associations[key]?.relatedEvents ?? '—'}} / {{analytics.associations[key]?.totalEvents ?? '—'}} 条
        <template v-if="analytics.associations[key]?.uncertainEvents"> · 待判断 {{analytics.associations[key]?.uncertainEvents}} 条</template>
      </span>
      <ArrowUpRight :size="14"/>
    </button>
    <button v-if="alcohol.status==='ESTIMATE'||alcohol.status==='INSUFFICIENT_DATA'" @click="emit('view','analytics/estimate')">
      <strong>最近一次饮酒估算</strong>
      <span class="sensitive">{{alcohol.status==='INSUFFICIENT_DATA'?'有资料待补':'查看模型时长与计算依据'}}</span>
      <ArrowUpRight :size="14"/>
    </button>
  </div>
</div>
</template>

<style scoped>
.dashboard { container: overview / inline-size; display: grid; gap: 16px; min-width: 0; }
.week-summary { overflow: hidden; }
.week-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px 16px; padding: 14px 19px; border-bottom: 1px solid var(--border); }
.week-header h2, .trend-panel h3 { margin: 0; font-size: 16px; font-weight: 650; }
.summary-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 12px; }
.summary-title span, .trend-caption { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.scope-tabs button, .chart-tabs button { min-height: 30px; padding: 4px 10px; font-size: 12px; }
.summary-status { margin: 10px 18px 0; }
.overview-workspace { display: grid; grid-template-columns: minmax(330px,.95fr) minmax(0,1.1fr); }
.week-values { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); }
.week-values article { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 14px 18px 12px; }
.week-values article:nth-child(even) { border-left: 1px solid var(--border); }
.week-values article:nth-child(n+3) { border-top: 1px solid var(--border); }
.week-values h3 { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 13px; font-weight: 550; }
.week-values h3 svg { color: var(--mint); }
.week-values article:nth-child(2) h3 svg { color: var(--blue); }
.week-values article:nth-child(3) h3 svg { color: var(--pink); }
.week-values article:nth-child(4) h3 svg { color: var(--muted); }
.week-value { margin: 1px 0; font-variant-numeric: tabular-nums; font-size: clamp(21px, 2.4vw, 29px); line-height: 1.25; font-weight: 560; letter-spacing: -.035em; overflow-wrap: anywhere; }
.week-value small, .week-values p { font-size: 12px; color: var(--muted); font-weight: 400; letter-spacing: normal; }
.week-values p { line-height: 1.55; margin: 0 0 3px; }
.week-action-row { display: flex; align-items: center; flex-wrap: wrap; gap: 2px 6px; margin-top: auto; }
.week-action, .week-detail, .cost-disclosure summary { display: inline-flex; align-items: center; gap: 3px; min-height: 27px; margin-top: auto; padding: 2px 0; color: var(--mint); font-size: 12px; font-weight: 550; }
.week-detail, .cost-disclosure summary { color: var(--muted); }
.week-action:hover, .week-detail:hover, .cost-disclosure summary:hover { text-decoration: underline; text-underline-offset: 3px; color: var(--text); }
.cost-disclosure { margin-top: auto; }
.cost-disclosure summary { display: list-item; cursor: pointer; }
.trend-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; border-left: 1px solid var(--border); }
.trend-panel .panel-header { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; padding: 14px 16px 0; }
.trend-panel h3 { font-size: 14px; }
.trend-caption { margin: 4px 0 0; }
.chart-tabs button { padding: 4px 8px; }
.chart-wrap { flex: 1; min-height: 185px; height: 185px; padding: 0 9px; }
.chart-status { display: grid; flex: 1; min-height: 140px; place-items: center; }
.chart-footer { justify-content: flex-start; flex-wrap: wrap; padding: 8px 18px 14px; font-size: 11px; color: var(--muted); }
.chart-footer > span:last-child { margin-left: auto; }
.list-range { margin: 8px 0; color: var(--muted); font-size: 12px; }
.day-list { max-height: 270px; overflow-y: auto; }
.analysis-links { display: flex; flex-wrap: wrap; }
.analysis-links button { display: flex; align-items: center; flex-wrap: wrap; flex: 1 1 280px; gap: 4px 12px; min-width: 0; min-height: 45px; padding: 10px 16px; font-size: 12px; text-align: left; }
.analysis-links button:hover { background: var(--panel-soft); }
.analysis-links span { color: var(--muted); }
.analysis-links svg { margin-left: auto; color: var(--mint); }
@container overview (max-width: 770px) {
  .overview-workspace { grid-template-columns: 1fr; }
  .week-values { grid-template-columns: repeat(4,minmax(0,1fr)); }
  .week-values article:nth-child(n+2) { border-top: 0; border-left: 1px solid var(--border); }
  .week-values article { padding: 12px; }
  .trend-panel { border-left: 0; border-top: 1px solid var(--border); }
}
@container overview (max-width: 570px) {
  .week-header { padding: 12px; }
  .summary-title { flex-direction: column; gap: 3px; }
  .summary-title span { font-size: 11px; }
  .week-values { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .week-values article:nth-child(3) { border-left: 0; }
  .week-values article:nth-child(n+3) { border-top: 1px solid var(--border); }
  .week-action, .week-detail { min-height: 32px; }
  .chart-tabs button { padding: 3px 6px; }
  .trend-panel .panel-header { padding: 12px 12px 0; }
}
</style>
