<script setup lang="ts">
import {recordName} from '../productPricing'
import {computed,ref,watch} from 'vue'
import {Cigarette,Heart,Clock3,Wine,ChevronDown} from 'lucide-vue-next'
import {state} from '../api'
import {number,shortDate,shortTime,privateActivityLabels,privateActivityLabel} from '../types'
const props=defineProps<{from:string;to:string}>()
const kind=ref<'smoking'|'intimacy'>('smoking'),filter=ref('ALL'),limit=ref(12)
const privateType = ref('ALL')
const allRows=computed(()=>{
  const lookup=kind.value==='smoking'?state.analytics.smokingLinks:state.analytics.links
  return(state.collections[kind.value]||[]).filter(r=>String(r.occurredAt).slice(0,10)>=props.from&&String(r.occurredAt).slice(0,10)<=props.to)
    .map(r=>({row:r,link:lookup?.[r.id]})).sort((a,b)=>String(b.row.occurredAt).localeCompare(String(a.row.occurredAt)))
})
const filtered=computed(()=>allRows.value.filter(x=>kind.value!=='intimacy'||privateType.value==='ALL'||(x.row.activityType??'MASTURBATION')===privateType.value).filter(x=>filter.value==='ALL'||(filter.value==='RELATED'?related(x.link):x.link?.effective!=='YES'&&x.link?.effective!=='NO')))
const visible=computed(()=>filtered.value.slice(0,limit.value))
watch([kind,filter,privateType,()=>props.from,()=>props.to],()=>limit.value=12)
function related(link:any){return link?.effective==='YES'}
function status(link:any){if(!link)return '待判断';if(link.source==='MANUAL')return link.effective==='YES'?'本人确认酒后':link.effective==='NO'?'本人确认非酒后':'本人不确定';return({DURING_DRINKING:'饮酒期间',POSSIBLE_AFTER_DRINKING:'估算酒后时段',RECENT_RECORDED_DRINKING:'近期有饮酒 · 待判断',NO_RECORDED_RECENT_DRINKING:'无对应饮酒记录 · 待判断',INSUFFICIENT_DATA:'资料不足 · 待判断'} as Record<string,string>)[link.status]||'待判断'}
function sessions(link:any){return(link?.sessionIds||[]).map((id:string)=>state.collections.sessions?.find(s=>s.id===id)).filter(Boolean)}
</script>

<template>
<details class="panel association-detail-panel">
  <summary><span>查看逐条饮酒关联</span><ChevronDown :size="16"/></summary>
  <div class="association-controls"><div class="segmented"><button :class="{active:kind==='smoking'}" @click="kind='smoking'"><Cigarette :size="15"/>吸烟</button><button :class="{active:kind==='intimacy'}" @click="kind='intimacy'"><Heart :size="15"/>私人行为</button></div><label v-if="kind==='intimacy'"><span>类型</span><select v-model="privateType" aria-label="私人行为类型"><option value="ALL">全部类型</option><option v-for="(label,type) in privateActivityLabels" :key="type" :value="type">{{label}}</option></select></label><label><span>查看</span><select v-model="filter" aria-label="饮酒关联筛选"><option value="ALL">全部记录</option><option value="RELATED">已关联饮酒</option><option value="UNCERTAIN">待判断</option></select></label></div>
  <div v-if="!visible.length" class="empty-state compact"><Clock3 :size="25"/><p>{{allRows.length?'当前筛选下没有记录。':'所选时间内还没有这类记录。'}}</p></div>
  <div v-else class="association-records">
    <article v-for="{row,link} in visible" :key="row.id" class="association-record">
      <div class="association-record-top"><div><strong class="sensitive">{{shortDate(row.occurredAt)}} · {{row.timePrecision==='DATE'?'时间不详':shortTime(row.occurredAt)}}</strong><span class="sensitive">{{kind==='smoking'?(recordName(row.snapshotName,'未选种类')):privateActivityLabel(row.activityType)}} · {{number(row.count,0)}} {{kind==='smoking'?'根':'次'}}</span></div><span class="tag" :class="related(link)?'mint':'association-unknown'">{{status(link)}}</span></div>
      <p>{{link?.reason||'正在等待关联信息。'}}</p>
      <div v-if="sessions(link).length" class="related-session-list sensitive"><span><Wine :size="13"/>对应饮酒</span><span v-for="session in sessions(link)" :key="session.id">{{shortDate(session.startedAt)}} {{session.timePrecision==='DATE'?'时间不详':shortTime(session.startedAt)}}{{session.endedAt?' 至 '+shortTime(session.endedAt):''}}{{session.context?' · '+session.context:''}}</span></div>
      <details v-if="link?.source==='MANUAL'&&row.overrideReason" class="association-explanation"><summary>本人确认的说明 <ChevronDown :size="13"/></summary><p class="sensitive">{{row.overrideReason}}</p></details>
    </article>
  </div>
  <footer class="association-detail-footer"><span>当前筛选 {{filtered.length}} 条 · 按发生时间排列</span><button v-if="limit<filtered.length" class="text-link" @click="limit+=12">再看 12 条 <ChevronDown :size="14"/></button></footer>
</details>
</template>

<style scoped>
.association-detail-panel { margin: 0; }
.association-detail-panel > summary { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 13px 18px; list-style: none; cursor: pointer; font-size: 14px; font-weight: 550; }
.association-detail-panel > summary::-webkit-details-marker { display: none; }
.association-detail-panel > summary svg { color: var(--muted); }
.association-detail-panel[open] > summary { border-bottom: 1px solid var(--border); }
.association-detail-panel[open] > summary svg { transform: rotate(180deg); }
.association-controls { margin: 12px 16px 0; justify-content: flex-start; flex-wrap: wrap; }
.association-controls select, .association-controls label, .association-detail-footer { font-size: 12px; }
.association-records { padding: 0 16px; }
.association-record { padding: 12px 0; }
.association-record-top strong { font-size: 13px; }
.association-record-top > div > span, .association-record > p { font-size: 12px; }
.association-record-top .tag { font-size: 11px; }
</style>
