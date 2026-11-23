<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch,nextTick} from 'vue'
import {LayoutDashboard,Clock3,BarChart3,Package,Wallet,Database,Settings as SettingsIcon,ShieldCheck,Eye,EyeOff,Plus,RefreshCw,Menu,X,Activity,LockKeyhole,LoaderCircle,Check,Info,CalendarDays,ArrowRight,BookOpen,Download,ChevronDown} from 'lucide-vue-next'
import Dashboard from './components/Dashboard.vue'
import Ledger from './components/Ledger.vue'
import ReportExport from './components/ReportExport.vue'
import LifeJournal from './components/LifeJournal.vue'
import JournalEditor from './components/JournalEditor.vue'
import TaskEditor from './components/TaskEditor.vue'
import BodyDashboard from './components/BodyDashboard.vue'
import BodyEditor from './components/BodyEditor.vue'
import DataManager from './components/DataManager.vue'
import Products from './components/Products.vue'
import Settings from './components/Settings.vue'
import Analytics from './components/Analytics.vue'
import RecordDrawer from './components/RecordDrawer.vue'
import GenericEditor from './components/GenericEditor.vue'
import {state,authStatus,authenticate,api,refresh,refreshAnalytics,refreshLifetimeSummary,saveRow,deleteRow,restoreRow,saveSettings} from './api'
import type {Row,PrivateActivityType} from './types'
import {clearDrafts} from './drafts'
import {localNow,shanghaiDate,shortDate,privateActivityLabels} from './types'
import {shiftDay} from './journal'
import {anchorInMonth} from './ledgerReview'
const navGroups = [
  {label:'日常', items:[
    {id:'dashboard', label:'日常概览', icon:LayoutDashboard},
    {id:'journal', label:'生活日志', icon:BookOpen},
    {id:'ledger', label:'日常记账', icon:Wallet},
    {id:'history', label:'行为记录', icon:Clock3},
    {id:'analytics', label:'统计分析', icon:BarChart3},
    {id:'body', label:'身体数据', icon:Activity},
  ]},
  {label:'管理', items:[
    {id:'products', label:'种类与单价', icon:Package},
    {id:'manager', label:'数据管理', icon:Database},
    {id:'settings', label:'个人设置', icon:SettingsIcon},
  ]},
]
const nav = navGroups.flatMap(group => group.items)
const page=ref(nav.some(n=>n.id===location.hash.slice(1))?location.hash.slice(1):'dashboard'),days=ref(30),to=ref(shanghaiDate()),sidebar=ref(false),password=ref(''),passwordAgain=ref(''),showPassword=ref(false),authBusy=ref(false),authError=ref(''),connectionError=ref(''),record=ref<{kind:'smoking'|'alcohol'|'intimacy';row?:Row;session?:Row}|null>(null),editor=ref<{collection:string;row?:Row;preset?:Record<string,any>}|null>(null),historyKind=ref('smoking'),productTab=ref('products'),toast=ref<{message:string;undo?:()=>Promise<void>}|null>(null)
const journalRefresh=ref(0)
const exportMode = ref<'quick'|'custom'|null>(null)
watch(() => state.authenticated, authenticated => { if (!authenticated) exportMode.value = null })
const bodyDelete=ref<Row|null>(null),bodyDeleteBusy=ref(false),bodyDeleteError=ref('')
async function removeBody(){if(!bodyDelete.value)return;const row=bodyDelete.value;bodyDeleteBusy.value=true;bodyDeleteError.value='';try{await deleteRow('body',row);bodyDelete.value=null;await reload();notify('身体测量已移入回收站。',async()=>{await restoreRow('body',{...row,version:row.version+1});await reload();notify('身体测量已恢复。')})}catch(e){bodyDeleteError.value=(e as Error).message}finally{bodyDeleteBusy.value=false}}
function repairAlcohol(detail:Record<string,any>){if(detail.collection==='body'){go('body');openEditor('body',state.collections.body?.find(r=>r.id===detail.id));return}if(detail.collection==='sessions'){const session=state.collections.sessions?.find(r=>r.id===(detail.sessionId||detail.id));if(session){record.value={kind:'alcohol',session};return}}if(detail.collection==='drinks'){const row=state.collections.drinks?.find(r=>r.id===detail.id);if(row){openEditor('drinks',row);return}}historyKind.value=detail.collection==='sessions'?'sessions':'drinks';go('history')}
type HomeScope = '7' | '30' | 'all'
const homeScope = ref<HomeScope>('7')
const selectedDay = ref(to.value)
const detailMonth = ref('')
// Daily statistics for a lifetime view are fetched one calendar month at a time.
const reviewMonth = computed(() => {
  if (page.value === 'dashboard' && homeScope.value === 'all') return selectedDay.value.slice(0, 7)
  if (page.value === 'analytics' && days.value === 0) return detailMonth.value
  return ''
})
const activeDays = computed(() => page.value === 'dashboard' ? Number(homeScope.value) : days.value || 30)
const from = computed(() => reviewMonth.value ? reviewMonth.value + '-01' : shiftDay(to.value, 1 - activeDays.value))
const analysisEnd = computed(() => {
  if (!reviewMonth.value) return to.value
  const monthEnd = anchorInMonth(reviewMonth.value, reviewMonth.value + '-31')
  return monthEnd < to.value ? monthEnd : to.value
})
const homeLoading = computed(() => state.analytics.from !== from.value || state.analytics.to !== analysisEnd.value ||
  (!state.analytics.daily && !state.analytics.warnings))
function changeHomeScope(scope: HomeScope) {
  homeScope.value = scope
  if (scope !== 'all' && selectedDay.value < shiftDay(to.value, 1 - Number(scope))) selectedDay.value = to.value
}
function selectHomeDay(date: string) {
  const earliest = homeScope.value === 'all' ? state.lifetimeSummary.from : from.value
  if (date <= to.value && (!earliest || date >= earliest)) selectedDay.value = date
}
const currentNav=computed(()=>nav.find(n=>n.id===page.value)||nav[0]),todayLabel=computed(()=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'long',day:'numeric',weekday:'long'}).format(new Date()))
let toastTimer:ReturnType<typeof setTimeout>|undefined,lockTimer:ReturnType<typeof setInterval>|undefined,lastActivity=Date.now()
function notify(message:string,undo?:()=>Promise<void>){if(toastTimer)clearTimeout(toastTimer);toast.value={message,undo};toastTimer=setTimeout(()=>toast.value=null,undo?10000:6000)}
function go(id:string){
  if(page.value==='dashboard' && id==='analytics'){
    detailMonth.value=reviewMonth.value
    days.value=homeScope.value==='all' ? 0 : Number(homeScope.value)
  }
  page.value=id;location.hash=id;sidebar.value=false;window.scrollTo({top:0,behavior:state.settings.reducedMotion?'instant':'smooth'})
}
function openRecord(kind:'smoking'|'alcohol'|'intimacy'){record.value={kind}}
function openEditor(collection:string,row?:Row,preset?:Record<string,any>){if(!row&&['smoking','drinks','intimacy'].includes(collection)){openRecord(collection==='smoking'?'smoking':collection==='intimacy'?'intimacy':'alcohol');return}editor.value={collection,row,preset}}
async function updateAnalytics(includeSummary = true){
  if (!state.authenticated || !['dashboard','analytics','manager','history'].includes(page.value)) return
  const needsSummary = includeSummary && page.value === 'dashboard' && homeScope.value === 'all'
  await Promise.all([refreshAnalytics(from.value,analysisEnd.value), ...(needsSummary ? [refreshLifetimeSummary()] : [])])
}
async function reload(){try{await refresh();journalRefresh.value++;await updateAnalytics()}catch(e){notify((e as Error).message)}}
async function init(){state.checking=true;connectionError.value='';try{await authStatus();if(state.authenticated){await refresh();await updateAnalytics()}}catch(e){connectionError.value=(e as Error).message}finally{state.checking=false}}
async function login(){authError.value='';if(!state.configured&&password.value!==passwordAgain.value){authError.value='两次输入的口令不一致，请再检查一下。';return}authBusy.value=true;try{await authenticate(password.value);password.value='';passwordAgain.value='';await updateAnalytics();lastActivity=Date.now()}catch(e){authError.value=(e as Error).message}finally{authBusy.value=false}}
async function lock(){try{await api('/auth/logout',{method:'POST'});state.authenticated=false;state.collections={};state.analytics={};state.lifetimeSummary={};state.lifetimeError='';record.value=null;editor.value=null;bodyDelete.value=null;await nextTick();clearDrafts();await authStatus()}catch(e){notify((e as Error).message)}}
function reminder(kind:string){if(!['smoking','sessions','drinks','intimacy'].includes(kind))return '';if(state.settings.reminderMode==='OFF')return '';const time=localNow().slice(11,16),start=state.settings.quietStart||'23:00',end=state.settings.quietEnd||'08:00';if(start<end?time>=start&&time<end:time>=start||time<end)return '';const key=`ziji-reminders-${shanghaiDate()}`;const count=Number(localStorage.getItem(key)||0),limit=Number(state.settings.dailyReminderLimit??3);if(count>=limit)return '';localStorage.setItem(key,String(count+1));return kind==='smoking'?'如果愿意，下次想抽时可以先等一会儿。':kind==='intimacy'?'也给舒适和休息留一点空间。':kind==='sessions'||kind==='drinks'?'可以考虑到这里结束，给今晚留一些休息时间。':''}
const quickSaving = ref(false)
async function quick(activityType: PrivateActivityType) {
  if (quickSaving.value) return
  quickSaving.value = true
  try {
    const row = await saveRow('intimacy', { activityType, occurredAt:localNow(), timePrecision:'EXACT', count:1, manualAlcohol:'AUTO' })
    await reload()
    notify(`已记录${privateActivityLabels[activityType]} 1 次。${reminder('intimacy')}`, async () => {
      await deleteRow('intimacy',row)
      await reload()
      notify('记录已撤销，可在回收站恢复。')
    })
  } catch (error) { notify((error as Error).message) }
  finally { quickSaving.value = false }
}
async function afterSaved(row:Row,collection:string){const wasNew=!record.value?.row&&!editor.value?.row;record.value=null;editor.value=null;await reload();notify(`记录已保存。${reminder(collection)}`,wasNew&&['smoking','intimacy'].includes(collection)?async()=>{await deleteRow(collection,row);await reload();notify('记录已撤销。')}:undefined)}
async function togglePrivacy(){try{await saveSettings({hidden:!state.settings.hidden})}catch(e){notify((e as Error).message)}}
function editFromDashboard(row:Row,collection:string){if(collection==='smoking'||collection==='intimacy')record.value={kind:collection,row};else openEditor(collection,row)}
function activity(){lastActivity=Date.now()}
function hashChange(){const id=location.hash.slice(1);if(nav.some(n=>n.id===id))page.value=id}
watch(to, (today, previous) => {
  if (selectedDay.value === previous || (homeScope.value !== 'all' && selectedDay.value < shiftDay(today, 1 - Number(homeScope.value)))) selectedDay.value = today
})
watch([homeScope, () => state.lifetimeSummary.from], ([scope, earliest]) => {
  if (scope === 'all' && earliest && selectedDay.value < earliest) selectedDay.value = earliest
})
watch([page,from,analysisEnd,homeScope,to], (current, previous) => {
  const [view,,,scope,today] = current
  const enteringLifetime = view !== previous[0] || scope !== previous[3] || today !== previous[4]
  void updateAnalytics(enteringLifetime || !state.lifetimeSummary.totals)
})
watch(()=>state.settings.theme,v=>document.documentElement.dataset.theme=v||'dark',{immediate:true})
watch(()=>state.settings.reducedMotion,v=>document.documentElement.dataset.motion=v?'reduced':'normal',{immediate:true})
onMounted(()=>{init();window.addEventListener('pointerdown',activity);window.addEventListener('keydown',activity);window.addEventListener('hashchange',hashChange);lockTimer=setInterval(()=>{if(state.authenticated&&Date.now()-lastActivity>15*60*1000)lock();else if(state.authenticated&&['dashboard','analytics'].includes(page.value)&&document.visibilityState==='visible'){if(to.value!==shanghaiDate())to.value=shanghaiDate();else updateAnalytics()}},60000)})
onBeforeUnmount(()=>{window.removeEventListener('pointerdown',activity);window.removeEventListener('keydown',activity);window.removeEventListener('hashchange',hashChange);if(lockTimer)clearInterval(lockTimer);if(toastTimer)clearTimeout(toastTimer)})
</script>
<template>
<div v-if="state.checking" class="loading-screen"><div class="brand-mark"><Activity :size="28"/></div><LoaderCircle class="spin" :size="23"/><p>正在连接你的本机空间…</p></div>
<div v-else-if="!state.authenticated||connectionError" class="auth-page"><section class="auth-story"><a class="brand" href="#"><span class="brand-mark"><Activity :size="25" :stroke-width="1.8"/></span><span>自记<small>ZIJI / PERSONAL JOURNAL</small></span></a><div class="auth-story-copy"><span class="eyebrow">A SPACE THAT'S ONLY YOURS</span><h1>认真记录。<br/><span>温柔对待自己。</span></h1><p>看见日常的节奏，了解每一次选择。<br/>不必完美，从如实记下开始。</p></div><div class="auth-orbits" aria-hidden="true"><div></div><div></div><div></div><span><Activity :size="46" :stroke-width="1.1"/></span></div><div class="auth-privacy"><ShieldCheck :size="18"/><span>本机运行 · 私人数据留在这台电脑</span></div></section><section class="auth-form-wrap"><div class="auth-form-box"><span class="icon-tile mint"><LockKeyhole :size="24"/></span><span class="eyebrow">YOUR PRIVATE JOURNAL</span><h2>{{state.configured?'欢迎回到自己的空间':'创建你的私人空间'}}</h2><p>{{state.configured?'输入本地口令，继续记录日常。':'设置一个本地解锁口令，保护这些只属于你的记录。'}}</p><div v-if="connectionError" class="connection-state"><Info :size="23"/><p>{{connectionError}}</p><button class="button primary full-width" @click="init"><RefreshCw :size="17"/>重新连接本机服务</button></div><form v-else @submit.prevent="login"><label class="field"><span>{{state.configured?'解锁口令':'设置口令'}}</span><div class="password-input"><input v-model="password" :type="showPassword?'text':'password'" :minlength="state.configured?1:8" required :autocomplete="state.configured?'current-password':'new-password'" :placeholder="state.configured?'输入你的本地口令':'至少 8 位，可使用短语'" autofocus/><button type="button" class="icon-button" :title="showPassword?'隐藏口令':'显示口令'" @click="showPassword=!showPassword"><EyeOff v-if="showPassword" :size="18"/><Eye v-else :size="18"/></button></div></label><label v-if="!state.configured" class="field"><span>再次输入口令</span><input v-model="passwordAgain" :type="showPassword?'text':'password'" minlength="8" required autocomplete="new-password" placeholder="确认两次输入一致"/></label><p v-if="authError" class="error-box" role="alert">{{authError}}</p><button class="button primary full-width auth-submit" :disabled="authBusy"><LoaderCircle v-if="authBusy" class="spin" :size="18"/><span>{{authBusy?'正在打开':state.configured?'解锁我的空间':'开始记录'}}</span><ArrowRight :size="18"/></button></form><div class="auth-footnote"><ShieldCheck :size="15"/><span>无需注册云端账号 · 15 分钟无操作自动锁定</span></div></div><footer>自记 <span>为自己留一份真实的日常</span></footer></section></div>
<div v-else class="app-shell" :class="{privacy:state.settings.hidden}">
  <div v-if="sidebar" class="mobile-scrim" @click="sidebar=false"></div>
  <aside class="sidebar" :class="{open:sidebar}" @keydown.esc="sidebar=false">
    <a class="brand" href="#dashboard" @click.prevent="go('dashboard')"><span class="brand-mark"><Activity :size="25" :stroke-width="1.8"/></span><span>自记</span></a>
    <nav v-for="group in navGroups" :key="group.label" class="shell-nav" :aria-label="group.label">
      <div class="nav-label">{{group.label}}</div>
      <button v-for="n in group.items" :key="n.id" :class="{active:page===n.id}" :aria-current="page===n.id?'page':undefined" @click="go(n.id)">
        <component :is="n.icon" :size="18" :stroke-width="1.7"/><span>{{n.label}}</span>
      </button>
    </nav>
    <div class="shell-local"><ShieldCheck :size="15"/>本机存储</div>
  </aside>
  <main class="main"><div class="main-content">
    <header class="shell-header">
      <div class="shell-title">
        <button class="icon-button mobile-menu" aria-label="打开导航" :aria-expanded="sidebar" @click="sidebar=true"><Menu :size="21"/></button>
        <div><h1>{{currentNav.label}}</h1><p v-if="page==='dashboard'">{{todayLabel}}</p></div>
      </div>
      <div class="shell-actions">
        <div class="report-split" role="group" aria-label="导出分析记录">
          <button type="button" title="下载最近 30 天的 Markdown，默认不含私人记录" @click="exportMode='quick'"><Download :size="15"/>导出近 30 天</button>
          <button type="button" title="选择导出日期、内容和格式" aria-label="选择导出日期、内容和格式" aria-haspopup="dialog" @click="exportMode='custom'"><ChevronDown :size="15"/></button>
        </div>
        <label v-if="page==='analytics'" class="date-control"><CalendarDays :size="16"/><select v-model.number="days" aria-label="统计时间范围"><option v-if="detailMonth" :value="0">{{detailMonth}} 月度</option><option :value="7">最近 7 天</option><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option></select></label>
        <button v-if="page==='body'" class="button primary compact-button" @click="openEditor('body')"><Plus :size="16"/>记录测量</button>
        <button class="icon-button" :aria-pressed="!!state.settings.hidden" :title="state.settings.hidden?'显示记录':'隐私遮挡'" @click="togglePrivacy"><EyeOff v-if="state.settings.hidden" :size="18"/><Eye v-else :size="18"/></button>
        <button class="icon-button" title="刷新数据" @click="reload" :disabled="state.loading"><RefreshCw :size="17" :class="{spin:state.loading}"/></button>
        <button class="icon-button" title="锁定空间" @click="lock"><LockKeyhole :size="17"/></button>
      </div>
    </header>
<div v-if="state.error" class="error-box main-error" role="alert">{{state.error}}<button class="text-link" @click="reload">重试</button></div>
<Dashboard v-if="page==='dashboard'" :from="from" :to="analysisEnd" :days="activeDays" :scope="homeScope" :selected-date="selectedDay" :loading="homeLoading" :quick-saving="quickSaving" @scope="changeHomeScope" @select-day="selectHomeDay" @month="selectHomeDay" @record="openRecord" @quick="quick" @view="go" @edit="editFromDashboard" @repair="repairAlcohol"/>
<Ledger v-else-if="page==='ledger'" :refresh-key="journalRefresh" @notify="notify"/>
<LifeJournal v-else-if="page==='journal'" :refresh-key="journalRefresh"/>
<template v-else-if="page==='history'"><div class="history-tabs segmented"><button v-for="t in [{v:'smoking',l:'吸烟记录'},{v:'sessions',l:'饮酒场次'},{v:'drinks',l:'酒品明细'},{v:'intimacy',l:'私人记录'}]" :key="t.v" :class="{active:historyKind===t.v}" @click="historyKind=t.v">{{t.l}}</button></div><DataManager :key="historyKind" :collection="historyKind" @edit="openEditor" @changed="updateAnalytics" @notify="notify"/></template>
<Analytics v-else-if="page==='analytics'" :from="from" :to="analysisEnd" @view="go" @repair="repairAlcohol"/>
<BodyDashboard v-else-if="page==='body'" @edit="row=>openEditor('body',row)" @remove="row=>{bodyDeleteError='';bodyDelete=row}"/>
<template v-else-if="page==='products'"><div class="history-tabs segmented"><button :class="{active:productTab==='products'}" @click="productTab='products'">商品资料</button><button :class="{active:productTab==='prices'}" @click="productTab='prices'">价格历史</button></div><Products v-if="productTab==='products'" @edit="openEditor" @changed="updateAnalytics" @notify="notify"/><DataManager v-else collection="prices" @edit="openEditor" @changed="updateAnalytics" @notify="notify"/></template>
<DataManager v-else-if="page==='manager'" manager :refresh-key="journalRefresh" @edit="openEditor" @changed="updateAnalytics" @notify="notify"/>
<Settings v-else-if="page==='settings'" @view="go" @edit="openEditor" @changed="updateAnalytics" @notify="notify" @lock="lock"/>
</div></main>
<ReportExport v-if="exportMode" :quick="exportMode==='quick'" @close="exportMode=null" @notify="notify"/>
<RecordDrawer v-if="record" :key="record.kind+(record.row?.id||record.session?.id||'new')" :kind="record.kind" :row="record.row" :session="record.session" @close="record=null" @saved="afterSaved" @refresh="reload"/>
<BodyEditor v-if="editor?.collection==='body'" :key="editor.row?.id||'new-body'" :row="editor.row" @close="editor=null" @saved="afterSaved"/>
<JournalEditor v-else-if="editor?.collection==='journal'" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved" @refresh="reload"/>
<TaskEditor v-else-if="editor?.collection==='tasks'" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved"/>
<GenericEditor v-else-if="editor" :key="editor.collection+editor.row?.id" :collection="editor.collection" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved"/>
<div v-if="bodyDelete" class="overlay centered" @click.self="bodyDelete=null"><section class="modal"><header><h2>移除这次身体测量？</h2><button class="icon-button" title="关闭" @click="bodyDelete=null"><X :size="20"/></button></header><p>测量于 {{shortDate(bodyDelete.measuredAt)}}。趋势和酒精估算会随之更新，之后可在回收站恢复。</p><p v-if="bodyDeleteError" class="error-box">{{bodyDeleteError}}</p><footer><button class="button secondary" @click="bodyDelete=null">保留</button><button class="button danger" :disabled="bodyDeleteBusy" @click="removeBody">移入回收站</button></footer></section></div>
</div><Transition name="toast"><div v-if="toast" class="toast" role="status"><span class="toast-icon"><Check :size="17"/></span><p>{{toast.message}}</p><button v-if="toast.undo" class="toast-undo" @click="toast.undo().catch(e=>notify(e.message))">撤销</button><button class="icon-button" title="关闭提示" @click="toast=null"><X :size="16"/></button></div></Transition>
</template>

<style scoped>
.sidebar {overflow-y:auto}
.shell-nav {margin-top:24px}
.shell-nav .nav-label {margin:0 12px 4px;color:var(--muted)}
.shell-local {display:flex;align-items:center;gap:8px;margin-top:auto;padding:25px 12px 20px;color:var(--muted);font-size:12px}
.shell-header {display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px;padding-bottom:16px;border-bottom:1px solid var(--border)}
.shell-title, .shell-actions {display:flex;align-items:center;gap:6px}
.shell-title h1 {font-size:clamp(22px,2vw,28px);line-height:1.2;letter-spacing:-.03em}
.shell-title p {margin-top:6px;color:var(--muted);font-size:12px}
.shell-actions .icon-button[aria-pressed=true] {color:var(--mint);background:var(--mint-bg)}
.report-split {display:inline-flex;align-items:stretch;border:1px solid var(--border);border-radius:8px;background:var(--panel)}
.report-split button {display:inline-flex;align-items:center;gap:6px;font-size:12px;padding:7px 9px;color:var(--text);border-radius:7px}
.report-split button+button {border-left:1px solid var(--border);border-radius:0 7px 7px 0;padding-inline:7px}
.report-split button:hover {background:var(--panel-soft)}
@media(max-width:600px){
  .shell-header {gap:4px;margin-bottom:14px;padding-bottom:12px;flex-wrap:wrap}
  .shell-actions {margin-left:auto;gap:2px}
  .shell-actions .date-control {padding:4px}
}
</style>
