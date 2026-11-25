<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ArrowLeft,ArrowRight,CalendarDays,Check,CheckSquare2,ChevronDown,ChevronLeft,ChevronRight,Clock3,Download,Filter,Flag,LoaderCircle,MapPin,MoreHorizontal,Layers2,BookOpen,Utensils,TrainFront,Sun,CheckCircle2,ListChecks,NotebookPen,Pencil,Pin,Plus,RotateCcw,Search,Shuffle,NotebookText,BriefcaseBusiness,Trash2,Users,X} from 'lucide-vue-next'
import {api,deleteRow,restoreRow,saveRow,saveSettings,state} from '../api'
import {recordDrafts} from '../drafts'
import {shanghaiDate} from '../types'
import type {Row} from '../types'
import {dateText,deadlineClass,deadlineText,downloadJournalCsv,effortText,emptyFacets,journalDuration,journalCategories,journalGroup,journalTime,queryJournal,renameQueryTag,shiftDay,taskStatuses} from '../journal'
import type {JournalFacets,JournalPage,JournalQuery} from '../journal'
import JournalEditor from './JournalEditor.vue'
import TaskEditor from './TaskEditor.vue'
import TaskDetail from './TaskDetail.vue'
import TagInput from './TagInput.vue'
import JournalTermManager from './JournalTermManager.vue'
import JournalRecall from './JournalRecall.vue'
import QuickJournalEntry from './QuickJournalEntry.vue'
import TripReview from './TripReview.vue'
defineOptions({name:'LifeJournal'})
const props=withDefaults(defineProps<{initialCollection?:'journal'|'tasks';management?:boolean;refreshKey?:number}>(),{initialCollection:'journal',management:false,refreshKey:0})
const contextKey=props.management?'journal-management-context':'journal-page-context',remembered=recordDrafts[contextKey]
const tab=ref<'day'|'search'|'tasks'>(props.management?(props.initialCollection==='tasks'?'tasks':'search'):(remembered?.tab||'day'))
const date=ref(remembered?.date||shanghaiDate()),filterOpen=ref(false),searchView=ref<'items'|'dates'>(remembered?.searchView||'items')
const defaultFilters=(task=false)=>({q:'',from:'',to:'',category:'',tags:[] as string[],tagMode:'all',person:'',place:'',collection:task?'tasks':'journal',status:task?'OPEN':'ANY',dateField:task?'dueDate':'plannedDate',taskId:'',unscheduled:false,overdue:false,important:false})
const taskContext=ref<Record<string,any>>(remembered?.taskFilters||defaultFilters(true)),journalContext=ref<Record<string,any>>(remembered?.journalFilters||defaultFilters())
const filters=reactive({...defaultFilters(tab.value==='tasks'),...(remembered?.filters||{})})
if(!props.management && tab.value==='search' && filters.collection==='tasks') tab.value='tasks'
else if(tab.value==='tasks') Object.assign(filters,defaultFilters(true),taskContext.value)
const taskDetail=ref<Row|null>(null),detailRevision=ref(0)
const taskView=computed(()=>filters.status==='DONE'?'history':filters.status==='OPEN'?'open':filters.status==='ANY'?'all':'custom')
const taskListDescription=computed(()=>filters.dateField==='dueDate'?'按截止日期从早到晚排列；可筛选日期范围，未填时长不按 0 计算。':filters.dateField==='completedDate'?'按实际完成日期从新到旧排列；可筛选日期范围，未填时长不按 0 计算。':'按安排日期从新到旧排列；可筛选日期范围，未填时长不按 0 计算。')
function rememberFilters(){const snapshot=JSON.parse(JSON.stringify(filters));if(tab.value==='tasks')taskContext.value=snapshot;else if(tab.value==='search'&&filters.collection==='journal')journalContext.value=snapshot}
function setTaskView(view:'open'|'history'|'all'){filters.status=view==='history'?'DONE':view==='open'?'OPEN':'ANY';filters.dateField=view==='history'?'completedDate':'dueDate';filters.from='';filters.to='';filters.unscheduled=false;filters.overdue=false;trash.value=false;page.value=1}
function changeTaskStatusFilter(){if(filters.status==='DONE'){filters.dateField='completedDate';filters.from='';filters.to='';filters.unscheduled=false;filters.overdue=false}else if(filters.dateField==='completedDate'){filters.dateField='dueDate';filters.from='';filters.to=''}page.value=1}
function openGlobalTasks(){setTab('tasks');Object.assign(filters,defaultFilters(true));setTaskView('open')}
function addTaskProgress(task:Row,category:'WORK'|'STUDY'){taskDetail.value=null;journalEditor.value={date:shanghaiDate(),preset:{category,taskId:task.id,tags:task.tags||[]}}}
function viewTask(task:Row){taskDetail.value=task}

const pages=reactive<Record<string,number>>({day:1,search:1,tasks:1,...remembered?.pages}),size=ref(remembered?.size||20),trash=ref(false)
const page=computed({get:()=>pages[tab.value]||1,set:value=>pages[tab.value]=value})
const result=ref<JournalPage>({items:[],total:0,page:1,size:20}),facets=ref<JournalFacets>(emptyFacets()),loading=ref(false),error=ref(''),notice=ref(''),busy=ref(''),exporting=ref(false)
const sideTasks=ref<Row[]>([]),sideError=ref(''),sideLoaded=ref(false),expanded=ref('')
const journalEditor=ref<{row?:Row;preset?:Record<string,any>;date?:string}|null>(null),taskEditor=ref<{row?:Row;preset?:Record<string,any>}|null>(null)
const pendingDelete=ref<{row:Row;collection:string}|null>(null),undo=ref<{row:Row;collection:string}|null>(null)
const moreMenu=ref<HTMLDetailsElement>()
function closeMore(){ if(moreMenu.value) moreMenu.value.open=false }
function dismissMore(event:PointerEvent) {
  if(moreMenu.value?.open && !moreMenu.value.contains(event.target as Node)) closeMore()
}
function escapeMore() {
  if(!moreMenu.value?.open) return
  closeMore()
  moreMenu.value.querySelector('summary')?.focus()
}
function moveTab(event:KeyboardEvent) {
  const items = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]'))
  const index = items.indexOf(event.target as HTMLButtonElement)
  if(index<0) return
  const positions:Record<string, number> = {ArrowRight:(index+1)%items.length,ArrowLeft:(index+items.length-1)%items.length,Home:0,End:items.length-1}
  const next = items[positions[event.key]]
  if(!next) return
  event.preventDefault()
  next.focus()
  next.click()
}
onMounted(()=>document.addEventListener('pointerdown',dismissMore))
onBeforeUnmount(()=>document.removeEventListener('pointerdown',dismissMore))
const pinOpen=ref(false),pinName=ref(''),pinError=ref(''),termsOpen=ref(false)
const recall=ref<{person?:string}|null>(null),quickOpen=ref(false),tripRow=ref<Row|null>(null)
function isTrip(row:Row){return row.category==='TRAVEL'||row.tags?.includes('出差')}
function openPerson(person:string){termsOpen.value=false;recall.value={person}}
function recallDay(day:string,row:Row){recall.value=null;openDay(day,row)}
function planTogether(name:string){recall.value=null;taskEditor.value={preset:{title:'和'+name+'一起',content:''}}}
async function quickSaved(rows:{journal?:Row;ledger?:Row}){
  quickOpen.value=false
  if(rows.journal && !props.management)openDay(rows.journal.startDate,rows.journal)
  await reload()
  inform(rows.journal&&rows.ledger?'生活日志和支出已保存。':rows.journal?'生活日志已保存。':'支出已记入账本。')
}
const collection=computed(()=>tab.value==='tasks'?'tasks':'journal')
const pinFilters=computed<any[]>(()=>Array.isArray(state.settings.journalPinnedFilters)?state.settings.journalPinnedFilters:[])
const totalPages=computed(()=>Math.max(1,Math.ceil(result.value.total/size.value)))
const week=computed(()=>{const selected=/^\d{4}-\d{2}-\d{2}$/.test(date.value)?date.value:shanghaiDate(),d=new Date(selected+'T12:00:00Z'),weekday=d.getUTCDay()||7,start=shiftDay(selected,1-weekday);return Array.from({length:7},(_,i)=>{const value=shiftDay(start,i);return {date:value,label:['一','二','三','四','五','六','日'][i],day:Number(value.slice(-2)),today:value===shanghaiDate()}})})
const groups=computed(()=>{const order=['持续事项','凌晨','早晨','上午','中午','下午','晚上','时间未细分'];return order.map(label=>({label,rows:result.value.items.filter(r=>journalGroup(r)===label)})).filter(g=>g.rows.length)})
const monthTitle=computed(()=>Number(date.value.slice(5,7))+' 月')
const selectedWeekday=computed(()=>week.value.find(day=>day.date===date.value)?.label||'')
const entryIcons:Record<string,typeof Sun>={LIFE:Sun,MEAL:Utensils,WORK:BriefcaseBusiness,STUDY:BookOpen,TRAVEL:TrainFront,SOCIAL:Users}
function dayTime(row:Row) {
  return row.timeMode==='EXACT'||(row.endDate&&row.endDate!==row.startDate)?journalTime(row):journalDuration(row)
}
const frequentTags=computed(()=>facets.value.tags.filter(tag=>tag.count>0).slice(0,6))
const templates=[{label:'吃饭',category:'MEAL'},{label:'工作',category:'WORK'},{label:'上课',category:'STUDY'},{label:'外出',category:'SOCIAL'},{label:'出差',category:'TRAVEL',tags:['出差']}]
let request=0,sideRequest=0,facetRequest=0,debounce:ReturnType<typeof setTimeout>|undefined,noticeTimer:ReturnType<typeof setTimeout>|undefined,disposed=false
function inform(text:string){notice.value=text;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>notice.value='',6000)}
function query(overrides:JournalQuery={}):JournalQuery{
 const common:JournalQuery={collection:collection.value,page:page.value,size:size.value,deleted:trash.value}
 if(tab.value==='day')return {...common,from:date.value,to:date.value,order:'asc',...overrides}
 return {...common,q:filters.q,from:filters.from,to:filters.to,tags:filters.tags,tagMode:filters.tagMode,category:collection.value==='journal'?filters.category:'',person:collection.value==='journal'?filters.person:'',place:collection.value==='journal'?filters.place:'',taskId:collection.value==='journal'?filters.taskId:'',status:collection.value==='tasks'?filters.status:'ANY',dateField:filters.dateField,order:collection.value==='tasks'&&filters.dateField==='dueDate'?'asc':'desc',unscheduled:collection.value==='tasks'&&filters.unscheduled,overdue:collection.value==='tasks'&&filters.overdue,important:collection.value==='tasks'&&filters.important?true:undefined,view:collection.value==='journal'&&tab.value==='search'&&searchView.value==='dates'?'dates':'items',...overrides}
}
async function load(){const id=++request;loading.value=true;error.value='';try{const data=await queryJournal(query());if(disposed||id!==request)return;result.value=data;if(page.value>Math.max(1,Math.ceil(data.total/size.value)))page.value=Math.max(1,Math.ceil(data.total/size.value))}catch(e){if(!disposed&&id===request){error.value=(e as Error).message;result.value={items:[],total:0,page:page.value,size:size.value}}}finally{if(!disposed&&id===request)loading.value=false}}
async function loadFacets(){
  const id=++facetRequest
  try {
    const data=await api<JournalFacets>('/journal/facets')
    if (!disposed && id===facetRequest) facets.value=data
  } catch(e) {
    if (!disposed && id===facetRequest) error.value='读取同行人与标签失败。'+(e as Error).message
  }
}
async function loadSide(){
  if(tab.value!=='day'||props.management)return
  const id=++sideRequest
  sideError.value=''
  try {
    const due=await queryJournal({collection:'tasks',status:'OPEN',dateField:'dueDate',to:shiftDay(shanghaiDate(),6),order:'asc',page:1,size:20})
    if(!disposed && id===sideRequest) {sideTasks.value=due.items.slice(0,3);sideLoaded.value=true}
  } catch(e) {
    if(!disposed && id===sideRequest) sideError.value=(e as Error).message
  }
}
async function reload(){detailRevision.value++;await Promise.all([load(),loadFacets(),loadSide()])}
watch(()=>JSON.stringify(filters),()=>{page.value=1;clearTimeout(debounce);debounce=setTimeout(()=>load(),220)})
watch([tab,trash,size,searchView],()=>{expanded.value='';load();loadSide()})
watch(date,()=>{if(!/^\d{4}-\d{2}-\d{2}$/.test(date.value)){date.value=shanghaiDate();return}pages.day=1;if(tab.value==='day')load()})
watch(page,()=>load())
watch(()=>props.refreshKey,()=>reload())
watch(()=>props.initialCollection,value=>{if(props.management){rememberFilters();tab.value=value==='tasks'?'tasks':'search';Object.assign(filters,defaultFilters(value==='tasks'),value==='tasks'?taskContext.value:journalContext.value);filters.collection=value;trash.value=false;page.value=1;reload()}})
onMounted(()=>{if(props.management)filters.collection=props.initialCollection;reload()})
onBeforeUnmount(()=>{disposed=true;request++;sideRequest++;clearTimeout(debounce);clearTimeout(noticeTimer);if(state.authenticated){rememberFilters();recordDrafts[contextKey]={tab:tab.value,date:date.value,filters:JSON.parse(JSON.stringify(filters)),taskFilters:taskContext.value,journalFilters:journalContext.value,pages:{...pages},size:size.value,searchView:searchView.value}}})
function setTab(value:'day'|'search'|'tasks'){if(tab.value===value)return;rememberFilters();if(value==='tasks')Object.assign(filters,defaultFilters(true),taskContext.value,{collection:'tasks'});else if(value==='search')Object.assign(filters,defaultFilters(),journalContext.value,{collection:props.management?props.initialCollection:'journal'});tab.value=value;trash.value=false;filterOpen.value=false}
function useTemplate(event:Event) {
  const select = event.currentTarget as HTMLSelectElement
  const chosen = templates.find(item=>item.category===select.value)
  select.value=''
  if(chosen) addLog({category:chosen.category,tags:(chosen.tags||[]).filter(tag=>!state.settings.journalVocabulary?.tag?.hidden?.includes(tag))})
}
function addLog(preset:Record<string,any>={}){journalEditor.value={date:tab.value==='day'?date.value:shanghaiDate(),preset}}
function searchTag(tag:string){rememberFilters();tab.value='search';Object.assign(filters,defaultFilters(),{tags:[tag]});trash.value=false;pages.search=1}
function showProgress(task:Row){rememberFilters();taskDetail.value=null;tab.value='search';Object.assign(filters,defaultFilters(),{taskId:task.id});searchView.value='items';trash.value=false;pages.search=1}
function openDay(value:string,row?:Row){rememberFilters();date.value=value;tab.value='day';trash.value=false;pages.day=1;expanded.value=row?.id||''}
function clearFilters(){Object.assign(filters,defaultFilters(collection.value==='tasks'));page.value=1}
function edit(row:Row){if(collection.value==='tasks')taskEditor.value={row};else journalEditor.value={row}}
async function saved(row:Row,kind:string){journalEditor.value=null;taskEditor.value=null;if(kind==='tasks'&&taskDetail.value?.id===row.id)taskDetail.value=row;await reload();inform(kind==='tasks'?'待办已保存。':'生活记录已保存。')}
async function remove(){if(!pendingDelete.value)return;const {row,collection:c}=pendingDelete.value;busy.value=row.id;error.value='';try{const removed=await deleteRow(c,row);undo.value={row:removed,collection:c};pendingDelete.value=null;await reload();inform('已移入回收站，可以撤销。')}catch(e){error.value=(e as Error).message;pendingDelete.value=null}finally{busy.value=''}}
async function restore(row:Row,c=collection.value){busy.value=row.id;error.value='';try{await restoreRow(c,row);undo.value=null;await reload();inform('记录已恢复。')}catch(e){error.value=(e as Error).message}finally{busy.value=''}}
async function changeStatus(row:Row,status:string){busy.value=row.id;try{await saveRow('tasks',{status,completedDate:status==='DONE'?shanghaiDate():null},row);await reload();inform(status==='TODO'?'任务已重新打开。':'任务状态已更新。')}catch(e){error.value=(e as Error).message}finally{busy.value=''}}
function complete(row:Row){taskEditor.value={row,preset:{status:'DONE',completedDate:shanghaiDate()}}}
async function openLinkedTask(row:Row){try{const task=await api<Row>('/data/tasks/'+row.taskId);if(task.deleted){setTab('tasks');clearFilters();filters.status='ANY';filters.q=task.title;trash.value=true;expanded.value=task.id;inform('关联任务在回收站中，恢复后可继续编辑。')}else viewTask(task)}catch(e){error.value=(e as Error).message}}
async function exportRows(){exporting.value=true;error.value='';try{const rows:Row[]=[],seen=new Set<string>(),filter=query({view:'items',page:1,size:50});let p=1,total=0;do{const data=await queryJournal({...filter,page:p});total=data.total;for(const row of data.items)if(!seen.has(row.id)){seen.add(row.id);rows.push(row)}p++;if(!data.items.length)break}while((p-1)*50<total);downloadJournalCsv(rows,String(filter.collection));inform(`已导出 ${rows.length} 条筛选结果。`)}catch(e){error.value=(e as Error).message}finally{exporting.value=false}}
function pinnedQuery(){const allowed=['q','from','to','category','tags','tagMode','person','place','collection','status','dateField','taskId','unscheduled','overdue','important'];return Object.fromEntries(allowed.map(k=>[k,(filters as Record<string,any>)[k]]).filter(([,value])=>value!==''&&value!=null))}
async function pin(){pinError.value='';if(!pinName.value.trim()){pinError.value='给这个入口起个名字。';return}if(pinFilters.value.length>=12){pinError.value='最多固定 12 个入口，可以先移除不常用的。';return}busy.value='pin';try{await saveSettings({journalPinnedFilters:[...pinFilters.value,{id:crypto.randomUUID(),name:pinName.value.trim(),query:pinnedQuery()}]});pinOpen.value=false;pinName.value='';inform('已固定到页面顶部。')}catch(e){pinError.value=(e as Error).message}finally{busy.value=''}}
async function unpin(id:string){try{await saveSettings({journalPinnedFilters:pinFilters.value.filter(p=>p.id!==id)})}catch(e){error.value=(e as Error).message}}
function applyPin(pin:any){rememberFilters();Object.assign(filters,defaultFilters(pin.query.collection==='tasks'),pin.query);tab.value=pin.query.collection==='tasks'?'tasks':'search';trash.value=false;page.value=1;searchView.value='items'}
type TermKind = 'tag' | 'person'
function searchTerm({kind,name}:{kind:TermKind;name:string}) {
  termsOpen.value=false
  const tasks=kind==='tag' && collection.value==='tasks'
  rememberFilters()
  tab.value=tasks?'tasks':'search'
  Object.assign(filters,defaultFilters(tasks),kind==='tag'?{tags:[name],status:'ANY'}:{person:name})
  searchView.value='items'
  trash.value=false
  page.value=1
}
async function termsChanged({kind,from,to,allChanged}:{kind:TermKind;from?:string;to?:string;allChanged?:boolean}) {
  if (from && to && allChanged) {
    for (const context of [filters,taskContext.value,journalContext.value]) {
      if (kind==='tag') {
        context.tags=[...new Set((context.tags||[]).map((tag:string)=>tag===from?to:tag))]
        context.q=renameQueryTag(context.q||'',from,to)
      } else if (context.person===from) context.person=to
    }
  }
  await reload()
  try {
    const latest=await api<Record<string,any>>('/settings')
    if (!disposed && state.authenticated) state.settings=latest
  }
  catch(e) { if(!disposed) error.value=(e as Error).message }
}
const taskDateText=(row:Row)=>row.status==='DONE'?`完成于 ${dateText(row.completedDate)}`:row.plannedDate?`安排 ${dateText(row.plannedDate)}`:'未安排日期'
</script>

<template><div class="life-journal" :class="{managed:management}">
 <div class="journal-toolbar">
   <div v-if="!management" class="journal-tabs" role="tablist" aria-label="生活日志视图" @keydown="moveTab">
     <button :class="{active:tab==='day'}" role="tab" :aria-selected="tab==='day'" :tabindex="tab==='day'?0:-1" @click="setTab('day')"><CalendarDays :size="16"/>按天回看</button>
     <button :class="{active:tab==='search'}" role="tab" :aria-selected="tab==='search'" :tabindex="tab==='search'?0:-1" @click="setTab('search')"><Search :size="16"/>搜索日志</button>
     <button :class="{active:tab==='tasks'}" role="tab" :aria-selected="tab==='tasks'" :tabindex="tab==='tasks'?0:-1" @click="setTab('tasks')"><CheckSquare2 :size="16"/>全局待办</button>
   </div>
   <span v-else class="managed-title">{{initialCollection==='tasks'?'待办任务':'生活日志'}}</span>
   <div class="toolbar-actions">
     <template v-if="tab!=='tasks'">
       <button v-if="!management" class="button secondary" @click="quickOpen=true"><NotebookText :size="15"/>一句话补记</button>
       <div class="entry-split">
         <button class="button primary" @click="addLog()"><Plus :size="16"/>记一件事</button>
         <label class="template-picker" title="按模板记一件事">
           <ChevronDown :size="14" aria-hidden="true"/>
           <select class="journal-template-select" aria-label="按模板记一件事" @change="useTemplate">
             <option value="">选模板</option>
             <option v-for="item in templates" :key="item.category" :value="item.category">{{item.label}}</option>
           </select>
         </label>
       </div>
     </template>
     <button v-else class="button primary" @click="taskEditor={}"><Plus :size="16"/>添加待办</button>
     <details ref="moreMenu" class="journal-more" @keydown.esc.stop="escapeMore">
       <summary title="更多日志工具" aria-label="更多日志工具"><MoreHorizontal :size="19"/></summary>
       <div>
         <button @click="termsOpen=true;closeMore()"><Users :size="15"/>同行人与标签</button>
         <button v-if="!management" @click="recall={};closeMore()"><Shuffle :size="15"/>翻一条旧日记</button>
         <button v-if="tab!=='tasks'" @click="taskEditor={};closeMore()"><Plus :size="15"/>添加待办</button>
       </div>
     </details>
   </div>
 </div>
 <div v-if="pinFilters.length||frequentTags.length" class="quick-searches" aria-label="常用查找">
   <span class="quick-label"><Pin :size="13"/>常用查找</span>
   <span v-for="pinItem in pinFilters" :key="pinItem.id" class="pinned-chip"><button class="sensitive" @click="applyPin(pinItem)">{{pinItem.name}}</button><button :aria-label="'取消固定'+pinItem.name" @click="unpin(pinItem.id)"><X :size="12"/></button></span>
   <button v-for="tag in frequentTags" :key="tag.name" class="tag-chip sensitive" @click="searchTag(tag.name)"><span aria-hidden="true">#</span>{{tag.name}}</button>
 </div>
 <div v-if="notice" class="journal-notice" role="status"><Check :size="14"/>{{notice}}<button v-if="undo" class="text-link" :disabled="!!busy" @click="restore(undo.row,undo.collection)">撤销</button><button class="icon-button" title="关闭提示" @click="notice=''"><X :size="13"/></button></div>
 <div v-if="error" class="error-box journal-error" role="alert">{{error}}<button class="text-link" @click="reload">重试</button></div>

 <template v-if="tab==='day'">
   <section class="day-navigation" aria-label="日期导航">
     <div class="date-navigation">
       <label class="month-picker" title="选择日期">
         <small>{{date.slice(0,4)}} 年 <CalendarDays :size="13"/></small>
         <strong>{{monthTitle}}</strong>
         <input v-model="date" type="date" aria-label="查看哪一天"/>
       </label>
       <div class="date-step">
         <button class="icon-button" title="前一天" @click="date=shiftDay(date,-1)"><ChevronLeft :size="16"/></button>
         <button class="jump-today" @click="date=shanghaiDate()">今天</button>
         <button class="icon-button" title="后一天" @click="date=shiftDay(date,1)"><ChevronRight :size="16"/></button>
       </div>
     </div>
     <div class="week-strip" aria-label="本周日期">
       <button v-for="d in week" :key="d.date" :class="{selected:d.date===date,today:d.today}" :aria-label="d.date+' 周'+d.label" :aria-current="d.today?'date':undefined" :aria-pressed="d.date===date" @click="date=d.date">
         <small>周{{d.label}}</small><strong>{{d.day}}</strong><i v-if="d.today" aria-label="今天"></i>
       </button>
     </div>
   </section>
   <div class="day-layout" :class="{'has-upcoming':!management && (sideTasks.length||sideError)}">
     <section class="journal-panel day-list">
       <header class="list-heading day-heading">
         <div><h3>{{Number(date.slice(5,7))}} 月 {{Number(date.slice(8,10))}} 日<small>星期{{selectedWeekday}}</small></h3><span class="day-count">{{result.total}} 条记录</span></div>
         <div class="day-heading-actions">
           <button v-if="!management && sideLoaded && !sideError && !sideTasks.length" class="nothing-due" title="没有逾期或未来七天内截止的待办，查看全部待办" @click="openGlobalTasks()"><CheckCircle2 :size="15"/>暂无近期到期项<ChevronRight :size="13"/></button>
           <button class="icon-button" title="刷新生活日志" @click="reload"><RotateCcw :size="15" :class="{spin:loading}"/></button>
         </div>
       </header>
       <div v-if="loading" class="loading-note"><LoaderCircle :size="17" class="spin"/>正在读取</div>
       <div v-else-if="!result.items.length" class="journal-empty">
         <NotebookPen :size="25"/><strong>{{date>shanghaiDate()?'这一天还没到':'这一天还没有生活记录'}}</strong>
         <p>{{date>shanghaiDate()?'未来的安排可以先添加为待办。':'想起的时候再补记，日期和一句话就够。'}}</p>
         <button v-if="date<=shanghaiDate()" class="button secondary" @click="addLog()">记下这一天</button>
         <button v-else class="button secondary" @click="taskEditor={preset:{plannedDate:date}}">安排一件事</button>
       </div>
       <div v-else class="day-timeline">
         <section v-for="group in groups" :key="group.label" class="time-group">
           <h4>{{group.label==='时间未细分'?'未分时段':group.label}}</h4>
           <div class="group-entries">
             <article v-for="row in group.rows" :key="row.id" class="journal-row timeline-entry">
               <div class="entry-symbol sensitive" :data-category="row.category" :title="journalCategories[row.category]||'生活'"><component :is="entryIcons[row.category]||Layers2" :size="18" aria-hidden="true"/></div>
               <div class="entry-main">
                 <button class="row-title sensitive" :aria-expanded="expanded===row.id" @click="expanded=expanded===row.id?'':row.id">{{row.title}}<ChevronDown :size="13" :class="{turned:expanded===row.id}"/></button>
                 <div class="row-context sensitive">
                   <span>{{journalCategories[row.category]||'生活'}}</span>
                   <span v-if="dayTime(row)"><Clock3 :size="12"/>{{dayTime(row)}}</span>
                   <span v-if="row.people?.length"><Users :size="13"/><template v-if="!trash"><button class="recall-link" v-for="name in row.people" :key="name" @click="openPerson(name)">{{name}}</button></template><template v-else>{{row.people.join('、')}}</template></span>
                   <span v-if="row.place"><MapPin :size="13"/>{{row.place}}</span>
                   <button v-if="!trash && isTrip(row)" class="recall-link trip-link" @click="tripRow=row"><BriefcaseBusiness :size="13"/>行程花费</button>
                   <button v-for="tag in row.tags||[]" :key="tag" class="entry-tag" @click="searchTag(tag)">#{{tag}}</button>
                 </div>
                 <div v-if="expanded===row.id" class="row-expanded">
                   <p v-if="row.content" class="record-content sensitive">{{row.content}}</p>
                   <p v-if="row.taskId" class="linked-task sensitive">关联待办：{{row.taskTitle||'查看任务'}}{{row.taskDeleted?'（已移入回收站）':''}}<button class="text-link" @click="openLinkedTask(row)">查看</button></p>
                   <div class="row-actions">
                     <button v-if="!isTrip(row)" @click="tripRow=row"><BriefcaseBusiness :size="13"/>行程花费</button>
                     <button @click="taskEditor={preset:{title:'',content:'后续事项：'+row.title,tags:row.tags||[]}}"><Plus :size="13"/>添加后续待办</button>
                     <button @click="pendingDelete={row,collection:'journal'}"><Trash2 :size="13"/>移入回收站</button>
                   </div>
                 </div>
               </div>
               <button class="icon-button edit-entry" :aria-label="'编辑记录：'+row.title" @click="journalEditor={row}"><Pencil :size="15"/></button>
             </article>
           </div>
         </section>
       </div>
       <div v-if="result.total>size" class="journal-pagination">
         <span>{{result.total}} 条</span><button aria-label="上一页" :disabled="page<=1||loading" @click="page--"><ChevronLeft :size="15"/></button>
         <span>{{page}} / {{totalPages}}</span><button aria-label="下一页" :disabled="page>=totalPages||loading" @click="page++"><ChevronRight :size="15"/></button>
       </div>
     </section>
     <aside v-if="!management && (sideTasks.length||sideError)" class="journal-panel side-panel">
       <header><h3><ListChecks :size="17"/>近期截止</h3><button class="text-link" @click="openGlobalTasks()">全部待办 <ArrowRight :size="14"/></button></header>
       <p v-if="sideError" class="error-box">{{sideError}}<button class="text-link" @click="loadSide">重试</button></p>
       <article v-for="task in (sideError?[]:sideTasks)" :key="task.id" class="side-task">
         <div><button class="side-title sensitive" @click="viewTask(task)">{{task.title}}</button><Flag v-if="task.important" :size="13"/></div>
         <small class="deadline-label" :class="deadlineClass(task)"><CalendarDays :size="12"/>{{deadlineText(task)}}</small>
       </article>
     </aside>
   </div>
 </template>

 <template v-else><div v-if="tab==='tasks'" class="global-task-heading"><div><strong>全局待办</strong><p>跨日期保留；完成后可以回看完成日期和累计投入。</p></div><div class="task-view-tabs" role="tablist" aria-label="全局待办范围"><button role="tab" :aria-selected="taskView==='open'" :class="{active:taskView==='open'}" @click="setTaskView('open')">未完成</button><button role="tab" :aria-selected="taskView==='history'" :class="{active:taskView==='history'}" @click="setTaskView('history')">完成历史</button><button role="tab" :aria-selected="taskView==='all'" :class="{active:taskView==='all'}" @click="setTaskView('all')">全部</button></div></div><section class="journal-panel search-panel"><form class="journal-search-bar" @submit.prevent="page=1;load()"><Search :size="18"/><input v-model="filters.q" :placeholder="tab==='tasks'?'搜索待办、工作内容或 #标签':'搜索做过什么、吃了什么、和谁，或输入 #出差'" aria-label="检索关键词"/><button type="button" class="icon-button" title="清空关键词" v-if="filters.q" @click="filters.q=''"><X :size="15"/></button><button class="button secondary" type="button" :class="{selected:filterOpen}" @click="filterOpen=!filterOpen"><Filter :size="14"/>筛选</button><button class="button primary" type="submit">搜索</button></form><div class="search-tools"><div v-if="collection==='journal'" class="mini-segment"><button :class="{active:searchView==='items'}" @click="searchView='items';page=1">事项列表</button><button :class="{active:searchView==='dates'}" @click="searchView='dates';page=1">匹配日期</button></div><button class="text-link" @click="pinName=filters.tags[0]||filters.q||'常用筛选';pinOpen=true"><Pin :size="12"/>固定此筛选</button></div>
 <div v-if="filterOpen" class="filter-grid"><label v-if="collection==='tasks'" class="field"><span>详细状态</span><select v-model="filters.status" aria-label="筛选任务状态" @change="changeTaskStatusFilter"><option value="OPEN">未完成</option><option value="ANY">全部状态</option><option v-for="(label,key) in taskStatuses" :key="key" :value="key">{{label}}</option></select></label><label class="field"><span>{{collection==='journal'?'发生日期从':filters.dateField==='completedDate'?'完成日期从':filters.dateField==='dueDate'?'截止日期从':'安排日期从'}}</span><input v-model="filters.from" type="date"/></label><label class="field"><span>到</span><input v-model="filters.to" type="date"/></label><label v-if="collection==='journal'" class="field"><span>分类</span><select v-model="filters.category"><option value="">全部分类</option><option v-for="(label,key) in journalCategories" :key="key" :value="key">{{label}}</option></select></label><label v-else class="field"><span>按哪种日期筛选</span><select v-model="filters.dateField"><option value="plannedDate">安排日期</option><option value="dueDate">截止日期</option><option value="completedDate">实际完成日期</option></select></label><template v-if="collection==='journal'"><label class="field"><span>和谁</span><input v-model="filters.person" list="journal-filter-people" placeholder="姓名或昵称"/><datalist id="journal-filter-people"><option v-for="p in facets.people" :key="p" :value="p"/></datalist></label><label class="field"><span>地点</span><input v-model="filters.place" placeholder="地点关键词"/></label></template><div class="filter-tags"><TagInput v-model="filters.tags" :max-items="20" label="标签筛选" :suggestions="facets.tags.map(t=>t.name)"/></div><label class="field"><span>多个标签</span><select v-model="filters.tagMode"><option value="all">全部符合</option><option value="any">任一符合</option></select></label><div v-if="collection==='tasks'" class="task-filter-options"><label><input type="checkbox" v-model="filters.unscheduled"/>只看未安排日期</label><label><input type="checkbox" v-model="filters.overdue"/>已过截止日期</label><label><input type="checkbox" v-model="filters.important"/>只看重要</label></div><button class="text-link reset-filters" @click="clearFilters">清除筛选</button></div><div v-if="filters.tags.length||filters.taskId||filters.from||filters.to||filters.person||filters.place||filters.category||filters.unscheduled||filters.overdue||filters.important" class="active-filters"><span v-if="filters.unscheduled">未安排日期</span><span v-if="filters.overdue">已过截止日期</span><span v-if="filters.important">重要任务</span><span v-if="filters.taskId">关联任务的工作记录 <button title="清除任务筛选" @click="filters.taskId=''">×</button></span><span v-if="filters.from||filters.to">{{filters.from||'最早'}} — {{filters.to||'至今'}}</span><span v-if="filters.category">{{journalCategories[filters.category]}}</span><span v-if="filters.person">同行：{{filters.person}}</span><span v-if="filters.place">地点：{{filters.place}}</span><span v-for="tag in filters.tags" :key="tag">#{{tag}} <button :aria-label="'取消筛选'+tag" @click="filters.tags=filters.tags.filter((t:string)=>t!==tag)">×</button></span></div></section>
 <section class="journal-panel search-results"><header class="list-heading"><div><h3>{{trash?'回收站':collection==='tasks'?(filters.status==='DONE'?'完成历史':'全局待办'):searchView==='dates'?'匹配日期':'生活记录'}} <small>{{result.total}} {{collection==='journal'&&searchView==='dates'?'天':'条'}}</small></h3><p>{{collection==='tasks'?taskListDescription:searchView==='dates'?'按全部匹配记录去重，跨日事项覆盖的日期也会列出。':'按实际发生日期排列；计划中的事项请在待办中查看。'}}</p></div><div class="result-actions"><button class="text-link" :disabled="exporting||!result.total" @click="exportRows"><LoaderCircle v-if="exporting" :size="13" class="spin"/><Download v-else :size="13"/>{{exporting?'导出中':'导出筛选'}}</button><button class="text-link" :class="{selected:trash}" @click="trash=!trash;page=1"><RotateCcw v-if="trash" :size="13"/><Trash2 v-else :size="13"/>{{trash?'返回记录':'回收站'}}</button></div></header><div v-if="loading" class="loading-note"><LoaderCircle :size="17" class="spin"/>正在查找</div><div v-else-if="!result.total" class="journal-empty"><Search :size="28"/><strong>{{trash?'回收站是空的':'没有找到匹配内容'}}</strong><p>{{trash?'移入回收站的内容可以在这里恢复。':'可以换个词，或放宽日期、标签等条件。'}}</p><button v-if="!trash" class="text-link" @click="clearFilters">清除筛选</button></div><div v-else-if="collection==='journal'&&searchView==='dates'" class="matching-dates"><button v-for="item in result.dates" :key="item.date" @click="openDay(item.date)"><CalendarDays :size="17"/><strong>{{dateText(item.date)}}</strong><span>{{item.count}} 条相关事项</span><ArrowRight :size="14"/></button></div>
 <div v-else-if="collection==='journal'" class="search-record-list"><article v-for="row in result.items" :key="row.id" class="journal-row"><div class="row-top"><div class="row-heading"><button class="record-date" @click="openDay(filters.from&&filters.from>row.startDate?filters.from:row.startDate,row)">{{dateText(row.startDate)}}{{row.endDate&&row.endDate!==row.startDate?'—'+dateText(row.endDate):''}}</button><button class="row-title sensitive" @click="expanded=expanded===row.id?'':row.id">{{row.title}}<ChevronDown :size="13" :class="{turned:expanded===row.id}"/></button></div><button v-if="!trash" class="icon-button" :aria-label="'编辑记录：'+row.title" @click="edit(row)"><Pencil :size="14"/></button><button v-else class="text-link" :disabled="!!busy" @click="restore(row)">恢复</button></div><div class="row-context sensitive"><span>{{journalCategories[row.category]||'生活'}} · {{journalTime(row)}}</span><span v-if="row.people?.length"><Users :size="12"/><template v-if="!trash"><button class="recall-link" v-for="name in row.people" :key="name" @click="openPerson(name)">{{name}}</button></template><template v-else>{{row.people.join('、')}}</template></span><button v-if="!trash && isTrip(row)" class="recall-link trip-link" @click="tripRow=row"><BriefcaseBusiness :size="12"/>行程花费</button><span v-if="row.place"><MapPin :size="12"/>{{row.place}}</span></div><p v-if="row.content" class="search-snippet sensitive" :class="{full:expanded===row.id}">{{row.content}}</p><div class="row-tags"><button v-for="tag in row.tags||[]" :key="tag" @click="searchTag(tag)">#{{tag}}</button></div><div v-if="expanded===row.id" class="row-expanded"><p v-if="row.taskId" class="linked-task">关联待办：{{row.taskTitle||row.taskId}}{{row.taskDeleted?'（已移入回收站）':''}}</p><small>录入 {{dateText(String(row.createdAt).slice(0,10))}} · 版本 {{row.version}}</small><div v-if="!trash" class="row-actions"><button v-if="!isTrip(row)" @click="tripRow=row"><BriefcaseBusiness :size="12"/>行程花费</button><button @click="openDay(row.startDate,row)">回到当天</button><button @click="taskEditor={preset:{content:'后续事项：'+row.title,tags:row.tags||[]}}">添加后续待办</button><button @click="pendingDelete={row,collection:'journal'}"><Trash2 :size="12"/>移入回收站</button></div></div></article></div>
 <div v-else class="task-record-list"><article v-for="row in result.items" :key="row.id" class="task-row"><div class="row-top"><button v-if="!trash" class="task-check" :class="{done:row.status==='DONE'}" :aria-label="row.status==='DONE'?'重新打开：'+row.title:'完成任务：'+row.title" :disabled="!!busy" @click="row.status==='DONE'?changeStatus(row,'TODO'):complete(row)"><Check :size="15"/></button><div class="task-row-main"><button class="row-title sensitive" @click="expanded=expanded===row.id?'':row.id">{{row.title}}<Flag v-if="row.important" :size="12"/><ChevronDown :size="13" :class="{turned:expanded===row.id}"/></button><div class="row-context"><span class="task-status" :class="row.status.toLowerCase()">{{taskStatuses[row.status]}}</span><span>{{taskDateText(row)}}</span><span class="deadline-label" :class="deadlineClass(row)">{{deadlineText(row)}}</span><span class="effort-label"><Clock3 :size="12"/>{{effortText(row.effort)}}</span></div></div><button v-if="!trash" class="icon-button" :aria-label="'编辑待办：'+row.title" @click="taskEditor={row}"><Pencil :size="14"/></button><button v-else class="text-link" :disabled="!!busy" @click="restore(row)">恢复</button></div><div class="row-tags task-tags"><button v-for="tag in row.tags||[]" :key="tag" @click="filters.tags=[tag]">#{{tag}}</button></div><div v-if="expanded===row.id" class="row-expanded task-expanded"><TaskDetail :task="row" :refresh-key="detailRevision" :readonly="trash" @edit-log="journalEditor={row:$event}" @add-progress="addTaskProgress(row,$event)"/><p v-if="row.content" class="record-content sensitive">{{row.content}}</p><p v-if="row.result" class="record-content sensitive">结果：{{row.result}}</p><div class="row-actions"><button @click="showProgress(row)">查看工作记录</button><template v-if="!trash"><button v-if="row.status==='TODO'" :disabled="!!busy" @click="changeStatus(row,'DOING')">开始做</button><button v-if="row.status==='TODO'||row.status==='DOING'" :disabled="!!busy" @click="changeStatus(row,'PAUSED')">暂缓</button><button v-if="row.status==='PAUSED'||row.status==='CANCELLED'" :disabled="!!busy" @click="changeStatus(row,'TODO')">重新打开</button><button @click="pendingDelete={row,collection:'tasks'}"><Trash2 :size="12"/>移入回收站</button></template></div></div></article></div>
 <footer class="journal-pagination"><span>共 {{result.total}} {{collection==='journal'&&searchView==='dates'?'天':'条'}}</span><label>每页<select v-model.number="size" aria-label="每页条数"><option :value="20">20</option><option :value="50">50</option></select></label><button :disabled="page<=1||loading" @click="page--"><ChevronLeft :size="15"/>上一页</button><span>{{page}} / {{totalPages}}</span><button :disabled="page>=totalPages||loading" @click="page++">下一页<ChevronRight :size="15"/></button></footer></section></template>

 <div v-if="taskDetail" class="overlay centered" @click.self="taskDetail=null"><section class="modal task-summary-modal" role="dialog" aria-modal="true" aria-label="待办详情"><header><div><h2 class="sensitive">{{taskDetail.title}}</h2><p><span>{{taskStatuses[taskDetail.status]}}</span> · {{taskDateText(taskDetail)}} · <span :class="deadlineClass(taskDetail)">{{deadlineText(taskDetail)}}</span></p></div><button class="icon-button" title="关闭待办详情" @click="taskDetail=null"><X :size="18"/></button></header><p v-if="taskDetail.content" class="record-content sensitive">{{taskDetail.content}}</p><p v-if="taskDetail.result" class="record-content sensitive">结果：{{taskDetail.result}}</p><TaskDetail :task="taskDetail" :refresh-key="detailRevision" :readonly="taskDetail.deleted" @edit-log="journalEditor={row:$event};taskDetail=null" @add-progress="addTaskProgress(taskDetail,$event)"/><footer><button class="button secondary" @click="showProgress(taskDetail)">筛选所有关联记录</button><button v-if="!taskDetail.deleted" class="button primary" @click="taskEditor={row:taskDetail};taskDetail=null">编辑待办</button></footer></section></div>
 <JournalEditor v-if="journalEditor" :key="journalEditor.row?.id||JSON.stringify(journalEditor.preset||{})" :row="journalEditor.row" :preset="journalEditor.preset" :date="journalEditor.date" @close="journalEditor=null" @saved="saved" @refresh="reload"/>
 <TaskEditor v-if="taskEditor" :key="(taskEditor.row?.id||'new')+(taskEditor.preset?.status||'')" :row="taskEditor.row" :preset="taskEditor.preset" @close="taskEditor=null" @saved="saved"/>
 <div v-if="pendingDelete" class="overlay centered" @click.self="!busy&&(pendingDelete=null)"><section class="modal journal-modal" role="dialog" aria-modal="true" aria-label="移入回收站"><h2>移入回收站？</h2><p class="sensitive">{{pendingDelete.row.title}}</p><p>{{pendingDelete.collection==='tasks'?'关联的工作记录会保留，任务以后也可以恢复。':'记录将从当前时间线和搜索中隐藏，以后可以恢复。'}}</p><footer><button class="button secondary" :disabled="!!busy" @click="pendingDelete=null">取消</button><button class="button primary" :disabled="!!busy" @click="remove">{{busy?'正在处理':'移入回收站'}}</button></footer></section></div>
 <div v-if="pinOpen" class="overlay centered" @click.self="pinOpen=false"><form class="modal journal-modal" role="dialog" aria-modal="true" aria-label="固定常用筛选" @submit.prevent="pin"><h2>固定这个查找入口</h2><p>保存当前关键词和筛选条件，下次直接点开。</p><label class="field"><span>入口名称</span><input v-model="pinName" maxlength="60" required placeholder="例如：出差记录、项目 A"/></label><p v-if="pinError" class="error-box" role="alert">{{pinError}}</p><footer><button class="button secondary" type="button" @click="pinOpen=false">取消</button><button class="button primary" :disabled="!!busy">保存入口</button></footer></form></div>
 <JournalTermManager v-if="termsOpen" :facets="facets" @close="termsOpen=false" @changed="termsChanged" @search="searchTerm" @recap="openPerson"/>
 <JournalRecall v-if="recall" :person="recall.person" @close="recall=null" @day="recallDay" @plan="planTogether" @search="name=>{recall=null;searchTerm({kind:'person',name})}"/>
 <QuickJournalEntry v-if="quickOpen" :facets="facets" :date="tab==='day'?date:undefined" @close="quickOpen=false" @saved="quickSaved"/>
 <TripReview v-if="tripRow" :row="tripRow" @close="tripRow=null"/>
</div></template>

<style scoped src="./lifeJournal.css"></style>
