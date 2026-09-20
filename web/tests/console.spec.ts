import { test, expect } from "@playwright/test";

test.describe.configure({ mode: "serial" });
test("nominal procedure, uncertain completion, reconciliation and replay", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await expect(page.getByText("Ground service live")).toBeVisible();
  await page
    .getByLabel("Procedure version", { exact: true })
    .selectOption("OBSERVATION-001:1");
  await request.post("/api/scenario", { data: { mode: "NONE" } });
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/state")).json()).telemetry.fault,
    )
    .toBe("NONE");
  await page.getByRole("button", { name: "Execute observation" }).click();
  await expect(page.locator(".run-state .status")).toHaveText("RUNNING");
  await expect(page.locator(".run-state .status")).toHaveText("COMPLETED", {
    timeout: 25000,
  });
  await expect(page.locator("tbody tr")).toHaveCount(3);
  await expect(page.locator("tbody .status")).toHaveText([
    "COMPLETED",
    "COMPLETED",
    "COMPLETED",
  ]);

  await page
    .getByLabel("Introduce a controlled failure")
    .selectOption("DROP_COMPLETION");
  await page.getByRole("button", { name: "Apply scenario" }).click();
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/state")).json()).telemetry.fault,
    )
    .toBe("DROP_COMPLETION");
  await page.getByRole("button", { name: "Execute observation" }).click();
  await expect(page.locator(".run-state .status")).toHaveText("RUNNING");
  await expect(page.locator(".run-state .status")).toHaveText("PAUSED", {
    timeout: 25000,
  });
  await expect(page.locator("tbody .status").last()).toHaveText("UNKNOWN");
  const uncertain = await (await request.get("/api/state")).json();
  const before = uncertain.telemetry.observations;
  await page
    .getByRole("button", { name: "Reconcile spacecraft state" })
    .click();
  await expect(page.locator("tbody .status").last()).toHaveText("COMPLETED");
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(page.locator(".run-state .status")).toHaveText("COMPLETED", {
    timeout: 10000,
  });
  expect(
    (await (await request.get("/api/state")).json()).telemetry.observations,
  ).toBe(before);
  await page.screenshot({ path: "test-results/desktop.png", fullPage: true });

  await page.getByRole("link", { name: "Run history" }).click();
  await page.locator(".run-row").first().click();
  await expect(page.getByLabel("Replay position")).toBeVisible();
  const exportPath = await page
    .getByRole("link", { name: "Export JSON" })
    .getAttribute("href");
  const exported = await (await request.get(exportPath!)).json();
  expect(exported.commands).toHaveLength(3);
  expect(exported.dataSource).toBe("SIMULATED");
  await page.getByLabel("Replay position").fill("0");
  await expect(page.locator(".replay-focus h3")).toContainText("started");
  expect(errors).toEqual([]);
});

test("stale measurements and low battery block authorization", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page
    .getByLabel("Procedure version", { exact: true })
    .selectOption("OBSERVATION-001:1");
  await request.post("/api/scenario", { data: { mode: "LOW_BATTERY" } });
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/state")).json()).telemetry.battery,
    )
    .toBeLessThan(30);
  const denied = await request.post("/api/runs", {
    data: { requestId: crypto.randomUUID() },
  });
  expect(denied.status()).toBe(409);
  await request.post("/api/scenario", { data: { mode: "STALE_TELEMETRY" } });
  await expect(
    page.getByText("Stale data cannot authorize actions"),
  ).toBeVisible({ timeout: 10000 });
  await expect(
    page.getByRole("button", { name: "Execute observation" }),
  ).toBeDisabled();
  await expect(page.getByText("STATE UNKNOWN", { exact: true })).toBeVisible();
  await request.post("/api/scenario", { data: { mode: "NONE" } });
  await expect(
    page.getByRole("button", { name: "Execute observation" }),
  ).toBeEnabled({ timeout: 10000 });
});

test("mobile console and cross-origin command rejection", async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page
    .getByLabel("Procedure version", { exact: true })
    .selectOption("OBSERVATION-001:1");
  await expect(
    page.getByRole("button", { name: "Execute observation" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: "test-results/mobile.png", fullPage: true });
  const response = await request.post("/api/disconnect", {
    headers: { Origin: "https://unrelated.example" },
  });
  expect(response.status()).toBe(403);
});
