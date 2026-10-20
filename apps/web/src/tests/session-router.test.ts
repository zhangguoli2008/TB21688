import { createPinia, setActivePinia } from "pinia";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory } from "vue-router";
import { createAppRouter } from "../router";
import { useSessionStore } from "../stores/session";
import { api } from "../api/client";
import { apiError, json, owner, support } from "./fixtures";

beforeEach(() => {
  setActivePinia(createPinia());
  api.clearCsrf();
});
afterEach(() => vi.unstubAllGlobals());

describe("session and permission boundary", () => {
  it("loads identity from the server and clears it after a later unauthorized response", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(json(owner))
        .mockResolvedValueOnce(apiError(401)),
    );
    const session = useSessionStore();
    await session.restore();
    expect(session.user?.tenantName).toBe("演示商家 A");
    await expect(api.getSystemStatus()).rejects.toMatchObject({ status: 401 });
    expect(session.user).toBeNull();
  });

  it("redirects an anonymous deep link to the public login page", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(apiError(401)));
    const router = createAppRouter(createMemoryHistory());
    await router.push("/dashboard");
    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("denies the support persona access to the dashboard", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(support)));
    const router = createAppRouter(createMemoryHistory());
    await router.push("/dashboard");
    expect(router.currentRoute.value.path).toBe("/403");
  });

  it("admits an owner to the dashboard after checking the server identity", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(owner)));
    const router = createAppRouter(createMemoryHistory());
    await router.push("/dashboard");
    expect(router.currentRoute.value.path).toBe("/dashboard");
  });

  it("keeps a session bootstrap failure distinguishable from being signed out", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    const session = useSessionStore();
    await expect(session.restore()).rejects.toMatchObject({
      code: "NETWORK_ERROR",
    });
    expect(session.checked).toBe(false);
    expect(session.error?.code).toBe("NETWORK_ERROR");
  });

  it("shows the global 404 for an unknown route after authentication", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(owner)));
    const router = createAppRouter(createMemoryHistory());
    await router.push("/unknown-route");
    expect(router.currentRoute.value.name).toBe("not-found");
  });
});

describe('M1 landing and session rotation',()=> {
  it('sends signed-in support visiting login to their scoped orders',async()=> {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(json(support)));
    const router=createAppRouter(createMemoryHistory());await router.push('/login');expect(router.currentRoute.value.path).toBe('/orders');
  });
  it('clears a previous identity as soon as another persona login begins',async()=> {
    const session=useSessionStore();session.user=owner;session.checked=true;
    let resolve!: (value:Response)=>void;
    vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));
    const pending=session.login('OWNER_B');
    expect(session.user).toBeNull();
    resolve(apiError(503));await expect(pending).rejects.toMatchObject({status:503});
  });
});
