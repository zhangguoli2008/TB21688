import { createRouter, createWebHistory, type RouterHistory } from "vue-router";
import { useSessionStore } from "../stores/session";

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      {
        path: "/login",
        name: "login",
        component: () => import("../pages/LoginPage.vue"),
        meta: { public: true, title: "登录" },
      },
      {
        path: "/",
        component: () => import("../layouts/AppLayout.vue"),
        children: [
          { path: "", redirect: "/dashboard" },
          {
            path: "dashboard",
            name: "dashboard",
            component: () => import("../pages/DashboardPage.vue"),
            meta: { permission: "reports.read", title: "经营总览" },
          },
          {
            path: "403",
            name: "forbidden",
            component: () => import("../pages/ForbiddenPage.vue"),
            meta: { title: "无访问权限" },
          },
          {
            path: ":pathMatch(.*)*",
            name: "not-found",
            component: () => import("../pages/NotFoundPage.vue"),
            meta: { title: "页面不存在" },
          },
        ],
      },
    ],
  });
  router.beforeEach(async (to) => {
    const session = useSessionStore();
    if (!session.checked) {
      try {
        await session.restore();
      } catch {
        return true;
      }
    }
    if (!session.user && !to.meta.public) return { path: "/login" };
    if (session.user && to.path === "/login")
      return { path: session.can("reports.read") ? "/dashboard" : "/403" };
    if (
      typeof to.meta.permission === "string" &&
      !session.can(to.meta.permission)
    )
      return { path: "/403" };
    return true;
  });
  router.afterEach((to) => {
    document.title = `${String(to.meta.title ?? "工作台")} · 智采云 MOCK`;
  });
  return router;
}
