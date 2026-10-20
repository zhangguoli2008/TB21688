<script setup lang="ts">
import type { ApiError } from "../api/client";
defineProps<{ error: ApiError; title?: string }>();
</script>

<template>
  <div class="error-panel" role="alert">
    <strong>{{ title ?? "请求未完成" }}</strong>
    <p>{{ error.message }}</p>
    <p class="error-meta">
      错误代码：{{ error.code
      }}<span v-if="error.requestId"> · 请求编号：{{ error.requestId }}</span>
    </p>
    <slot />
  </div>
</template>
