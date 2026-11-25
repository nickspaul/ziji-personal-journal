<script setup lang="ts">
import {computed} from 'vue'
import {Info,ArrowUpRight,Cigarette,CalendarRange,Wallet,Wine} from 'lucide-vue-next'
import AlcoholEstimate from './AlcoholEstimate.vue'
import AssociationSummary from './AssociationSummary.vue'
import AssociationDetails from './AssociationDetails.vue'
import DayComparison from './DayComparison.vue'
import {state} from '../api'
import {money,number,shortDate} from '../types'
const props=defineProps<{from:string;to:string}>();const emit=defineEmits<{view:[page:string];repair:[detail:Record<string,any>]}>()
const total=computed(()=>state.analytics.totals||{})
const daily=computed(()=>state.analytics.daily||[])
const missingPrices=computed(()=>Number(total.value.smokingUnknownCostCount||0)+Number(total.value.alcoholUnknownCostCount||0))
const health=computed(()=>state.collections.health||[])
const smokeRows=computed(()=>(state.collections.smoking||[]).filter(r=>String(r.occurredAt).slice(0,10)>=props.from&&String(r.occurredAt).slice(0,10)<=props.to))
function coverage(key:string){return smokeRows.value.filter(r=>r[key]!=null).reduce((s,r)=>s+Number(r.count||0),0)}
</script>
<template>
<div class="analytics-page">
  <div class="analysis-period">
    <span><CalendarRange :size="15" />{{shortDate(from)}} — {{shortDate(to)}}</span>
    <span>未记录行为的日期按 0</span>
  </div>
  <div class="analysis-focus">
    <DayComparison :from="from" :to="to"/>
    <AssociationSummary id="association"/>
  </div>
  <AssociationDetails :from="from" :to="to"/>
  <div class="analytics-grid compact-facts">
    <section id="cost" class="panel fact-panel">
      <h3><Wallet :size="16" />按用量估算的费用</h3>
      <dl class="sensitive"><div><dt>吸烟</dt><dd>{{money(total.smokingCost)}}</dd></div><div><dt>饮酒</dt><dd>{{money(total.alcoholCost)}}</dd></div><div><dt>已知小计</dt><dd>{{money(total.consumptionCost)}}</dd></div></dl>
      <p>按本人用量分摊每包或每瓶单价。<span v-if="missingPrices" class="sensitive">有 {{missingPrices}} 条待补价格，金额仅含已知部分。</span></p>
    </section>
    <section id="alcohol" class="panel fact-panel">
      <h3><Wine :size="16" />折合 42° 白酒</h3>
      <dl class="sensitive"><div><dt>折合酒量</dt><dd>{{total.ethanolEquivalent42Ml==null?'待补资料':number(total.ethanolEquivalent42Ml)+' ml'}}</dd></div><div><dt>纯酒精</dt><dd>{{total.ethanolGrams==null?'待补资料':number(total.ethanolGrams)+' g'}}</dd></div></dl>
      <p>纯酒精克数 ÷ 0.789 ÷ 0.42 = 折合毫升数。<span v-if="total.alcoholUnknownEthanolCount" class="sensitive">{{total.alcoholUnknownEthanolCount}} 条用量或酒精度待补。</span></p>
      <details v-if="daily.length" class="daily-conversion"><summary>查看逐日折算</summary>
        <div class="table-scroll"><table class="sensitive"><thead><tr><th>日期</th><th>饮酒场次</th><th>折合 42° 白酒</th></tr></thead><tbody>
          <tr v-for="day in daily" :key="day.date"><td>{{shortDate(day.date)}}</td><td>{{day.alcoholSessions}}</td><td>{{day.ethanolEquivalent42Ml==null?'待补资料':number(day.ethanolEquivalent42Ml)+' ml'}}<small v-if="day.alcoholUnknownEthanolCount"> · {{day.alcoholUnknownEthanolCount}} 条待补</small></td></tr>
        </tbody></table></div>
      </details>
    </section>
  </div>
  <AlcoholEstimate id="estimate" @view="emit('view',$event)" @repair="emit('repair',$event)"/>
<section class="panel constituent-panel"><header class="panel-header"><div><h3>烟盒标示成分折算</h3></div><Cigarette :size="21" class="muted"/></header><div class="constituent-metrics sensitive"><div v-for="m in [{key:'smokingTarMg',snapshot:'snapshotTarMg',label:'焦油'},{key:'smokingNicotineMg',snapshot:'snapshotNicotineMg',label:'烟气烟碱'},{key:'smokingCoMg',snapshot:'snapshotCoMg',label:'烟气一氧化碳'}]" :key="m.key"><span>{{m.label}} · 标示值合计</span><strong>{{total.constituentMixedMethods?'分口径':total[m.key]!=null&&coverage(m.snapshot)?number(total[m.key],2):'—'}}<small>mg</small></strong><p>有标示资料 {{coverage(m.snapshot)}} / {{total.smokingCount||0}} 根</p></div></div><div v-if="total.tobaccoByMethod?.length" class="table-scroll constituent-groups"><table><thead><tr><th>标示 / 检测口径</th><th>焦油 mg</th><th>烟气烟碱 mg</th><th>一氧化碳 mg</th></tr></thead><tbody><tr v-for="g in total.tobaccoByMethod" :key="g.method"><td>{{g.method||'未说明口径'}}</td><td>{{g.tarMg==null?'待补资料':number(g.tarMg,2)}}<small class="muted"> · {{g.tarCoveredCount}} 根</small></td><td>{{g.nicotineMg==null?'待补资料':number(g.nicotineMg,2)}}<small class="muted"> · {{g.nicotineCoveredCount}} 根</small></td><td>{{g.coMg==null?'待补资料':number(g.coMg,2)}}<small class="muted"> · {{g.coCoveredCount}} 根</small></td></tr></tbody></table></div><div class="soft-notice"><Info :size="17"/><span>以上为包装标示值乘以根数，不是人体吸收量或体内残留量。低焦油不代表更安全，不同检测口径不宜直接比较。</span></div></section>
<div v-if="state.analytics.warnings?.length" class="soft-notice"><Info :size="18"/><div><p v-for="(w,i) in state.analytics.warnings" :key="i">{{typeof w==='string'?w:w.message||JSON.stringify(w)}}</p></div></div><details v-if="health.length" class="panel fact-panel reference-panel"><summary>健康参考与来源 · {{health.length}} 项</summary>
    <article v-for="h in health" :key="h.id" class="reference-item"><h4>{{h.title}}</h4><p>{{h.body}}</p><a v-if="h.sourceUrl" class="text-link" :href="h.sourceUrl" target="_blank" rel="noopener noreferrer">阅读来源 <ArrowUpRight :size="14"/></a></article>
  </details>
</div>
</template>
<style scoped>
.analytics-page { display: grid; gap: 14px; min-width: 0; container: analysis / inline-size; }
.analysis-period, .analysis-period span { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.analysis-period { gap: 5px 16px; color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.analysis-period span:first-child { color: var(--text); font-size: 13px; }
.analysis-period svg, .fact-panel h3 svg { color: var(--mint); }
.analysis-focus { display: grid; grid-template-columns: minmax(0,1.3fr) minmax(0,1fr); gap: 14px; align-items: start; }
.compact-facts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; margin: 0; }
.fact-panel { padding: 15px 18px; min-width: 0; }
.fact-panel h3 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 15px; }
.fact-panel dl { display: flex; flex-wrap: wrap; gap: 8px 22px; margin: 12px 0; }
.fact-panel dt, .fact-panel p { color: var(--muted); font-size: 12px; line-height: 1.65; }
.fact-panel dd { margin: 4px 0 0; font-size: 21px; letter-spacing: -.025em; font-variant-numeric: tabular-nums; }
.fact-panel p { margin: 4px 0; }
.fact-panel summary { cursor: pointer; font-size: 13px; }
.daily-conversion { margin-top: 8px; }
.daily-conversion .table-scroll { max-height: 260px; margin-top: 6px; }
.analytics-page .constituent-panel { margin: 0; min-width: 0; }
.constituent-panel .panel-header { padding: 15px 18px 7px; }
.constituent-metrics { padding: 8px 18px 16px; gap: 12px; grid-template-columns: repeat(3,minmax(0,1fr)); }
.constituent-metrics > div { padding-right: 10px; border-color: var(--border); }
.constituent-metrics span, .constituent-metrics p, .constituent-metrics small { color: var(--muted); font-size: 12px; }
.constituent-metrics strong { margin-top: 6px; color: var(--text); font-size: 24px; }
.constituent-metrics p { margin-top: 3px; }
.constituent-panel .soft-notice, .constituent-groups { margin: 0 16px 14px; font-size: 12px; }
.analytics-page > .soft-notice { margin: 0; }
.reference-item { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border); }
.reference-item h4 { font-size: 14px; }
.analytics-page [id] { scroll-margin-top: 16px; }
@container analysis (max-width: 820px) {
  .analysis-focus { grid-template-columns: minmax(0,1fr); }
}
@container analysis (max-width: 570px) {
  .compact-facts { grid-template-columns: minmax(0,1fr); }
  .constituent-metrics { gap: 6px; padding: 8px 12px 12px; }
  .constituent-metrics > div { padding-right: 5px; }
  .constituent-metrics p { line-height: 1.5; }
}
</style>
