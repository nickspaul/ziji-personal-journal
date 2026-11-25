<script setup lang="ts">
import {computed} from 'vue'
import {Cigarette,Heart,Info} from 'lucide-vue-next'
import {state} from '../api'
import {number} from '../types'
const groups=computed(()=>[
  {key:'smoking',label:'吸烟',unit:'根',icon:Cigarette,data:state.analytics.associations?.smoking},
  {key:'intimacy',label:'私人行为',unit:'次',icon:Heart,data:state.analytics.associations?.intimacy}
])
function percent(data:any){return data?.totalEvents>0&&data?.relatedPercent!=null?number(data.relatedPercent,1)+'%':'—'}
function width(data:any,key:string){return data?.totalEvents>0?Math.max(0,Math.min(100,Number(data[key]||0)/Number(data.totalEvents)*100)):0}
</script>

<template>
<section class="panel association-section">
  <header class="association-heading"><h3>饮酒与行为关联</h3></header>
  <div class="association-grid">
    <article v-for="g in groups" :key="g.key" class="association-card" :class="g.key">
      <div class="association-title"><span class="association-icon"><component :is="g.icon" :size="18"/></span><h4>{{g.label}}</h4><span class="association-rate sensitive">{{percent(g.data)}}<small>相关记录占比</small></span></div>
      <template v-if="g.data?.totalEvents>0">
        <div class="association-counts sensitive"><strong>{{number(g.data.relatedEvents,0)}}<small> / {{number(g.data.totalEvents,0)}} 条</small></strong><span>已关联 {{number(g.data.relatedQuantity,0)}} {{g.unit}}<b>待判断 {{number(g.data.uncertainEvents,0)}} 条</b></span></div>
        <div class="association-track sensitive" :aria-label="`${g.label}：相关 ${g.data.relatedEvents} 条，待判断 ${g.data.uncertainEvents} 条，共 ${g.data.totalEvents} 条`"><i class="related" :style="{width:width(g.data,'relatedEvents')+'%'}"></i><i class="uncertain" :style="{width:width(g.data,'uncertainEvents')+'%'}"></i></div>
        <p v-if="g.key==='intimacy'" class="association-caption sensitive">本期自慰 {{number(state.analytics.totals?.masturbationCount,0)}} 次 · 性生活 {{number(state.analytics.totals?.sexCount,0)}} 次</p>
        <p class="association-caption">{{g.data.denominatorLabel||'占比以所选时间内已记录的行为条数为分母'}}<span v-if="g.data.totalEvents<5"> · 目前记录较少</span></p>
      </template>
      <div v-else class="association-empty">所选时段暂无{{g.label}}记录，无法判断关联。</div>
    </article>
  </div>
  <p class="association-note"><Info :size="13"/><span>相关指饮酒期间、模型估算时段或本人确认；未找到饮酒记录也可能是漏记。占比不说明因果。</span></p>
</section>
</template>

<style scoped>
.association-section { min-width: 0; margin: 0; padding: 15px 18px; }
.association-heading { margin: 0 0 4px; }
.association-heading h3 { color: var(--text); font-weight: 630; font-size: 16px; }
.association-grid { display: block; }
.association-card { padding: 13px 0; background: none; border: 0; border-radius: 0; }
.association-card + .association-card { border-top: 1px solid var(--border); }
.association-icon { width: 25px; height: 25px; border-radius: 6px; }
.association-card.intimacy .association-icon { color: var(--pink); background: color-mix(in srgb,var(--pink) 12%,transparent); }
.association-title h4 { font-size: 14px; }
.association-title .association-rate, .association-card.intimacy .association-rate { display: flex; gap: 4px 8px; align-items: baseline; font-size: 23px; line-height: 1.2; color: var(--text); }
.association-rate small { margin: 0; font-size: 11px; }
.association-counts { flex-wrap: wrap; margin: 7px 0; gap: 3px 10px; }
.association-counts > strong { font-size: 15px; }
.association-counts > strong small, .association-counts > span { font-size: 12px; }
.association-counts > span { display: flex; flex-flow: row wrap; gap: 6px; }
.association-caption, .association-empty, .association-note { color: var(--muted); font-size: 12px; line-height: 1.6; }
.association-empty { padding: 9px 0 0; }
.association-note { padding-top: 10px; border-top: 1px solid var(--border); margin-top: 0; }
.association-card.intimacy .association-track > .related { background: var(--pink); }
</style>
