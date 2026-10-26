<script setup lang="ts">
import {computed,watch} from 'vue'
const props=withDefaults(defineProps<{modelValue:string;precision:string;label:string;required?:boolean}>(),{required:false})
const emit=defineEmits<{ 'update:modelValue':[value:string] }>()
const day=computed(()=>String(props.modelValue||'').slice(0,10))
const hour=computed(()=>String(props.modelValue||'').slice(11,13)||'20')
watch(()=>[props.modelValue,props.precision],()=>{if(!props.modelValue||props.precision==='EXACT')return;const normalized=`${day.value}T${props.precision==='DATE'?'00':hour.value}:00:00`;if(normalized!==props.modelValue)emit('update:modelValue',normalized)},{immediate:true})
function setDay(value:string){emit('update:modelValue',value?`${value}T${props.precision==='DATE'?'00':hour.value}:00:00`:'')}
function setHour(value:string){if(day.value)emit('update:modelValue',`${day.value}T${value}:00:00`)}
</script>
<template>
<label v-if="precision==='EXACT'" class="field"><span>{{label}}</span><input type="datetime-local" step="60" :value="String(modelValue||'').slice(0,16)" :required="required" @input="emit('update:modelValue',($event.target as HTMLInputElement).value)"/></label>
<div v-else class="field"><span>{{label}}{{precision==='APPROX'?' · 大概几点':' · 日期'}}</span><div class="coarse-time"><input type="date" :aria-label="label+'日期'" :value="day" :required="required" @input="setDay(($event.target as HTMLInputElement).value)"/><select v-if="precision==='APPROX'" :aria-label="label+'大概时段'" :value="hour" @change="setHour(($event.target as HTMLSelectElement).value)"><option v-for="n in 24" :key="n" :value="String(n-1).padStart(2,'0')">约 {{String(n-1).padStart(2,'0')}} 点</option></select></div></div>
</template>
<style scoped>.coarse-time{display:flex;gap:8px}.coarse-time input{min-width:0;flex:1}.coarse-time select{width:105px;flex:none}</style>
