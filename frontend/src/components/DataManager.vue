<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {Search,Plus,Trash2,Pencil,RotateCcw,ChevronLeft,ChevronRight,Download,Database,SlidersHorizontal,FileClock,Eye,X,Calculator,Info,ArrowDown,ArrowUp,BookOpen,CheckSquare,Cigarette,Wine,Heart,Package,Activity,Archive,FileText,Wallet} from 'lucide-vue-next'
import {recordCostLabel} from '../productPricing'
import {dataGroups,dataGuide,preferredColumns,derivedColumns,valueFor,matchesRecord,compareRecords,calculationNotes,csvCell} from '../dataManagement'
import RecalculateDialog from './RecalculateDialog.vue'
import LifeJournal from './LifeJournal.vue'
import {api,state,deleteRow,restoreRow,refresh} from '../api'
import type {Row,Field} from '../types'
import {collectionLabels,shanghaiDate} from '../types'

const props=defineProps<{collection?:string;manager?:boolean;refreshKey?:number}>()
const emit=defineEmits<{edit:[collection:string,row?:Row,preset?:Record<string,any>];changed:[];notify:[message:string]}>()
const selectedCollection=ref(props.collection||'smoking'),search=ref(''),page=ref(1),pageSize=ref(20)
const trash=ref(false),fetchedRows=ref<Row[]>([]),rowsLoading=ref(false),error=ref(''),busy=ref(false)
const selected=ref<string[]>([]),confirmDelete=ref<Row[]|null>(null),inspecting=ref<Row|null>(null),audit=ref<any[]|null>(null),recalcRow=ref<Row|null>(null)
const sortKey=ref('recordDate'),sortDirection=ref('desc'),columnChoices=ref<Record<string,string[]>>({}),legacyOpen=ref(false),guideOpen=ref(false)
const icons:Record<string,any>={smoking:Cigarette,sessions:Wine,drinks:Wine,intimacy:Heart,journal:BookOpen,tasks:CheckSquare,products:Package,body:Activity,prices:FileClock,health:FileText,purchases:Archive,ledger:Wallet}
const groups=computed(()=>dataGroups.map(g=>({...g,collections:g.collections.filter(c=>state.schema.some(s=>s.name===c))})).filter(g=>g.collections.length))
const label=(name:string)=>dataGuide[name]?.label||collectionLabels[name]||name
const guide=computed(()=>dataGuide[selectedCollection.value])
const schema=computed(()=>state.schema.find(s=>s.name===selectedCollection.value))
const pagedCollection=computed(()=>['journal','tasks'].includes(selectedCollection.value))
const availableFields=computed(()=>[...(schema.value?.fields||[]),...(derivedColumns[selectedCollection.value]||[]).filter(f=>!schema.value?.fields.some(s=>s.key===f.key))])
const chosenColumns=computed({get:()=>columnChoices.value[selectedCollection.value]||preferredColumns[selectedCollection.value]||availableFields.value.slice(0,6).map(f=>f.key),set:(v:string[])=>columnChoices.value[selectedCollection.value]=v})
const displayFields=computed(()=>chosenColumns.value.map(key=>availableFields.value.find(f=>f.key===key)).filter(Boolean) as Field[])
const rows=computed(()=>(trash.value||selectedCollection.value==='ledger')?fetchedRows.value:state.collections[selectedCollection.value]||[])
const filtered=computed(()=>rows.value.filter(r=>matchesRecord(r,availableFields.value,state.collections,search.value)).sort((a,b)=>compareRecords(a,b,sortKey.value,sortDirection.value,availableFields.value,state.collections)))
const pages=computed(()=>Math.max(1,Math.ceil(filtered.value.length/pageSize.value)))
const visible=computed(()=>filtered.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
const hasCost=computed(()=>['smoking','drinks'].includes(selectedCollection.value))
const hasAlcoholLink=computed(()=>!trash.value&&['smoking','intimacy'].includes(selectedCollection.value))
const allSelected=computed(()=>visible.value.length>0&&visible.value.every(r=>selected.value.includes(r.id)))
const notes=computed(()=>inspecting.value?calculationNotes(selectedCollection.value,inspecting.value):[])
const display=(r:Row,f:Field)=>valueFor(r,f,state.collections)
let rowsRequest = 0
async function loadRows() {
  const request = ++rowsRequest
  const collection = selectedCollection.value
  const deleted = trash.value
  fetchedRows.value = []
  rowsLoading.value = false
  if (pagedCollection.value || (!deleted && collection !== 'ledger')) return
  rowsLoading.value = true
  try {
    const result = await api<Row[]>(`/data/${collection}${deleted ? '?deleted=true' : ''}`)
    if (request === rowsRequest) fetchedRows.value = result
  } catch (e) {
    if (request === rowsRequest) error.value = (e as Error).message
  } finally {
    if (request === rowsRequest) rowsLoading.value = false
  }
}
watch(()=>props.collection,v=>{if(v)selectedCollection.value=v})
watch(selectedCollection,()=>{search.value='';trash.value=false;page.value=1;selected.value=[];error.value='';guideOpen.value=false;sortKey.value='recordDate';sortDirection.value='desc'})
watch(trash,()=>{selected.value=[];page.value=1;error.value=''})
watch([selectedCollection,trash,()=>state.collections],()=>{selected.value=[];void loadRows()},{immediate:true})
watch([search,pageSize,sortKey,sortDirection],()=>{page.value=1;selected.value=[]})
watch(page,()=>selected.value=[])
watch(pages,v=>{page.value=Math.min(page.value,v)})
function sortBy(field:Field){if(sortKey.value===field.key)sortDirection.value=sortDirection.value==='asc'?'desc':'asc';else{sortKey.value=field.key;sortDirection.value='asc'}}
function toggleAll(){selected.value=allSelected.value?[]:visible.value.map(r=>r.id)}
function alcoholLink(row:Row){return (selectedCollection.value==='smoking'?state.analytics.smokingLinks:state.analytics.links)?.[row.id]}
function outsideAnalysis(row:Row){const day=String(row.occurredAt||'').slice(0,10);return !!day&&!!state.analytics.from&&!!state.analytics.to&&(day<state.analytics.from||day>state.analytics.to)}
function alcoholReason(row:Row){return alcoholLink(row)?.reason||(outsideAnalysis(row)?`这条记录不在当前分析日期 ${state.analytics.from} 至 ${state.analytics.to} 内，尚未生成关联结果；并非记录有误。`:'尚未取得关联结果，可刷新数据或在统计分析页查看判断依据。')}
function alcoholLinkLabel(row:Row){const link=alcoholLink(row);if(!link)return outsideAnalysis(row)?'分析范围外':'尚未分析';if(link.source==='MANUAL')return link.effective==='YES'?'本人确认酒后':link.effective==='NO'?'本人确认非酒后':'本人不确定';return ({DURING_DRINKING:'饮酒期间',POSSIBLE_AFTER_DRINKING:'估算酒后',RECENT_RECORDED_DRINKING:'近期有饮酒',NO_RECORDED_RECENT_DRINKING:'未找到饮酒记录',INSUFFICIENT_DATA:'信息不足'} as Record<string,string>)[link.status]||'尚未分析'}
async function remove(){
  if(!confirmDelete.value)return
  busy.value=true;error.value=''
  try{for(const r of confirmDelete.value)await deleteRow(selectedCollection.value,r);await refresh();selected.value=[];confirmDelete.value=null;emit('changed');emit('notify','已移入回收站，可以随时恢复。')}
  catch(e){error.value=(e as Error).message;confirmDelete.value=null;await refresh().catch(()=>{});emit('changed')}
  finally{busy.value=false}
}
async function restore(r:Row){busy.value=true;error.value='';try{await restoreRow(selectedCollection.value,r);await refresh();emit('changed');emit('notify','记录已恢复。')}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
function exportRows(){
  const fields=[...availableFields.value,...(hasCost.value?[{key:'cost',label:'按用量费用（元）',type:'number'}]:[])]
  const csv='\ufeff'+[fields.map(f=>csvCell(f.label)).join(','),...filtered.value.map(r=>fields.map(f=>csvCell(display(r,f))).join(','))].join('\r\n')
  const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));a.download=`自记-${label(selectedCollection.value)}${trash.value?'-回收站':''}-${shanghaiDate()}.csv`;a.click();URL.revokeObjectURL(a.href)
  emit('notify',`已导出当前筛选的 ${filtered.value.length} 条记录（全部字段）。`)
}
async function showAudit(){error.value='';try{const result=await api<any>('/audit');audit.value=Array.isArray(result)?result:result.rows||result.items||[]}catch(e){error.value=(e as Error).message}}
const auditLabel=(action:string)=>({CREATE:'新增',UPDATE:'修改',DELETE:'移入回收站',RESTORE:'恢复',RECALCULATE:'历史重算',FILL_MISSING_COST:'补齐费用',SETTINGS:'修改设置',BACKUP:'创建备份',IMPORT:'导入'} as Record<string,string>)[action]||action
function auditTitle(item:any){const row=item.after||item.before||{};return row.title||row.snapshotName||row.name||row.occurredAt||row.measuredAt||item.rowId}
</script>

<template>
  <div class="data-page" :class="{'manager-layout':manager}">
    <aside v-if="manager" class="data-nav" aria-label="数据分类">
      <div class="data-nav-title"><Database :size="15"/>数据目录</div>
      <template v-for="group in groups" :key="group.label">
        <button v-if="group.legacy" class="data-group-toggle" :aria-expanded="legacyOpen" @click="legacyOpen=!legacyOpen"><ChevronRight :size="13" :class="{expanded:legacyOpen}"/>{{group.label}}</button>
        <h4 v-else>{{group.label}}</h4>
        <div v-if="!group.legacy||legacyOpen" class="data-nav-group">
          <button v-for="name in group.collections" :key="name" :class="{active:selectedCollection===name}" :aria-current="selectedCollection===name?'page':undefined" @click="selectedCollection=name">
            <component :is="icons[name]||Database" :size="15"/><span>{{label(name)}}</span><small v-if="!['journal','tasks','ledger'].includes(name)" class="sensitive">{{state.collections[name]?.length||0}}</small>
          </button>
        </div>
      </template>
      <p class="data-nav-foot">兼容数据保留旧记录，<br/>日常使用无需维护。</p>
      <button class="data-audit-link" @click="showAudit"><FileClock :size="14"/>操作历史</button>
    </aside>
    <div class="data-workspace">
      <label v-if="manager" class="data-mobile-nav">数据分类<select v-model="selectedCollection"><optgroup v-for="group in groups" :key="group.label" :label="group.label"><option v-for="name in group.collections" :key="name" :value="name">{{label(name)}}</option></optgroup></select></label>
      <p v-if="error" class="error-box" role="alert">{{error}}</p>
      <section v-if="guide" class="data-context">
        <div><span class="data-context-dot"></span><p>{{guide.summary}}</p><button class="text-link" :aria-expanded="guideOpen" @click="guideOpen=!guideOpen"><Info :size="14"/>用途与规则<ChevronRight :size="13" :class="{expanded:guideOpen}"/></button></div>
        <div v-if="guideOpen" class="data-guide"><p><strong>用在哪里</strong>{{guide.usage}}</p><ul><li v-for="rule in guide.rules" :key="rule">{{rule}}</li></ul></div>
      </section>
      <LifeJournal v-if="pagedCollection" :key="selectedCollection" :initial-collection="selectedCollection==='tasks'?'tasks':'journal'" :refresh-key="refreshKey" management/>
      <section v-else class="panel table-panel data-table">
        <header class="table-heading">
          <div><h3>{{label(selectedCollection)}}<span v-if="trash" class="tag">回收站</span><span class="count-badge sensitive">{{rows.length}}</span></h3><p>{{trash?'已删除的数据，可恢复后继续使用':'全部日期 · 点击列名排序，点击详情查看依据'}}</p></div>
          <div class="toolbar"><button class="button secondary compact-button" :disabled="!filtered.length||rowsLoading" @click="exportRows"><Download :size="14"/>导出结果</button><button v-if="!trash" class="button primary compact-button" @click="emit('edit',selectedCollection)"><Plus :size="15"/>新增</button></div>
        </header>
        <div class="table-filters">
          <label class="search-box"><Search :size="16"/><input v-model="search" aria-label="搜索当前分类" placeholder="搜索名称、日期、备注…"/><button v-if="search" class="icon-button" title="清空搜索" @click="search=''"><X :size="13"/></button></label>
          <label class="data-sort"><span class="sr-only">时间排序</span><select aria-label="时间排序" :value="sortKey==='recordDate'?sortDirection:''" @change="sortKey='recordDate';sortDirection=($event.target as HTMLSelectElement).value"><option value="" disabled>按所选列排序</option><option value="desc">时间从新到旧</option><option value="asc">时间从旧到新</option></select></label>
          <details class="data-columns"><summary><SlidersHorizontal :size="14"/>显示列</summary><div><header>显示字段<button class="text-link" @click="delete columnChoices[selectedCollection]">恢复默认</button></header><label v-for="field in availableFields" :key="field.key"><input v-model="chosenColumns" type="checkbox" :value="field.key" :disabled="chosenColumns.length===1&&chosenColumns.includes(field.key)"/>{{field.label}}</label></div></details>
          <button class="filter-toggle" :class="{active:trash}" @click="trash=!trash"><RotateCcw :size="14"/>{{trash?'返回数据':'回收站'}}</button>
        </div>
        <div v-if="selected.length&&!trash" class="data-selection"><span>已选本页 {{selected.length}} 条</span><button class="text-link" @click="selected=[]">取消选择</button><button class="button danger compact-button" :disabled="busy" @click="confirmDelete=visible.filter(r=>selected.includes(r.id))"><Trash2 :size="14"/>移入回收站</button></div>
        <div class="table-scroll" :aria-busy="rowsLoading">
          <table><thead><tr>
            <th v-if="!trash" class="checkbox-cell"><input type="checkbox" aria-label="选择本页全部" :checked="allSelected" :indeterminate="selected.length>0&&!allSelected" @change="toggleAll"/></th>
            <th v-for="field in displayFields" :key="field.key" :aria-sort="sortKey===field.key?(sortDirection==='asc'?'ascending':'descending'):'none'"><button class="data-column-sort" @click="sortBy(field)">{{field.label}}<ArrowUp v-if="sortKey===field.key&&sortDirection==='asc'" :size="12"/><ArrowDown v-else-if="sortKey===field.key" :size="12"/></button></th>
            <th v-if="hasCost">按用量费用</th><th v-if="hasAlcoholLink">饮酒关联</th><th class="actions-cell">操作</th>
          </tr></thead><tbody><tr v-for="row in visible" :key="row.id" :class="{'data-selected':selected.includes(row.id)}">
            <td v-if="!trash" class="checkbox-cell"><input v-model="selected" type="checkbox" :aria-label="`选择记录 ${row.id}`" :value="row.id"/></td>
            <td v-for="field in displayFields" :key="field.key" class="sensitive" :title="display(row,field)"><span v-if="field.type==='boolean'" class="tag" :class="{mint:row[field.key]}">{{display(row,field)}}</span><span v-else>{{display(row,field)}}</span></td>
            <td v-if="hasCost" class="sensitive data-cost">{{recordCostLabel(row,selectedCollection)}}<small v-if="row.costType==='REFERENCE'" class="muted"> 估算</small></td>
            <td v-if="hasAlcoholLink" class="sensitive"><span class="tag" :class="{intimacy:alcoholLink(row)?.effective==='YES'}" :title="alcoholReason(row)">{{alcoholLinkLabel(row)}}</span></td>
            <td class="actions-cell"><button class="icon-button" title="详情与计算依据" @click="inspecting=row"><Eye :size="15"/></button><template v-if="!trash"><button v-if="['products','prices','purchases'].includes(selectedCollection)" class="icon-button" title="预览历史重算" @click="recalcRow=row"><Calculator :size="15"/></button><button class="icon-button" title="编辑" @click="emit('edit',selectedCollection,row)"><Pencil :size="14"/></button><button class="icon-button" title="移入回收站" :disabled="busy" @click="confirmDelete=[row]"><Trash2 :size="14"/></button></template><button v-else class="icon-button" title="恢复" :disabled="busy" @click="restore(row)"><RotateCcw :size="15"/></button></td>
          </tr></tbody></table>
          <div v-if="rowsLoading" class="empty-state"><p role="status">正在读取记录…</p></div>
          <div v-else-if="!visible.length" class="empty-state"><Database :size="29"/><strong>{{search?'没有匹配的记录':trash?'回收站是空的':'这里还没有记录'}}</strong><p>{{search?'可以搜索名称、中文选项、日期和备注，多个词用空格隔开。':guide?.summary}}</p><button v-if="search" class="button secondary compact-button" @click="search=''">清空搜索</button><button v-else-if="!trash" class="button secondary compact-button" @click="emit('edit',selectedCollection)"><Plus :size="14"/>新增记录</button></div>
        </div>
        <footer class="table-footer"><span class="sensitive">{{filtered.length?`${(page-1)*pageSize+1}–${Math.min(page*pageSize,filtered.length)}`:'0'}} / {{filtered.length}} 条<span v-if="search">（筛选前 {{rows.length}} 条）</span></span><div><label>每页 <select v-model.number="pageSize" aria-label="每页条数"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select></label><button class="icon-button" title="上一页" :disabled="page<=1" @click="page--"><ChevronLeft :size="17"/></button><span>{{page}} / {{pages}}</span><button class="icon-button" title="下一页" :disabled="page>=pages" @click="page++"><ChevronRight :size="17"/></button></div></footer>
      </section>
      <p v-if="!pagedCollection" class="page-note">修改保留操作历史；移入回收站后不参与当前统计，可随时恢复。</p>
      <button v-if="manager" class="data-mobile-audit text-link" @click="showAudit"><FileClock :size="14"/>操作历史</button>
    </div>
    <div v-if="confirmDelete" class="overlay centered" @click.self="!busy&&(confirmDelete=null)"><section class="modal" role="dialog" aria-modal="true" aria-label="确认移入回收站"><header><span class="icon-tile danger"><Trash2 :size="22"/></span><button class="icon-button" :disabled="busy" @click="confirmDelete=null" title="关闭"><X :size="20"/></button></header><h2>将 {{confirmDelete.length}} 条记录移入回收站？</h2><p>相关费用与统计会更新。你可以从回收站恢复；有其他数据引用时，系统会说明处理方式。</p><footer><button class="button secondary" :disabled="busy" @click="confirmDelete=null">保留记录</button><button class="button danger" :disabled="busy" @click="remove">{{busy?'正在处理':'移入回收站'}}</button></footer></section></div>
    <div v-if="inspecting||audit" class="overlay" @click.self="inspecting=null;audit=null"><section class="drawer data-detail" role="dialog" aria-modal="true" :aria-label="audit?'操作历史':'记录详情与计算依据'">
      <header class="drawer-header"><div><small>{{audit?'本机操作记录':label(selectedCollection)}}</small><h2>{{audit?'操作历史':'记录详情与计算依据'}}</h2></div><button class="icon-button" @click="inspecting=null;audit=null" title="关闭"><X :size="20"/></button></header>
      <div class="drawer-body">
        <template v-if="inspecting">
          <p class="data-detail-intro">{{guide?.usage}}</p>
          <section v-if="notes.length||hasAlcoholLink" class="data-calculations sensitive"><h3><Calculator :size="16"/>计算与判断</h3><dl><template v-for="note in notes" :key="note.label"><dt>{{note.label}}</dt><dd>{{note.value}}</dd></template><template v-if="hasAlcoholLink"><dt>饮酒关联 · {{alcoholLinkLabel(inspecting)}}</dt><dd>{{alcoholReason(inspecting)}}<p>时间精度与观察窗口影响关联，不代表因果关系或医学判断。</p></dd></template></dl></section>
          <h3 class="data-section-title">录入的字段</h3><dl class="data-field-list sensitive"><div v-for="field in schema?.fields" :key="field.key"><dt>{{field.label}}</dt><dd>{{display(inspecting,field)==='—'?'未填写':display(inspecting,field)}}</dd></div></dl>
          <dl class="data-meta sensitive"><div><dt>创建时间</dt><dd>{{String(inspecting.createdAt||'—').replace('T',' ')}}</dd></div><div><dt>最后修改</dt><dd>{{String(inspecting.updatedAt||'—').replace('T',' ')}}</dd></div><div><dt>记录版本</dt><dd>{{inspecting.version}}</dd></div></dl>
          <details class="data-raw"><summary>原始数据 · 排查问题时使用</summary><pre class="json-detail sensitive">{{JSON.stringify(inspecting,null,2)}}</pre></details>
        </template>
        <template v-else-if="audit"><p class="data-detail-intro">最近 {{audit.length}} 条操作，包含新增、修改、恢复及自动补齐。展开可核对修改前后的数据。</p><div v-if="!audit.length" class="empty-state">还没有操作历史。</div><details v-for="item in audit" :key="item.id" class="data-audit-item"><summary><span class="tag">{{auditLabel(item.action)}}</span><span>{{label(item.collection)}}<small class="sensitive">{{auditTitle(item)}}</small></span><time>{{String(item.occurredAt).replace('T',' ').slice(0,16)}}</time></summary><h4>修改前</h4><pre class="json-detail sensitive">{{item.before?JSON.stringify(item.before,null,2):'无（新增记录）'}}</pre><h4>修改后</h4><pre class="json-detail sensitive">{{item.after?JSON.stringify(item.after,null,2):'无'}}</pre></details></template>
      </div>
      <footer v-if="inspecting&&!trash" class="drawer-footer"><span class="muted small">可编辑原始字段，计算结果由系统生成。</span><button class="button primary compact-button" @click="emit('edit',selectedCollection,inspecting);inspecting=null"><Pencil :size="15"/>编辑记录</button></footer>
    </section></div>
    <RecalculateDialog v-if="recalcRow" :collection="selectedCollection" :source-id="recalcRow.id" @close="recalcRow=null" @saved="recalcRow=null;emit('changed');emit('notify','已按预览重算历史记录。')"/>
  </div>
</template>

<style scoped src="../dataManagement.css"></style>
