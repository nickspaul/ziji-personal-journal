<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {BookOpen,ChevronLeft,ChevronRight,Clock3,LoaderCircle,NotebookPen,Pencil,RotateCcw} from 'lucide-vue-next'
import {api} from '../api'
import type {Row} from '../types'
import {dateText,journalCategories,journalTime,minuteText,queryJournal} from '../journal'
import type {JournalPage,TaskEffort} from '../journal'

const props=defineProps<{task:Row;refreshKey?:number;readonly?:boolean}>()
const emit=defineEmits<{'edit-log':[row:Row];'add-progress':[category:'WORK'|'STUDY']}>()
const page=ref(1),result=ref<JournalPage>({items:[],total:0,page:1,size:20}),effort=ref<TaskEffort|null>(props.task.effort||null),loading=ref(false),error=ref('')
const pages=computed(()=>Math.max(1,Math.ceil(result.value.total/20)))
let request=0,disposed=false
async function load(){const current=++request;loading.value=true;error.value='';try{const [summary,rows]=await Promise.all([api<TaskEffort>('/journal/tasks/'+props.task.id+'/effort'),queryJournal({collection:'journal',taskId:props.task.id,page:page.value,size:20,order:'desc'})]);if(disposed||current!==request)return;effort.value=summary;result.value=rows;if(page.value!==rows.page)page.value=rows.page}catch(e){if(!disposed&&current===request)error.value=(e as Error).message}finally{if(!disposed&&current===request)loading.value=false}}
watch(()=>props.task.id,()=>{page.value=1;load()},{immediate:true})
watch(()=>props.refreshKey,()=>load())
watch(page,()=>load())
onBeforeUnmount(()=>{disposed=true;request++})
</script>

<template><section class="task-detail">
 <div v-if="error" class="error-box" role="alert">{{error}} <button class="text-link" @click="load">重试</button></div>
 <div class="effort-overview"><div><span><Clock3 :size="13"/>已记录投入</span><strong>{{effort?.totalMinutes==null?(effort?.linkedCount?'待补时长':'尚无计时记录'):minuteText(effort.totalMinutes)}}</strong></div><div class="effort-facts" v-if="effort"><span>{{effort.linkedCount}} 条关联记录</span><span v-if="effort.unmeasuredCount">{{effort.unmeasuredCount}} 条待补时长</span></div><button class="icon-button" title="刷新任务投入" @click="load"><RotateCcw :size="13" :class="{spin:loading}"/></button></div>
 <p class="effort-method" v-if="effort">完整起止时间 {{minuteText(effort.timedMinutes)}}，自填 {{minuteText(effort.manualMinutes)}}。<template v-if="effort.overlapMinutes">完整时间段已去重 {{minuteText(effort.overlapMinutes)}}。</template><template v-else>同一待办内的完整时间段去重。</template>自填时长累加，无法判断是否重叠；未填时长的记录不按 0 计入。</p>
 <div v-if="!readonly" class="progress-buttons"><button @click="emit('add-progress','WORK')"><NotebookPen :size="13"/>记工作进展</button><button @click="emit('add-progress','STUDY')"><BookOpen :size="13"/>记学习进展</button></div>
 <div class="progress-heading"><h4>关联的工作与生活记录</h4><small>{{result.total}} 条</small></div>
 <p v-if="loading&&!result.items.length" class="detail-empty"><LoaderCircle class="spin" :size="14"/>正在读取</p>
 <p v-else-if="!result.total" class="detail-empty">从这里记录进展，或在已有日志中选择这个待办。</p>
 <article v-for="log in result.items" :key="log.id" class="progress-row"><div><span class="progress-date">{{dateText(log.startDate)}}{{log.endDate&&log.endDate!==log.startDate?'—'+dateText(log.endDate):''}} · {{journalCategories[log.category]||'生活'}}</span><strong class="sensitive">{{log.title}}</strong><span class="progress-time">{{journalTime(log)}}<template v-if="!((log.timeMode==='EXACT'&&log.startTime&&log.endTime)||log.durationMinutes!=null)"> · 时长待补</template></span><p v-if="log.content" class="sensitive">{{log.content}}</p></div><button class="icon-button" :aria-label="'编辑关联记录：'+log.title" @click="emit('edit-log',log)"><Pencil :size="13"/></button></article>
 <footer v-if="result.total>20" class="progress-pages"><button :disabled="page<=1||loading" @click="page--"><ChevronLeft :size="13"/>上一页</button><span>{{page}} / {{pages}}</span><button :disabled="page>=pages||loading" @click="page++">下一页<ChevronRight :size="13"/></button></footer>
</section></template>

<style scoped>
.task-detail{border:1px solid var(--border);border-radius:9px;padding:13px;background:var(--input);margin:12px 0 4px;min-width:0}.effort-overview{display:flex;align-items:center;gap:15px}.effort-overview>div:first-child{min-width:0;flex:1}.effort-overview>div:first-child>span{display:flex;align-items:center;gap:5px;font-size:10px;color:var(--muted)}.effort-overview strong{display:block;margin-top:5px;font-size:19px;font-weight:500;color:var(--text)}.effort-facts{display:flex;gap:5px;flex-direction:column;text-align:right;color:var(--muted);font-size:10px}.effort-method{color:var(--muted);font-size:10px;line-height:1.8;margin:9px 0 11px}.progress-buttons{display:flex;gap:8px;flex-wrap:wrap}.progress-buttons button,.progress-pages button{display:inline-flex;gap:5px;align-items:center;border:1px solid var(--border);background:var(--panel);color:var(--text);font-size:11px;border-radius:6px;padding:7px 9px;cursor:pointer}.progress-heading{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--border);margin-top:13px;padding-top:12px;gap:12px}.progress-heading h4{font-size:11px;font-weight:500;margin:0}.progress-heading small{font-size:10px;color:var(--muted)}.progress-row{display:flex;align-items:flex-start;gap:8px;padding:11px 0;border-bottom:1px solid var(--border)}.progress-row>div{flex:1;min-width:0}.progress-row:last-of-type{border-bottom:0}.progress-date,.progress-time{display:block;font-size:10px;color:var(--muted);line-height:1.8}.progress-row strong{display:block;font-size:12px;font-weight:500;line-height:1.7;margin:4px 0;overflow-wrap:anywhere}.progress-row p{font-size:11px;color:var(--muted);white-space:pre-wrap;line-height:1.8;overflow-wrap:anywhere;margin:6px 0 0}.detail-empty{display:flex;gap:7px;color:var(--muted);font-size:11px;line-height:1.8;margin:14px 0 3px}.progress-pages{display:flex;justify-content:flex-end;gap:9px;align-items:center;font-size:10px;color:var(--muted);border-top:1px solid var(--border);padding-top:10px;margin-top:6px}.progress-pages button{font-size:10px;padding:5px 8px}.progress-pages button:disabled{opacity:.45;cursor:default}@media(max-width:580px){.task-detail{padding:11px}.effort-overview{gap:8px}.effort-overview strong{font-size:17px}.effort-facts{font-size:9px}}
</style>
