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
          { path: "stores", name: "stores", component: () => import("../pages/StoresPage.vue"), meta: {permission: "stores.read", title: "店铺中心"} },
          { path: "stores/connect", name: "connect-store", component: () => import("../pages/ConnectStorePage.vue"), meta: {permission: "stores.manage", title: "连接 Mock 店铺"} },
          { path: "orders", name: "orders", component: () => import("../pages/OrdersPage.vue"), meta: {permission: "orders.read", title: "订单工作台"} },
          { path: "orders/:id", name: "order-detail", component: () => import("../pages/OrderDetailPage.vue"), meta: {permission: "orders.read", title: "销售订单详情"} },
          { path: "catalog", redirect: "/catalog/mappings" },
          { path: "catalog/mappings", name: "catalog-mappings", component: () => import("../pages/CatalogPage.vue"), meta: {permission: "sourcing.read", title: "SKU 映射管理"} },
          { path: "catalog/sourcing", name: "catalog-sourcing", component: () => import("../pages/CatalogPage.vue"), meta: {permission: "sourcing.manage", title: "候选货源审核"} },
          { path: "purchases", name: "purchases", component: () => import("../pages/PurchasesPage.vue"), meta: {permission: "procurement.read", title: "采购管理"} },
          { path: "purchases/:id", name: "purchase-detail", component: () => import("../pages/PurchaseDetailPage.vue"), meta: {permission: "procurement.read", title: "采购订单详情"} },
          { path: "purchase-intents/:id", name: "intent-detail", component: () => import("../pages/PurchaseDetailPage.vue"), meta: {permission: "procurement.read", title: "采购意图详情"} },
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
      return { path: session.can("reports.read") ? "/dashboard" : session.can("orders.read") ? "/orders" : "/403" };
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
