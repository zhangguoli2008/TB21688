import { ref } from "vue";
import { defineStore } from "pinia";
import {
  api,
  asApiError,
  type ApiError,
  type Me,
  type Persona,
} from "../api/client";

export const useSessionStore = defineStore("session", () => {
  const user = ref<Me | null>(null);
  const checked = ref(false);
  const loading = ref(false);
  const error = ref<ApiError | null>(null);

  function clearSession() {
    user.value = null;
    checked.value = true;
    error.value = null;
    api.clearCsrf();
  }
  api.setUnauthorizedHandler(clearSession);

  async function restore() {
    loading.value = true;
    error.value = null;
    try {
      user.value = await api.getMe();
      checked.value = true;
    } catch (cause) {
      const failure = asApiError(cause);
      if (failure.status === 401) clearSession();
      else {
        error.value = failure;
        throw failure;
      }
    } finally {
      loading.value = false;
    }
  }
  async function login(persona: Persona) {
    user.value = null;
    api.clearCsrf();
    loading.value = true;
    try {
      user.value = await api.login(persona);
      checked.value = true;
      error.value = null;
    } finally {
      loading.value = false;
    }
  }
  async function logout() {
    loading.value = true;
    try {
      await api.logout();
      clearSession();
    } catch (cause) {
      if (asApiError(cause).status !== 401) throw cause;
    } finally {
      loading.value = false;
    }
  }
  const can = (permission: string) =>
    user.value?.permissions.includes(permission) === true;

  return { user, checked, loading, error, restore, login, logout, can };
});
