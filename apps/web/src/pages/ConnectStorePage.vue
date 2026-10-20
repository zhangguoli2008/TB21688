<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api, asApiError, type ApiError } from '../api/client';
import { type StoreCreate } from '../domain/m1';
import { useSessionStore } from '../stores/session';
import ErrorPanel from '../components/ErrorPanel.vue';
const router = useRouter();
const session = useSessionStore();
const form = reactive<StoreCreate>({ platform: 'TAOBAO', externalShopId: '', displayName: '' });
const pending = ref(false);
const error = ref<ApiError | null>(null);
let lastBody = '';
let key = '';
async function connect() { if (pending.value || !session.can('stores.manage'))
    return; pending.value = true; error.value = null; const body = { ...form, displayName: form.displayName.trim(), externalShopId: form.externalShopId.trim() }; const signature = JSON.stringify(body); if (signature !== lastBody) {
    key = globalThis.crypto.randomUUID();
    lastBody = signature;
} try {
    await api.createStore(body, key);
    await router.push('/stores');
}
catch (cause) {
    error.value = asApiError(cause);
}
finally {
    pending.value = false;
} }
</script>
<template><div><div class="page-heading"><div><p class="eyebrow">店铺中心 / 连接店铺</p><h1>连接 Mock 店铺</h1><p class="muted">注册合成店铺，演示授权与订单同步。</p></div><RouterLink to="/stores"><el-button>返回店铺中心</el-button></RouterLink></div><section class="panel connect-panel"><el-tag type="warning" effect="plain">MOCK · 真实 OAuth 待授权</el-tag><h2>店铺信息</h2><p class="muted help-text">仅填写合成店铺信息。当前操作不访问淘宝或抖店。</p><form class="stack-form" @submit.prevent="connect"><label>模拟销售渠道<select v-model="form.platform" :disabled="pending"><option value="TAOBAO">淘宝 · Mock</option><option value="DOUYIN">抖店 · Mock</option></select></label><label>外部店铺号<input v-model="form.externalShopId" name="externalShopId" maxlength="128" pattern="[A-Za-z0-9_-]+" placeholder="例如 demo_shop_a" required :disabled="pending" /><small>仅支持字母、数字、下划线和连字符。</small></label><label>店铺名称<input v-model="form.displayName" name="displayName" maxlength="160" placeholder="例如 商家 A · 合成旗舰店" required :disabled="pending" /></label><ErrorPanel v-if="error" :error="error" title="店铺连接未完成" /><el-button type="primary" native-type="submit" :loading="pending" :disabled="pending||!form.displayName.trim()||!form.externalShopId.trim()">注册并启用 Mock 授权</el-button></form></section></div></template>
