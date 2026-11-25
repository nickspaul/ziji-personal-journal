<script setup lang="ts">
import { onMounted,onBeforeUnmount,ref,watch } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { LabelLayout } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart, GridComponent, TooltipComponent, LabelLayout, CanvasRenderer])
const props=defineProps<{option:EChartsOption;label?:string}>()
const el=ref<HTMLDivElement>();let chart:ECharts|undefined;let observer:ResizeObserver|undefined
onMounted(()=>{if(!el.value)return;chart=init(el.value);chart.setOption(props.option);observer=new ResizeObserver(()=>chart?.resize());observer.observe(el.value)})
watch(()=>props.option,v=>chart?.setOption(v,true),{deep:true})
onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="el" class="chart" role="img" :aria-label="label||'记录趋势图'"></div></template>
