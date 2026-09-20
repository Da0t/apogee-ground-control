import { test, expect } from "@playwright/test";

test.describe.configure({ mode: "serial" });
const procedureId = "CONTACT-" + crypto.randomUUID().slice(0, 8).toUpperCase();
test("procedure versions, pending-run snapshot and addressable pages", async ({
  page,
  request,
}) => {
  await page.goto("/procedures");
  await expect(
    page.getByRole("heading", { name: "A plan with verifiable steps." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "New procedure" }).click();
  await page.getByLabel("Procedure ID", { exact: true }).fill(procedureId);
  await page
    .getByLabel("Procedure name", { exact: true })
    .fill("Contact observation");
  await page
    .getByLabel("Description", { exact: true })
    .fill("Observe across a simulated blackout.");
  await page.getByLabel("Step 2 duration", { exact: true }).fill("10");
  await page
    .getByRole("button", { name: "Publish version 1", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    `Published ${procedureId} v1`,
  );
  await page
    .getByRole("link", { name: "Contact planning", exact: true })
    .click();
  await expect(page).toHaveURL(/\/contacts$/);
  await page
    .getByLabel("Scheduled procedure", { exact: true })
    .selectOption(`${procedureId}:1`);
  await page.getByLabel("Start after (s)", { exact: true }).fill("180");
  await page
    .getByRole("button", { name: "Schedule procedure", exact: true })
    .click();
  await expect(page.locator(".scheduled-row")).toHaveCount(1);
  const queued = (await (await request.get("/api/state")).json()).scheduled[0];
  await page.getByRole("link", { name: "Procedures", exact: true }).click();
  await page.locator(".library-row").filter({ hasText: procedureId }).click();
  await page.getByLabel("Step 2 duration", { exact: true }).fill("3");
  await page
    .getByRole("button", { name: "Publish version 2", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    `Published ${procedureId} v2`,
  );
  const snapshot = await (
    await request.get(`/api/runs/${queued.id}/export`)
  ).json();
  expect(snapshot.run.definition.version).toBe(1);
  expect(snapshot.run.definition.steps[1].durationSeconds).toBe(10);
  const published = (
    await (await request.get("/api/state")).json()
  ).procedures.find(
    (p: { id: string; version: number }) =>
      p.id === procedureId && p.version === 2,
  );
  const otherEditor = await request.post("/api/procedures", {
    data: { ...published, baseVersion: 2 },
  });
  expect(otherEditor.ok()).toBe(true);
  await expect(
    page
      .locator(".library-row")
      .filter({ hasText: procedureId })
      .locator(".status"),
  ).toHaveText("v3");
  await page
    .getByRole("button", { name: "Publish version 3", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("Procedure changed");
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "A plan with verifiable steps." }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Contact planning", exact: true })
    .click();
  await page.goBack();
  await expect(page).toHaveURL(/\/procedures$/);
  await request.post(`/api/runs/${queued.id}/abort`);
});

test("scheduled observation survives real contact loss and reconciles without duplication", async ({
  page,
  request,
}) => {
  test.setTimeout(90000);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await request.post("/api/scenario", { data: { mode: "NONE" } });
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/state")).json()).telemetry.fault,
    )
    .toBe("NONE");
  const observations = (await (await request.get("/api/state")).json())
    .telemetry.observations;
  await page.goto("/contacts");
  await page.getByLabel("First contact in (s)", { exact: true }).fill("5");
  await page.getByLabel("Cycle length (s)", { exact: true }).fill("30");
  await page.getByLabel("Contact length (s)", { exact: true }).fill("8");
  await page
    .getByRole("button", { name: "Apply contact plan", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Contact plan saved");
  await page
    .getByLabel("Scheduled procedure", { exact: true })
    .selectOption(`${procedureId}:1`);
  await page.getByLabel("Start after (s)", { exact: true }).fill("0");
  await page
    .getByRole("button", { name: "Schedule procedure", exact: true })
    .click();
  await expect
    .poll(
      async () => (await (await request.get("/api/state")).json()).run?.status,
      { timeout: 12000 },
    )
    .toBe("RUNNING");
  await expect
    .poll(
      async () =>
        (await (await request.get("/api/state")).json()).commands.some(
          (c: { kind: string; status: string }) =>
            c.kind === "CAPTURE" && c.status === "ACCEPTED",
        ),
      { timeout: 10000 },
    )
    .toBe(true);
  await expect
    .poll(
      async () => (await (await request.get("/api/state")).json()).connected,
      { timeout: 12000 },
    )
    .toBe(false);
  await expect
    .poll(
      async () => (await (await request.get("/api/state")).json()).run.status,
      { timeout: 20000 },
    )
    .toBe("PAUSED");
  const uncertain = await (await request.get("/api/state")).json();
  expect(uncertain.commands.at(-1).status).toBe("UNKNOWN");
  await page.screenshot({ path: "test-results/contacts.png", fullPage: true });
  await expect
    .poll(async () => (await (await request.get("/api/state")).json()).fresh, {
      timeout: 35000,
    })
    .toBe(true);
  await page
    .getByRole("link", { name: "Mission console", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Reconcile spacecraft state" })
    .click();
  await expect(page.locator("tbody .status").last()).toHaveText("COMPLETED");
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(page.locator(".run-state .status")).toHaveText("COMPLETED", {
    timeout: 10000,
  });
  const finished = await (await request.get("/api/state")).json();
  expect(finished.telemetry.observations).toBe(observations + 1);
  expect(finished.commands).toHaveLength(3);
  await request.post("/api/contacts", {
    data: { ...finished.contacts.plan, enabled: false },
  });
  expect(errors).toEqual([]);
});

test("globe controls and new pages fit mobile screens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const route of ["/contacts", "/procedures", "/history", "/console"]) {
    await page.goto(route);
    await expect(page.getByText("Ground service live")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    if (route === "/contacts") {
      await page.getByRole("button", { name: "Rotate globe right" }).click();
      await page
        .getByRole("button", { name: "Center globe on station" })
        .click();
      await page.screenshot({
        path: "test-results/contacts-mobile.png",
        fullPage: true,
      });
    }
  }
});
