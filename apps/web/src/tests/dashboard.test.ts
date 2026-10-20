import { createPinia } from "pinia";
import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, describe, expect, it, vi } from "vitest";
import DashboardPage from "../pages/DashboardPage.vue";
import { apiError, json } from "./fixtures";

afterEach(() => vi.unstubAllGlobals());

function dashboard() {
  return mount(DashboardPage, {
    global: { plugins: [createPinia(), ElementPlus] },
  });
}

describe("dashboard service state", () => {
  it("shows pending service checks without pretending they are UP", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise(() => {})),
    );
    const wrapper = dashboard();
    expect(wrapper.find('[aria-busy="true"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain("运行正常");
    wrapper.unmount();
  });

  it("shows a failed request separately and offers a retry that displays real DOWN status", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(apiError(503, "SERVICE_UNAVAILABLE"))
        .mockResolvedValueOnce(
          json({
            mode: "MOCK",
            realChannelsEnabled: false,
            services: [
              { name: "api", status: "UP" },
              { name: "postgresql", status: "DOWN" },
              { name: "redis", status: "UNKNOWN" },
              { name: "rabbitmq", status: "UP" },
            ],
          }),
        ),
    );
    const wrapper = dashboard();
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain("test-error-1");
    expect(wrapper.find('[data-testid="service-list"]').exists()).toBe(false);
    await wrapper.get('[data-testid="retry-status"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[data-testid="service-postgresql"]').text()).toContain(
      "异常",
    );
    expect(wrapper.get('[data-testid="service-redis"]').text()).toContain(
      "未知",
    );
    expect(wrapper.text()).toContain("真实通道关闭");
    wrapper.unmount();
  });
});
