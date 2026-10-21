<script setup lang="ts">
import { onScopeDispose, ref, shallowRef, watch } from 'vue';
import { asApiError, type ApiError } from '../api/client';
import ErrorPanel from './ErrorPanel.vue';
const props=defineProps<{label:string;run:(key:string)=>Promise<unknown>;context:string;testid?:string;disabled?:boolean}>();
const emit=defineEmits<{done:[]}>();
const open=ref(false),pending=ref(false);const error=shallowRef<ApiError|null>(null);let key='',epoch=0;let action:((key:string)=>Promise<unknown>)|null=null;
watch(()=>props.context,()=>{epoch++;key='';open.value=false;pending.value=false;error.value=null;action=null;});onScopeDispose(()=>epoch++);
function review(){if(props.disabled||pending.value)return;if(!key)key=globalThis.crypto.randomUUID();action=props.run;error.value=null;open.value=true;}
async function confirm(){if(pending.value||!action)return;const current=epoch;pending.value=true;error.value=null;try{await action(key);if(current===epoch){key='';action=null;open.value=false;emit('done');}}catch(cause){if(current===epoch)error.value=asApiError(cause);}finally{if(current===epoch)pending.value=false;}}
</script>
<template><el-button :data-testid="testid" :disabled="disabled||pending" @click="review">{{ label }}</el-button><el-dialog v-model="open" :title="`确认${label}`" width="520px" :close-on-click-modal="false" :show-close="!pending" :close-on-press-escape="!pending"><p class="help-text">仅提交合成 Mock 操作并记录审计。异步受理不代表回填或退款完成；请刷新查看后台独立权威核验结果。</p><ErrorPanel v-if="error" :error="error" /><template #footer><el-button :disabled="pending" @click="open=false">取消</el-button><el-button data-testid="confirm-m4-action" type="primary" :disabled="pending" :loading="pending" @click="confirm">确认提交</el-button></template></el-dialog></template>
