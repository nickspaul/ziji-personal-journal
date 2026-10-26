<script setup lang="ts">
import RecordTimeField from './RecordTimeField.vue'
import type { Field } from '../types'
import { state } from '../api'
import {productOption} from '../productPricing'
const props=defineProps<{fields:Field[];model:Record<string,any>;collection?:string;requireProduct?:boolean}>()
function required(field:Field){return field.required||(props.requireProduct&&field.type==='product')}
function changed(field:Field){if(field.type==='product'&&props.collection==='drinks'){const product=state.collections.products?.find(p=>p.id===props.model[field.key]);props.model.bottleMl=product?.bottleMl??'';props.model.abv=product?.abv??''}}
function choices(field:Field){
 if(field.options)return field.options
 if(field.type==='product')return(state.collections.products||[]).filter(r=>(props.collection!=='smoking'||r.kind==='SMOKING')&&(props.collection!=='drinks'||r.kind==='ALCOHOL')&&(r.active!==false||r.id===props.model[field.key])).map(r=>({value:r.id,label:productOption(r)}))
 if(field.type==='session')return(state.collections.sessions||[]).map(r=>({value:r.id,label:`${String(r.startedAt).replace('T',' ').slice(0,16)} · ${r.context||'饮酒记录'}`}))
 if(field.type==='purchase')return(state.collections.purchases||[]).map(r=>({value:r.id,label:`${String(r.occurredAt).slice(0,10)} · ${state.collections.products?.find(p=>p.id===r.productId)?.name||'商品'} · ¥${r.amount}`}))
 return []
}
</script>
<template><div class="fields-grid"><template v-for="f in fields" :key="f.key"><RecordTimeField v-if="f.type==='datetime'&&model.timePrecision" v-model="model[f.key]" :precision="model.timePrecision" :label="f.label" :required="required(f)"/><label v-else class="field" :class="{'full':f.type==='textarea','check-field':f.type==='boolean'}"><span>{{f.label}} <i v-if="required(f)">*</i></span><textarea v-if="f.type==='textarea'" v-model="model[f.key]" :required="required(f)" rows="3"/><select v-else-if="['select','product','session','purchase'].includes(f.type)" v-model="model[f.key]" @change="changed(f)" :required="required(f)"><option value="">请选择{{f.label}}</option><option v-for="o in choices(f)" :key="o.value" :value="o.value">{{o.label}}</option></select><input v-else-if="f.type==='boolean'" type="checkbox" v-model="model[f.key]"/><input v-else-if="f.type==='number'" v-model.number="model[f.key]" type="number" step="any" :required="required(f)"/><input v-else v-model="model[f.key]" :type="f.type==='datetime'?'datetime-local':f.type==='date'?'date':'text'" :step="f.type==='datetime'?1:undefined" :required="required(f)"/></label></template></div></template>
