<script setup lang="ts">
import {computed,onBeforeUnmount,ref} from 'vue'
import {Search,Plus,Star,Cigarette,Wine,ArrowUpRight,Pencil,Database,Globe,Check,X,LoaderCircle,Info,Calculator} from 'lucide-vue-next'
import RecalculateDialog from './RecalculateDialog.vue'
import {api,state,saveRow,refresh} from '../api'
import type {Row} from '../types'
import {money,number} from '../types'
import {productPrice,productName} from '../productPricing'
import {recordDrafts} from '../drafts'
const emit=defineEmits<{edit:[collection:string,row?:Row,preset?:Record<string,any>];changed:[];notify:[message:string];prices:[id:string]}>()
const savedFilters=recordDrafts['product-catalog-filters']
const search=ref<string>(savedFilters?.search||''),kind=ref<string>(savedFilters?.kind||'ALL'),favorites=ref(Boolean(savedFilters?.favorites))
const catalogOpen=ref(false),catalogQuery=ref(''),candidates=ref<any[]>([]),catalogMessage=ref(''),loading=ref(false),error=ref(''),selectedCandidate=ref<any>(null)
onBeforeUnmount(()=>{
  if(state.authenticated) recordDrafts['product-catalog-filters']={search:search.value,kind:kind.value,favorites:favorites.value}
})
const catalogWarnings=ref<string[]>([]),recalcRow=ref<Row|null>(null)
const products=computed(()=>(state.collections.products||[]).filter(p=>(kind.value==='ALL'||p.kind===kind.value)&&(!favorites.value||p.favorite)&&`${p.brand} ${p.name} ${p.variant} ${p.aliases||''} ${p.barcode||''}`.toLowerCase().includes(search.value.toLowerCase())))

async function favorite(p:Row){try{await saveRow('products',{...p,favorite:!p.favorite},p);await refresh();emit('changed')}catch(e){error.value=(e as Error).message}}
async function catalogSearch(network=false){loading.value=true;error.value='';candidates.value=[];selectedCandidate.value=null;try{const r=await api<any>(`/catalog/search?q=${encodeURIComponent(catalogQuery.value.trim())}${network?'&refresh=true':''}`);candidates.value=Array.isArray(r)?r:r.candidates||r.results||[];catalogMessage.value=r.message||r.note||'';catalogWarnings.value=r.warnings||[]}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
async function importCandidate(){if(!selectedCandidate.value)return;loading.value=true;try{await api('/catalog/import',{method:'POST',body:JSON.stringify({candidate:selectedCandidate.value})});await refresh();emit('changed');emit('notify','商品资料已保存，可点“编辑商品与单价”确认这个种类的价格。');selectedCandidate.value=null;catalogOpen.value=false}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
</script>
<template>
<div class="products-page">
  <header class="catalog-heading">
    <h3>烟酒商品 <small class="sensitive">{{ products.length }} 种</small></h3>
    <div class="toolbar">
      <button class="button secondary compact-button" @click="catalogOpen=true;error='';catalogSearch(false)"><Globe :size="15"/>查找商品资料</button>
      <button class="button primary compact-button" @click="emit('edit','products',undefined,{kind:kind==='ALL'?'SMOKING':kind})"><Plus :size="16"/>添加商品</button>
    </div>
  </header>
  <div class="product-filters">
    <div class="segmented" role="group" aria-label="商品种类">
      <button v-for="k in [{v:'ALL',l:'全部商品'},{v:'SMOKING',l:'香烟'},{v:'ALCOHOL',l:'酒类'}]" :key="k.v" :class="{active:kind===k.v}" :aria-pressed="kind===k.v" @click="kind=k.v">{{ k.l }}</button>
    </div>
    <label class="search-box"><Search :size="15"/><input v-model="search" type="search" aria-label="搜索商品" placeholder="搜索品牌、规格或条码"/></label>
    <button class="filter-toggle" :class="{active:favorites}" :aria-pressed="favorites" @click="favorites=!favorites"><Star :size="15"/>常用</button>
  </div>
  <p v-if="error&&!catalogOpen" class="error-box" role="alert">{{ error }}</p>
  <div v-if="!products.length" class="panel empty-state">
    <Database :size="24"/>
    <strong>{{ search || favorites || kind !== 'ALL' ? '没有匹配的商品' : '尚未添加商品' }}</strong>
    <button v-if="search || favorites || kind !== 'ALL'" class="text-link" @click="search='';favorites=false;kind='ALL'">清除筛选</button>
    <button v-else class="text-link" @click="emit('edit','products')"><Plus :size="14"/>添加第一件商品</button>
  </div>
  <div v-else class="catalog-grid sensitive">
    <article v-for="p in products" :key="p.id" class="catalog-item panel">
      <header>
        <span class="catalog-icon" :class="{wine:p.kind==='ALCOHOL'}"><Cigarette v-if="p.kind==='SMOKING'" :size="19"/><Wine v-else :size="19"/></span>
        <div class="catalog-name">
          <h4>{{ productName(p) }}</h4>
          <small>{{ p.kind === 'SMOKING' ? '香烟 · '+(p.unitCount??'—')+' 根 / 包' : '酒类 · '+(p.bottleMl??'—')+' ml / 瓶' }}</small>
        </div>
        <button class="icon-button favorite" :class="{isFavorite:p.favorite}" :aria-pressed="!!p.favorite" title="加入或移出常用" @click="favorite(p)"><Star :size="16" :fill="p.favorite?'currentColor':'none'"/></button>
      </header>
      <dl class="catalog-specs">
        <template v-if="p.kind==='SMOKING'">
          <div><dt>焦油</dt><dd>{{ p.tarMg??'—' }} <small>mg</small></dd></div>
          <div><dt>烟气烟碱</dt><dd>{{ p.nicotineMg??'—' }} <small>mg</small></dd></div>
          <div><dt>一氧化碳</dt><dd>{{ p.coMg??'—' }} <small>mg</small></dd></div>
        </template>
        <template v-else>
          <div><dt>酒精度</dt><dd>{{ p.abv??'—' }} <small>% vol</small></dd></div>
          <div><dt>整瓶纯酒精</dt><dd>{{ p.abv!=null&&p.bottleMl?number(p.bottleMl*p.abv/100*0.789,1):'—' }} <small>g</small></dd></div>
          <div><dt>折合 42 度白酒</dt><dd>{{ p.abv!=null&&p.bottleMl?number(Number(p.bottleMl)*Number(p.abv)/42,1):'—' }} <small>ml</small></dd></div>
        </template>
      </dl>
      <div class="catalog-price-row">
        <div><small>{{ productPrice(p).type==='REFERENCE'?'参考单价':'种类单价' }} / {{ p.kind==='SMOKING'?'包':'瓶' }}</small>
          <strong>{{ money(productPrice(p).amount) }}</strong></div>
        <button class="text-link" @click="emit('edit','products',p,{focusPrice:!productPrice(p).confirmed})"><Pencil :size="13"/>{{ productPrice(p).confirmed?'编辑商品与单价':'确认商品与单价' }}</button>
      </div>
      <footer><span class="catalog-status" :class="{verified:p.verified}">{{ p.verified?'资料已核对':'资料待核对' }}</span>
        <a v-if="p.sourceUrl" :href="p.sourceUrl" class="icon-button" title="查看资料来源" target="_blank" rel="noopener noreferrer"><ArrowUpRight :size="15"/></a>
        <button class="icon-button" title="预览历史重算" @click="recalcRow=p"><Calculator :size="15"/></button>
        <button class="text-link" title="查看该商品的价格历史" @click="emit('prices',p.id)">价格历史</button>
      </footer>
    </article>
  </div>
  <div class="soft-notice"><Info :size="16"/><span>香烟成分是包装标示值，不是人体吸收量。低焦油不代表更安全。商品参考价与实际成交价分别保存。</span></div>
<div v-if="catalogOpen" class="overlay" @click.self="catalogOpen=false"><section class="drawer"><header class="drawer-header"><div><h2>查找商品资料</h2></div><button class="icon-button" title="关闭" @click="catalogOpen=false"><X :size="20"/></button></header><div class="drawer-body"><p class="muted">检索仅发送商品关键词，不发送个人记录。导入前请核对品牌、规格和来源。</p><form class="catalog-search" @submit.prevent="catalogSearch(false)"><input v-model="catalogQuery" placeholder="品牌、型号、条码或具体商品页链接"/><button class="button primary" :disabled="loading"><LoaderCircle v-if="loading" :size="17" class="spin"/><Search v-else :size="17"/>本地检索</button></form><button class="button secondary full-width" :disabled="loading" @click="catalogSearch(true)"><Globe :size="16"/>按需联网更新商品资料</button><p v-if="error" class="error-box">{{error}}</p><p v-if="catalogMessage" class="hint">{{catalogMessage}}</p><p v-for="(warning,i) in catalogWarnings" :key="i" class="hint">{{warning}}</p><div v-for="(candidate,i) in candidates" :key="i" class="catalog-candidate" :class="{selected:selectedCandidate===candidate}" @click="selectedCandidate=candidate"><span class="tag">{{candidate.sourceLabel||candidate.source||'商品资料'}}</span><h3>{{(candidate.product||candidate).brand}} {{(candidate.product||candidate).name}}</h3><p>{{(candidate.product||candidate).variant}} · {{(candidate.product||candidate).kind==='SMOKING'?`${(candidate.product||candidate).unitCount??'—'} 根/包`:`${(candidate.product||candidate).bottleMl??'—'} ml · ${(candidate.product||candidate).abv??'—'}% vol`}}</p><p>{{candidate.note}}</p><p class="catalog-price">参考价：{{money(candidate.price?.amount)}} · {{candidate.price?.channel||'渠道待补'}}<br/>资料日期：{{candidate.product?.sourceDate||candidate.price?.effectiveDate||'待补充'}} · 导入后待核对</p><a v-if="candidate.sourceUrl||(candidate.product||candidate).sourceUrl" :href="candidate.sourceUrl||(candidate.product||candidate).sourceUrl" target="_blank" rel="noopener noreferrer" @click.stop>查看来源</a><Check v-if="selectedCandidate===candidate" :size="18"/></div><div v-if="!candidates.length&&!loading" class="empty-state compact"><Globe :size="30"/><strong>从完整的商品名称开始</strong><p>资料源没有覆盖时，可以手动添加或导入本地资料。</p></div><div v-if="selectedCandidate" class="meta-box">选中资料只作为待核对商品保存。参考价格不会修改过去的记录，也不会自动成为本次实际购买价。</div></div><footer class="drawer-footer"><button class="button secondary" @click="catalogOpen=false">关闭</button><button class="button primary" :disabled="!selectedCandidate||loading" @click="importCandidate"><Check :size="17"/>确认导入资料</button></footer></section></div><RecalculateDialog v-if="recalcRow" collection="products" :source-id="recalcRow.id" @close="recalcRow=null" @saved="recalcRow=null;emit('changed');emit('notify','历史记录已按确认的预览重算。')"/></div></template>
<style scoped>
.products-page { container: catalog / inline-size; }
.catalog-heading, .catalog-heading h3, .catalog-grid header, .catalog-price-row, .catalog-grid footer { display:flex; align-items:center; gap:9px; }
.catalog-heading { justify-content:space-between; flex-wrap:wrap; margin:0 0 12px; }
.catalog-heading h3 { margin:0; font-size:17px; }
.catalog-heading small { color:var(--muted); font-size:12px; font-weight:450; }
.product-filters { flex-wrap:wrap; gap:8px; margin-bottom:14px; }
.product-filters .search-box { flex:1 1 180px; max-width:none; width:auto; }
.product-filters .filter-toggle { align-self:stretch; }
.product-filters .search-box input { min-width:0; font-size:12px; }
.catalog-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(min(260px,100%),1fr)); gap:12px; margin-bottom:14px; }
.catalog-grid:has(> :only-child) { grid-template-columns:minmax(0,480px); }
.catalog-grid article { min-width:0; border:1px solid var(--border); border-radius:10px; background:var(--panel); padding:15px; }
.catalog-grid header { align-items:flex-start; }
.catalog-name { min-width:0; flex:1; }
.catalog-name h4 { font-size:14px; font-weight:600; line-height:1.5; overflow-wrap:anywhere; margin:0; }
.catalog-icon { display:grid; place-items:center; border-radius:8px; background:var(--mint-bg); color:var(--mint); width:34px; height:34px; flex:none; }
.catalog-icon.wine { color:var(--blue); background:color-mix(in srgb,var(--blue) 12%,var(--panel)); }
.catalog-grid small,.catalog-specs dt { color:var(--muted); font-size:11px; }
.catalog-grid .favorite { width:28px; height:28px; }
.catalog-grid .favorite.isFavorite { color:var(--mint); }
.catalog-specs { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); margin:14px 0 12px; padding:10px 0; border-block:1px solid var(--border); column-gap:6px; }
.catalog-specs dd { margin:5px 0 0; font-size:15px; font-weight:550; font-variant-numeric:tabular-nums; }
.catalog-price-row { justify-content:space-between; gap:6px; flex-wrap:wrap; }
.catalog-price-row strong { display:block; font-size:19px; font-weight:600; margin-top:3px; }
.catalog-grid .text-link { font-size:12px; }
.catalog-grid footer { border-top:1px solid var(--border); margin-top:12px; padding-top:8px; gap:6px; }
.catalog-grid footer .icon-button { width:27px; height:27px; }
.catalog-status { display:flex; align-items:center; gap:6px; font-size:11px; color:var(--muted); margin-right:auto; }
.catalog-status::before { content:''; width:5px; height:5px; border-radius:50%; background:currentColor; }
.catalog-status.verified { color:var(--mint); }
.products-page .empty-state { gap:10px; padding:25px 14px; margin-bottom:12px; }
@container catalog (max-width:500px) {
  .product-filters .segmented { order:1; }
  .product-filters .filter-toggle { order:2; }
  .product-filters .search-box { order:3; flex-basis:100%; }
}
</style>
