<script setup lang="ts">
import { watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "./stores/session";
import ErrorPanel from "./components/ErrorPanel.vue";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();
watch(
  () => session.user,
  (user) => {
    if (!user && session.checked && !route.meta.public)
      void router.replace("/login");
  },
);
async function retrySession() {
  try {
    await session.restore();
    // Re-run the permission guard even when the failed startup kept this URL.
    await router.replace({ path: route.fullPath, force: true });
  } catch {
    /* The session error remains visible for retry. */
  }
}
</script>

<template>
  <main v-if="session.error && !session.checked" class="bootstrap-state">
    <h1>暂时无法确认会话</h1>
    <p>请先恢复 API 连接，再继续进入工作台。</p>
    <ErrorPanel :error="session.error">
      <el-button
        type="primary"
        :loading="session.loading"
        @click="retrySession"
      >
        重新连接
      </el-button>
    </ErrorPanel>
  </main>
  <main
    v-else-if="!session.checked && session.loading"
    class="bootstrap-state"
    role="status"
    aria-live="polite"
    aria-busy="true"
  >
    <h1>正在确认会话</h1>
    <p>正在从服务端核实身份与租户，请稍候…</p>
    <el-skeleton :rows="3" animated />
  </main>
  <RouterView v-else />
</template>
