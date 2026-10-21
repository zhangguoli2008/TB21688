export const owner = {
  userId: "00000000-0000-0000-0000-000000000001",
  email: "owner.a@example.test",
  tenantIds: ["10000000-0000-0000-0000-000000000001"],
  currentTenantId: "10000000-0000-0000-0000-000000000001",
  tenantName: "演示商家 A",
  roles: ["OWNER"],
  permissions: ["reports.read", "system.read", "demo.read", "stores.read", "stores.manage", "orders.read", "orders.simulate", "sourcing.read", "sourcing.manage", "procurement.read", "procurement.manage"],
  mode: "MOCK" as const,
};

export const support = {
  ...owner,
  userId: "00000000-0000-0000-0000-000000000002",
  email: "support.a@example.test",
  roles: ["SUPPORT"],
  permissions: ["demo.read", "stores.read", "orders.read", "procurement.read"],
};

export function json(data: unknown, status = 200) {
  return new Response(JSON.stringify({ data, requestId: "test-request-1" }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function apiError(status: number, code = "UNAUTHENTICATED") {
  return new Response(
    JSON.stringify({ code, message: "测试错误", requestId: "test-error-1" }),
    { status, headers: { "Content-Type": "application/json" } },
  );
}
