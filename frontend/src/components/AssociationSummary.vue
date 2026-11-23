<script setup lang="ts">
import {computed} from 'vue'
import {Cigarette,Heart,ArrowUpRight,Info} from 'lucide-vue-next'
import {state} from '../api'
import {number} from '../types'
defineProps<{compact?:boolean}>()
const emit=defineEmits<{details:[]}>()
const groups=computed(()=>[
  {key:'smoking',label:'吸烟',unit:'根',icon:Cigarette,data:state.analytics.associations?.smoking},
  {key:'intimacy',label:'私人行为',unit:'次',icon:Heart,data:state.analytics.associations?.intimacy}
])
function percent(data:any){return data?.totalEvents>0&&data?.relatedPercent!=null?number(data.relatedPercent,1)+'%':'—'}
function width(data:any,key:string){return data?.totalEvents>0?Math.max(0,Math.min(100,Number(data[key]||0)/Number(data.totalEvents)*100)):0}
</script>

<template>
<section class="association-section" :class="{compact}">
  <header class="association-heading"><div><h3>饮酒与行为关联</h3><p>看时间上的重合，以及还不能判断的记录。</p></div><button v-if="compact" class="text-link" @click="emit('details')">查看明细 <ArrowUpRight :size="15"/></button></header>
  <div class="association-grid">
    <article v-for="g in groups" :key="g.key" class="association-card" :class="g.key">
      <div class="association-title"><span class="association-icon"><component :is="g.icon" :size="18"/></span><h4>饮酒 ↔ {{g.label}}</h4><span class="association-rate sensitive">{{percent(g.data)}}<small>相关记录占比</small></span></div>
      <template v-if="g.data?.totalEvents>0">
        <div class="association-counts sensitive"><strong>{{number(g.data.relatedEvents,0)}}<small> / {{number(g.data.totalEvents,0)}} 条</small></strong><span>已关联 {{number(g.data.relatedQuantity,0)}} {{g.unit}}<b>待判断 {{number(g.data.uncertainEvents,0)}} 条</b></span></div>
        <div class="association-track sensitive" :aria-label="`${g.label}：相关 ${g.data.relatedEvents} 条，待判断 ${g.data.uncertainEvents} 条，共 ${g.data.totalEvents} 条`"><i class="related" :style="{width:width(g.data,'relatedEvents')+'%'}"></i><i class="uncertain" :style="{width:width(g.data,'uncertainEvents')+'%'}"></i></div>
        <p v-if="!compact && g.key==='intimacy'" class="association-caption sensitive">本期自慰 {{number(state.analytics.totals?.masturbationCount,0)}} 次 · 性生活 {{number(state.analytics.totals?.sexCount,0)}} 次</p>
        <p class="association-caption">{{g.data.denominatorLabel||'占比以所选时间内已记录的行为条数为分母'}}<span v-if="g.data.totalEvents<5"> · 目前记录较少</span></p>
      </template>
      <div v-else class="association-empty">所选时段暂无{{g.label}}记录，无法判断关联。</div>
    </article>
  </div>
  <p class="association-note"><Info :size="13"/><span>相关指饮酒期间、模型估算时段或本人确认；未找到饮酒记录也可能是漏记。占比不说明因果。</span></p>
</section>
</template>
