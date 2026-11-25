<script setup lang="ts">
import {computed,reactive,ref,onMounted} from 'vue'
import {X,Check,LoaderCircle,ChevronDown,Cigarette,Wine} from 'lucide-vue-next'
import {state,saveRow} from '../api'
import {productPrice} from '../productPricing'
import type {Row} from '../types'
const props=defineProps<{row?:Row;preset?:Record<string,any>;kind?:string}>()
const emit=defineEmits<{close:[];saved:[row:Row,collection:string]}>()
const priorPrice=productPrice(props.row)
const form=reactive<Record<string,any>>({kind:props.kind||props.preset?.kind||'SMOKING',brand:'',name:'',variant:'',unitCount:20,bottleMl:500,abv:'',packagePrice:'',priceType:'PERSONAL',priceConfirmed:true,verified:true,active:true,favorite:false,locked:false,...props.row,...props.preset})
if(props.row&&form.packagePrice==null){form.packagePrice=priorPrice.amount??'';form.priceType=priorPrice.type}
if(form.packagePrice==null)form.packagePrice=''
const isSmoke=computed(()=>form.kind==='SMOKING'),details=ref(false),freeConfirmed=ref(props.row?.packagePrice===0),saving=ref(false),error=ref(''),createId=crypto.randomUUID()
const priceInput=ref<HTMLInputElement|null>(null)
onMounted(()=>{if(props.preset?.focusPrice)priceInput.value?.focus()})
const confirmPrice=ref(props.row?priorPrice.confirmed:true)
const priceRequired=computed(()=>!props.row||confirmPrice.value)
const specificationRequired=computed(()=>!props.row||(confirmPrice.value&&(!priorPrice.confirmed||Number(form.packagePrice)!==priorPrice.amount)))
const priceLabel=computed(()=>isSmoke.value?'每包单价 · 元':'每瓶单价 · 元')
async function submit(){
 error.value=''
 if(priceRequired.value&&(form.packagePrice===''||form.packagePrice==null)){error.value='请填写种类单价。';return}
 if(confirmPrice.value&&Number(form.packagePrice)===0&&!freeConfirmed.value){error.value='单价为 0 时，请确认这个种类确实免费。';return}
 saving.value=true
 try{
  const keys=state.schema.find(s=>s.name==='products')?.fields.map(f=>f.key)||[]
  const fields=Object.fromEntries(keys.map(k=>[k,form[k]===''?null:form[k]]))
  for(const key of isSmoke.value?['bottleMl','abv']:['unitCount','tarMg','nicotineMg','coMg','constituentMethod'])fields[key]=null
  fields.priceConfirmed=confirmPrice.value
  if(!props.row)fields.id=createId
  const row=await saveRow('products',fields,props.row);emit('saved',row,'products')
 }catch(e){error.value=(e as Error).message}finally{saving.value=false}
}
</script>
<template>
<div class="overlay product-editor-overlay" @click.self="emit('close')"><section class="drawer" role="dialog" aria-modal="true" :aria-label="row?'编辑种类与单价':'新增种类与单价'">
 <header class="drawer-header"><div class="heading-with-icon"><span class="icon-tile" :class="isSmoke?'smoking':'alcohol'"><Cigarette v-if="isSmoke" :size="23"/><Wine v-else :size="23"/></span><div><h2>{{row?'编辑':'新增'}}种类与单价</h2></div></div><button class="icon-button" title="关闭种类编辑" @click="emit('close')"><X :size="20"/></button></header>
 <form class="drawer-form" @submit.prevent="submit"><div class="drawer-body">
 <p class="hint">设置一次单价，之后按抽的根数或本人喝的量自动算费。无需填写每次购买流水。</p>
 <div class="fields-grid">
 <label class="field"><span>类别</span><select v-model="form.kind" :disabled="!!kind||!!row"><option value="SMOKING">香烟</option><option value="ALCOHOL">酒品</option></select></label>
 <label class="field"><span>品牌 *</span><input v-model.trim="form.brand" required maxlength="500" placeholder="如：利群、青岛"/></label>
 <label class="field full"><span>种类名称 *</span><input v-model.trim="form.name" required maxlength="500" placeholder="填写容易认出的具体名称"/></label>
 <label class="field full"><span>规格 / 型号</span><input v-model.trim="form.variant" maxlength="500" placeholder="同品牌不同规格分别保存"/></label>
 <label class="field"><span>{{priceLabel}} {{priceRequired?'*':''}}</span><input ref="priceInput" v-model.number="form.packagePrice" @input="confirmPrice=form.packagePrice!==''&&form.packagePrice!=null" type="number" min="0" step="0.000001" :required="priceRequired" placeholder="填写你采用的单价"/></label>
 <label v-if="isSmoke" class="field"><span>每包根数 {{specificationRequired?'*':''}}</span><input v-model.number="form.unitCount" type="number" min="1" max="1000" step="1" :required="specificationRequired"/></label>
 <template v-else><label class="field"><span>每瓶容量 · ml {{specificationRequired?'*':''}}</span><input v-model.number="form.bottleMl" type="number" min="0.01" step="any" :required="specificationRequired"/></label><label class="field"><span>酒精度 · % vol {{!row?'*':''}}</span><input v-model.number="form.abv" type="number" min="0" max="100" step="any" :required="!row" placeholder="如：4.5 或 42；旧资料未知可留空"/></label></template>
 <label class="field"><span>单价来源</span><select v-model="form.priceType"><option value="PERSONAL">个人设定单价</option><option value="ACTUAL">实际单价</option><option value="REFERENCE">参考单价（费用标为估算）</option></select></label>
 </div>
 <label v-if="row" class="check-field field"><input v-model="confirmPrice" type="checkbox"/><span>已核对并采用这个单价计算费用</span></label>
 <label v-if="confirmPrice&&form.packagePrice!==''&&Number(form.packagePrice)===0" class="check-field field"><input v-model="freeConfirmed" type="checkbox"/><span>确认这个种类免费，单价确实为 0 元</span></label>
 <p v-if="row&&!row.priceConfirmed" class="hint">参考价需要你核对后勾选采用。暂时不清楚的价格与规格可以继续留空，费用会保留待补状态。</p>
 <button type="button" class="detail-toggle" @click="details=!details">{{details?'收起':'补充'}}成分与详细资料 <ChevronDown :size="17" :class="{rotated:details}"/></button>
 <div v-if="details" class="fields-grid">
 <template v-if="isSmoke"><label v-for="field in [{key:'tarMg',label:'焦油 · mg/支'},{key:'nicotineMg',label:'烟气烟碱 · mg/支'},{key:'coMg',label:'一氧化碳 · mg/支'}]" :key="field.key" class="field"><span>{{field.label}}</span><input v-model.number="form[field.key]" type="number" min="0" step="any" placeholder="未知留空"/></label><label class="field"><span>成分标示口径</span><input v-model="form.constituentMethod" placeholder="如：包装标示"/></label></template>
 <label v-for="field in [{key:'aliases',label:'别名'},{key:'barcode',label:'条码'},{key:'packagingVersion',label:'包装版本'},{key:'region',label:'销售地区'},{key:'sourceUrl',label:'资料来源链接'}]" :key="field.key" class="field"><span>{{field.label}}</span><input v-model="form[field.key]"/></label>
 <label class="field"><span>资料日期</span><input v-model="form.sourceDate" type="date"/></label>
 <label class="field full"><span>备注</span><textarea v-model="form.notes" rows="3"/></label>
 <label v-for="field in [{key:'verified',label:'规格资料已核对'},{key:'favorite',label:'加入常用'},{key:'locked',label:'锁定联网资料更新'},{key:'active',label:'启用这个种类'}]" :key="field.key" class="field check-field"><span>{{field.label}}</span><input v-model="form[field.key]" type="checkbox"/></label>
 </div>
 <p class="hint">确认单价后，会自动补齐所选种类中有足够资料的历史缺失费用。已有费用保留原单价；需要修改时，再预览重算。</p>
 <p v-if="error" class="error-box" role="alert">{{error}}</p>
 </div><footer class="drawer-footer"><button type="button" class="button secondary" @click="emit('close')">取消</button><button class="button primary" :disabled="saving"><LoaderCircle v-if="saving" class="spin" :size="17"/><Check v-else :size="17"/>{{saving?'正在保存':'保存种类与单价'}}</button></footer></form>
</section></div>
</template>
<style scoped>.product-editor-overlay{z-index:160}.check-field{display:flex;align-items:center;gap:10px}.check-field input{width:18px;height:18px}</style>
