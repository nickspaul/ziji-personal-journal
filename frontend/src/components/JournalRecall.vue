<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {ArrowUpRight, CalendarDays, ChevronRight, MapPin, Plus, Shuffle, Users, X} from 'lucide-vue-next'
import {api} from '../api'
import {dateText, journalCategories, journalTime} from '../journal'
import {shanghaiDate, type Row} from '../types'

const props=defineProps<{person?:string}>()
const emit=defineEmits<{close:[]; day:[date:string,row:Row]; search:[name:string]; plan:[name:string]}>()
type Recap={total:number;recent:Row[];places:{name:string;count:number}[]}
type Memory={row:Row|null;day:string|null}
const filters=[{value:'all',label:'所有旧日记'},{value:'outings',label:'结伴外出'},{value:'last-year',label:'去年今天'}]
const modal=ref<HTMLDialogElement>(), scope=ref('all'), recap=ref<Recap>(), memory=ref<Memory>()
const pending=ref(false), error=ref('')
let active:AbortController|undefined

async function load(again=false){
  active?.abort()
  const request=new AbortController()
  active=request
  const previous=again?memory.value?.row?.id:undefined
  if (!again) { memory.value=undefined;recap.value=undefined }
  pending.value=true
  error.value=''
  try{
    if(props.person){
      const data=await api<Recap>('/journal/people/recap?'+new URLSearchParams({name:props.person}),{signal:request.signal})
      if (!request.signal.aborted) recap.value=data
    }else{
      const params=new URLSearchParams({scope:scope.value})
      if(previous)params.set('previousId',previous)
      const data=await api<Memory>('/journal/memory?'+params,{signal:request.signal})
      if (!request.signal.aborted) memory.value=data
    }
  }catch(e){if(!request.signal.aborted)error.value=(e as Error).message}
  finally{if(!request.signal.aborted)pending.value=false}
}
const latest=computed(()=>recap.value?.recent[0])
const latestDate=computed(()=>latest.value?String(latest.value.endDate||latest.value.startDate):'')
const ago=computed(()=>{
  if(!latestDate.value)return ''
  const days=Math.round((Date.parse(shanghaiDate())-Date.parse(latestDate.value))/86400000)
  return days<=0?'今天':days===1?'昨天':days+' 天前'
})
function showDay(row:Row, day?:string|null){emit('day',day||row.startDate,row)}
watch([scope,()=>props.person],()=>load())
onMounted(()=>{modal.value?.showModal();load()})
onBeforeUnmount(()=>active?.abort())
</script>

<template>
  <dialog ref="modal" class="recall" :aria-label="person?'同行人回忆':'翻一条旧日记'" @cancel.prevent="emit('close')">
    <header>
      <div><Users v-if="person" :size="19"/><Shuffle v-else :size="18"/>
        <h2 :class="{sensitive:person}">{{person?'和'+person+'的共同记录':'翻一条旧日记'}}</h2></div>
      <button type="button" class="icon-button" aria-label="关闭回顾" @click="emit('close')"><X :size="18"/></button>
    </header>
    <div v-if="!person" class="scopes" aria-label="旧日记范围">
      <button v-for="f in filters" :key="f.value" type="button" :aria-pressed="scope===f.value" @click="scope=f.value">{{f.label}}</button>
    </div>
    <div class="scroll">
      <p v-if="error" class="error-box" role="alert">{{error}} <button class="text-link" @click="load()">重试</button></p>
      <p v-else-if="pending" class="hint" role="status">正在翻找记录…</p>
      <template v-else-if="person && recap">
        <template v-if="latest">
          <p class="kicker">最近一次有记录的见面 · {{ago}}</p>
          <button type="button" class="recent-head sensitive" @click="showDay(latest,latestDate)">{{latest.title}} <ArrowUpRight :size="17"/></button>
          <p class="meta sensitive">{{dateText(latestDate)}}<span v-if="latest.place"> · {{latest.place}}</span></p>
          <template v-if="recap.places.length"><h3>一起去过的地方</h3>
            <ul class="places sensitive"><li v-for="place in recap.places" :key="place.name"><MapPin :size="12"/>{{place.name}}<small>{{place.count}} 条</small></li></ul>
          </template>
          <h3>最近的日志 <small>共 {{recap.total}} 条</small></h3>
          <button v-for="row in recap.recent" :key="row.id" class="log-row sensitive" type="button" @click="showDay(row)">
            <time>{{dateText(row.startDate)}}</time><span>{{row.title}}</span><ChevronRight :size="15"/>
          </button>
        </template>
        <p v-else class="hint">还没有与这位同行人一起的生活日志。</p>
      </template>
      <template v-else-if="memory">
        <p v-if="scope==='outings' && memory.row" class="filter-note">从填写了同行人的餐饮、社交和出行日志中抽取。</p>
        <article v-if="memory.row" class="memory sensitive">
          <p class="kicker"><CalendarDays :size="13"/>{{dateText(memory.row.startDate)}}<span v-if="memory.row.endDate && memory.row.endDate!==memory.row.startDate">— {{dateText(memory.row.endDate)}}</span> · {{journalCategories[memory.row.category]||'生活'}}</p>
          <h3>{{memory.row.title}}</h3>
          <p v-if="memory.row.content" class="body-copy">{{memory.row.content}}</p>
          <p class="meta">{{journalTime(memory.row)}}<span v-if="memory.row.place"> · {{memory.row.place}}</span><span v-if="memory.row.people?.length"> · {{memory.row.people.join('、')}}</span></p>
          <div class="memory-tags" v-if="memory.row.tags?.length"><span v-for="tag in memory.row.tags" :key="tag">#{{tag}}</span></div>
        </article>
        <p v-else class="hint">{{scope==='last-year'?dateText(memory.day)+' 当天没有生活日志。':scope==='outings'?'过去的餐饮、社交或出行日志里，暂时没有填写同行人的记录。':'今天以前还没有生活日志。'}}</p>
      </template>
    </div>
    <footer v-if="person">
      <button type="button" class="button secondary" @click="emit('plan',person)"><Plus :size="14"/>约个下次</button>
      <button type="button" class="button primary" @click="emit('search',person)">查看全部日志</button>
    </footer>
    <footer v-else-if="memory?.row">
      <button type="button" class="button secondary" :disabled="pending" @click="showDay(memory.row,memory.day)">查看当天</button>
      <button type="button" class="button primary" :disabled="pending" @click="load(true)"><Shuffle :size="14"/>再翻一条</button>
    </footer>
  </dialog>
</template>

<style scoped>
.recall{box-sizing:border-box;width:min(520px,calc(100vw - 24px));max-height:85vh;overflow:hidden;border:1px solid var(--border);border-radius:14px;background:var(--panel);color:var(--text);padding:0}
.recall[open]{display:flex;flex-direction:column}.recall::backdrop{background:#090c13a8}
header,footer{padding:13px 18px;display:flex;align-items:center;justify-content:space-between;gap:8px;flex:none}
header{border-bottom:1px solid var(--border)}header div,.kicker,.places li,footer button{display:flex;align-items:center;gap:8px}header div{color:var(--mint);min-width:0}h2{font-size:16px;color:var(--text);margin:0;overflow-wrap:anywhere}
.scopes{display:flex;gap:5px;padding:13px 18px 0}.scopes button,.places li,.memory-tags span{border:1px solid var(--border);border-radius:6px;background:var(--input);color:var(--muted);font-size:11px;padding:5px 8px}.scopes button{cursor:pointer}.scopes button[aria-pressed=true]{color:var(--mint);background:var(--mint-bg)}
.scroll p.filter-note{font-size:11px;color:var(--muted);margin:0 0 12px}
.scroll{overflow:auto;padding:17px 18px}.scroll p{margin:0}.kicker,.meta,.log-row time,h3 small{color:var(--muted);font-size:11px;font-weight:400}.recent-head,.log-row{color:var(--text);border:0;background:none;text-align:left;cursor:pointer}.recent-head,.memory h3{margin:8px 0;font-size:19px;overflow-wrap:anywhere}.recent-head{padding:0}.scroll h3:not(.memory h3){font-size:13px;margin:19px 0 8px}.places,.memory-tags{padding:0;margin:0;display:flex;gap:6px;flex-wrap:wrap}.places li{overflow-wrap:anywhere}.places small{opacity:.8}.log-row{display:flex;gap:10px;align-items:center;width:100%;padding:9px 0;border-bottom:1px solid var(--border);font-size:12px}.log-row span{flex:1;overflow-wrap:anywhere}.log-row time{white-space:nowrap}.body-copy{white-space:pre-wrap;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.memory .meta,.memory-tags{margin-top:10px}.memory-tags span{color:var(--mint)}
footer{border-top:1px solid var(--border);justify-content:flex-end}footer .button{font-size:12px;padding:8px 12px}
</style>
