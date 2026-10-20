import { onScopeDispose, ref, shallowRef } from 'vue';
import { asApiError, type ApiError } from '../api/client';
export function useRemote<T>(loader: () => Promise<T>) {
    const data = shallowRef<T | null>(null);
    const loading = ref(true);
    const error = shallowRef<ApiError | null>(null);
    let sequence = 0;
    async function load() { const current = ++sequence; loading.value = true; data.value = null; error.value = null; try {
        const value = await loader();
        if (current === sequence)
            data.value = value;
    }
    catch (cause) {
        if (current === sequence)
            error.value = asApiError(cause);
    }
    finally {
        if (current === sequence)
            loading.value = false;
    } }
    onScopeDispose(() => { sequence++; });
    return { data, loading, error, load };
}
