<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { OfficeBuilding, Plus, Refresh } from '@element-plus/icons-vue';
import { api, asApiError, type ApiError } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useRemote } from '../composables/useRemote';
import { parseOrderFilters, platformName, shanghaiTime, type Store, type StoreUpdate } from '../domain/m1';
import StatusBadge from '../components/StatusBadge.vue';
import PagePagination from '../components/PagePagination.vue';
import ErrorPanel from '../components/ErrorPanel.vue';
const session = useSessionStore();
const route = useRoute();
const router = useRouter();
const page = computed(() => parseOrderFilters(route.query));
const { data, loading, error, load } = useRemote(() => api.listStores(page.value.limit, page.value.offset));
const pending = ref(false);
const mutationError = ref<ApiError | null>(null);
const notice = ref('');
const edit = ref<Store | null>(null);
const editName = ref('');
const risk = ref<{
    store: Store;
    body: StoreUpdate;
    title: string;
} | null>(null);
const dialogState = reactive({ open: false });
watch(() => route.fullPath, () => { edit.value = null; void load(); }, { immediate: true });
function editStore(store: Store) { edit.value = store; editName.value = store.displayName; mutationError.value = null; }
async function update(store: Store, body: StoreUpdate) {
    if (pending.value || !session.can('stores.manage'))
        return;
    pending.value = true;
    mutationError.value = null;
    notice.value = '';
    try {
        const updated = await api.updateStore(store.id, body);
        if (data.value)
            data.value = { ...data.value, items: data.value.items.map(item => item.id === updated.id ? updated : item) };
        edit.value = null;
        dialogState.open = false;
        risk.value = null;
        notice.value = 'Mock 店铺已更新。';
    }
    catch (cause) {
        mutationError.value = asApiError(cause);
    }
    finally {
        pending.value = false;
    }
}
function requestUpdate(store: Store, body: StoreUpdate, title?: string) { if (pending.value)
    return; if (title) {
    risk.value = { store, body, title };
    dialogState.open = true;
}
else
    void update(store, body); }
function paginate(offset: number, limit: number) { void router.push({ path: '/stores', query: { limit: String(limit), offset: String(offset) } }); }
</script>
<template>
  <div>
    <div class="page-heading"><div><p class="eyebrow">业务管理 / 店铺中心</p><h1>店铺中心</h1><p class="muted">管理当前租户可见的 Mock 店铺与模拟授权。</p></div><div class="inline-actions"><el-button :icon="Refresh" :loading="loading" :disabled="pending" @click="load">刷新</el-button><RouterLink v-if="session.can('stores.manage')" to="/stores/connect" data-testid="connect-store"><el-button type="primary" :icon="Plus">连接 Mock 店铺</el-button></RouterLink></div></div>
    <p class="scope-hint"><el-icon><OfficeBuilding /></el-icon>店铺范围由服务端会话确定 · {{ session.can('stores.manage') ? '可管理店铺' : '仅可查看授权范围内的店铺' }} · 时间：Asia/Shanghai</p>
    <p v-if="notice" class="success-note" role="status">{{ notice }}</p><ErrorPanel v-if="mutationError" :error="mutationError" title="店铺更新未完成"><el-button v-if="mutationError.status===409" :disabled="pending" @click="edit=null;dialogState.open=false;load()">刷新最新版本后重试</el-button></ErrorPanel>
    <section class="panel table-panel" :aria-busy="loading">
      <div v-if="loading" class="data-state" role="status"><el-skeleton :rows="4" animated /><p>正在加载店铺…</p></div><ErrorPanel v-else-if="error" :error="error" :title="error.status===403?'无权查看店铺':'店铺加载失败'"><el-button @click="load">重试</el-button></ErrorPanel><div v-else-if="data && !data.items.length" class="data-state"><el-icon class="empty-icon"><OfficeBuilding /></el-icon><h2>暂无可见店铺</h2><p>连接 Mock 店铺后，即可演示销售订单同步。</p></div>
      <div v-else-if="data" class="table-scroll"><table class="data-table"><thead><tr><th>店铺 / 渠道</th><th>外部店铺号</th><th>模拟授权</th><th>授权到期（上海）</th><th>店铺状态</th><th v-if="session.can('stores.manage')">操作</th></tr></thead><tbody><tr v-for="store in data.items" :key="store.id"><td><strong>{{ store.displayName }}</strong><small>{{ platformName(store.platform) }}</small></td><td class="mono">{{ store.externalShopId }}</td><td><StatusBadge :status="store.authStatus" /></td><td class="time-cell">{{ shanghaiTime(store.authorizationExpiresAt) }}</td><td><span :class="store.enabled?'enabled-text':'muted'">{{ store.enabled?'已启用':'已禁用' }}</span></td><td v-if="session.can('stores.manage')"><div class="row-actions"><el-button data-testid="edit-store" link type="primary" :disabled="pending" @click="editStore(store)">编辑</el-button><el-button data-testid="toggle-store" link :type="store.enabled?'danger':'primary'" :disabled="pending" @click="requestUpdate(store,{version:store.version,enabled:!store.enabled},store.enabled?'确认禁用店铺':undefined)">{{ store.enabled?'禁用':'启用' }}</el-button></div></td></tr></tbody></table></div>
      <PagePagination v-if="data" :total="data.total" :limit="page.limit" :offset="page.offset" :loading="loading||pending" @change="paginate" />
    </section>
    <section v-if="edit" class="panel editor-panel"><div class="panel-heading"><div><h2>编辑 Mock 店铺</h2><p>{{ edit.displayName }} · 版本 {{ edit.version }}</p></div><el-button text :disabled="pending" @click="edit=null">关闭</el-button></div><form class="inline-form" @submit.prevent="update(edit!,{version:edit!.version,displayName:editName.trim()})"><label>店铺名称<input v-model="editName" maxlength="160" required :disabled="pending" /></label><el-button type="primary" native-type="submit" :loading="pending" :disabled="!editName.trim()||pending">保存名称</el-button></form><div class="authorization-actions"><span>模拟授权状态</span><el-button :disabled="pending" @click="requestUpdate(edit,{version:edit.version,authStatus:'ACTIVE'})">设为有效 / 续期</el-button><el-button :disabled="pending||edit.authStatus==='EXPIRED'" @click="requestUpdate(edit,{version:edit.version,authStatus:'EXPIRED'})">模拟过期</el-button><el-button type="danger" plain :disabled="pending||edit.authStatus==='REVOKED'" @click="requestUpdate(edit,{version:edit.version,authStatus:'REVOKED'},'确认撤销模拟授权')">撤销授权</el-button></div><p class="muted help-text">有效期由服务端生成。真实平台 OAuth / API：待授权。</p></section>
    <el-dialog v-model="dialogState.open" :title="risk?.title" width="460px" :close-on-click-modal="false" :close-on-press-escape="!pending" :show-close="!pending"><p>店铺：{{ risk?.store.displayName }}</p><p class="help-text">此操作将阻止该 Mock 店铺继续生成与同步订单，并记录审计日志。</p><ErrorPanel v-if="mutationError" :error="mutationError" /><template #footer><el-button :disabled="pending" @click="dialogState.open=false;risk=null">取消</el-button><el-button data-testid="confirm-risk" type="danger" :loading="pending" :disabled="pending" @click="risk&&update(risk.store,risk.body)">确认操作</el-button></template></el-dialog>
  </div>
</template>
