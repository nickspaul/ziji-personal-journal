<script setup lang="ts">
import { onMounted,onBeforeUnmount,ref,watch } from 'vue'
import * as echarts from 'echarts'
const props=defineProps<{option:echarts.EChartsOption;label?:string}>()
const el=ref<HTMLDivElement>();let chart:echarts.ECharts|undefined;let observer:ResizeObserver|undefined
onMounted(()=>{if(!el.value)return;chart=echarts.init(el.value);chart.setOption(props.option);observer=new ResizeObserver(()=>chart?.resize());observer.observe(el.value)})
watch(()=>props.option,v=>chart?.setOption(v,true),{deep:true})
onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="el" class="chart" role="img" :aria-label="label||'记录趋势图'"></div></template>
