import { afterEach, describe, expect, it, vi } from "vitest";
import { createApiClient } from "../api/client";
import { apiError, json, owner } from "./fixtures";

afterEach(() => vi.unstubAllGlobals());

describe("API boundary", () => {
  it("sends cookie credentials and the current CSRF token on login, then renews the token before logout", async () => {
    const requests: { path: string; options: RequestInit }[] = [];
    let csrfCount = 0;
    const fetcher = vi.fn(async (path: string, options: RequestInit) => {
      requests.push({ path, options });
      if (path.endsWith("/csrf"))
        return json({
          token: ++csrfCount === 1 ? "before-login" : "after-login",
          headerName: "X-CSRF-TOKEN",
        });
      if (path.endsWith("/login")) return json(owner);
      return json({ loggedOut: true });
    });
    const client = createApiClient(fetcher);

    expect(await client.login("OWNER_A")).toEqual(owner);
    await client.logout();

    expect(requests.map((request) => request.path)).toEqual([
      "/api/v1/auth/csrf",
      "/api/v1/auth/login",
      "/api/v1/auth/csrf",
      "/api/v1/auth/logout",
    ]);
    expect(requests[1]?.options.credentials).toBe("same-origin");
    expect(new Headers(requests[1]?.options.headers).get("X-CSRF-TOKEN")).toBe(
      "before-login",
    );
    expect(JSON.parse(requests[1]?.options.body as string)).toEqual({
      persona: "OWNER_A",
    });
    expect(new Headers(requests[3]?.options.headers).get("X-CSRF-TOKEN")).toBe(
      "after-login",
    );
  });

  it("preserves server error code and requestId for diagnostics", async () => {
    const client = createApiClient(async () => apiError(403, "ACCESS_DENIED"));
    await expect(client.getSystemStatus()).rejects.toMatchObject({
      status: 403,
      code: "ACCESS_DENIED",
      requestId: "test-error-1",
    });
  });

  it("refreshes a rejected CSRF token on the next user attempt without automatically replaying the write", async () => {
    const submittedTokens: (string | null)[] = [];
    let csrfCount = 0;
    const client = createApiClient(async (path, options) => {
      if (path.endsWith("/csrf"))
        return json({
          token: ++csrfCount === 1 ? "expired-token" : "fresh-token",
          headerName: "X-CSRF-TOKEN",
        });
      const token = new Headers(options.headers).get("X-CSRF-TOKEN");
      submittedTokens.push(token);
      return token === "expired-token"
        ? apiError(403, "FORBIDDEN")
        : json(owner);
    });
    await expect(client.login("OWNER_A")).rejects.toMatchObject({
      status: 403,
    });
    expect(submittedTokens).toEqual(["expired-token"]);
    await expect(client.login("OWNER_A")).resolves.toEqual(owner);
    expect(submittedTokens).toEqual(["expired-token", "fresh-token"]);
  });

  it("clears a stale session when any protected request returns 401", async () => {
    let authenticated = true;
    const client = createApiClient(async () => apiError(401));
    client.setUnauthorizedHandler(() => {
      authenticated = false;
    });
    await expect(client.getMe()).rejects.toMatchObject({ status: 401 });
    expect(authenticated).toBe(false);
  });

  it("reports a network error without inventing a successful empty response", async () => {
    const client = createApiClient(async () => {
      throw new TypeError("connection refused");
    });
    await expect(client.getSystemStatus()).rejects.toMatchObject({
      code: "NETWORK_ERROR",
      status: 0,
    });
  });

  it("rejects an invalid tenant membership returned by the server", async () => {
    const client = createApiClient(async () =>
      json({ ...owner, currentTenantId: "another-tenant" }),
    );
    await expect(client.getMe()).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });

  it("does not present a real-channel response as an accepted MOCK status", async () => {
    const client = createApiClient(async () =>
      json({ mode: "MOCK", realChannelsEnabled: true, services: [] }),
    );
    await expect(client.getSystemStatus()).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });

  it("bounds requests and presents timeout separately from an empty state", async () => {
    vi.useFakeTimers();
    const client = createApiClient(
      (_path, options) =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener("abort", () =>
            reject(new DOMException("aborted", "AbortError")),
          );
        }),
    );
    const pending = expect(client.getMe()).rejects.toMatchObject({
      code: "REQUEST_TIMEOUT",
    });
    await vi.advanceTimersByTimeAsync(12000);
    await pending;
    vi.useRealTimers();
  });
});
