import { createPinia, setActivePinia } from "pinia";
import { flushPromises, mount } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import ElementPlus from "element-plus";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../App.vue";
import { useSessionStore } from "../stores/session";
import { createAppRouter } from "../router";
import { apiError, json, support } from "./fixtures";

afterEach(() => vi.unstubAllGlobals());

describe("initial session state", () => {
  it("announces pending session verification before a page is available", () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const session = useSessionStore();
    session.loading = true;
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/", component: { template: "<div />" } }],
    });
    const wrapper = mount(App, {
      global: { plugins: [pinia, router, ElementPlus] },
    });
    expect(wrapper.find('[role="status"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("正在确认会话");
    wrapper.unmount();
  });

  it.each([
    {
      name: "an anonymous session",
      response: () => apiError(401),
      destination: "/login",
      pageText: "进入演示工作台",
    },
    {
      name: "a support session without reports permission",
      response: () => json(support),
      destination: "/403",
      pageText: "当前身份无权访问此页面",
    },
  ])(
    "rechecks dashboard access after an offline startup recovers to $name",
    async ({ response, destination, pageText }) => {
      const pinia = createPinia();
      setActivePinia(pinia);
      vi.stubGlobal(
        "fetch",
        vi.fn()
          .mockRejectedValueOnce(new TypeError("offline"))
          .mockImplementation(async () => response()),
      );
      const router = createAppRouter(createMemoryHistory());
      await router.push("/dashboard");
      const wrapper = mount(App, {
        global: { plugins: [pinia, router, ElementPlus] },
      });
      expect(wrapper.text()).toContain("暂时无法确认会话");

      await wrapper.get("button").trigger("click");
      await flushPromises();

      await vi.waitFor(() => {
        expect(router.currentRoute.value.path).toBe(destination);
      });
      expect(wrapper.text()).toContain(pageText);
      expect(wrapper.find(".dashboard-page").exists()).toBe(false);
      wrapper.unmount();
    },
  );
});
