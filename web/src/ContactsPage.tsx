import React, { useState } from "react";
import { Clock3, Globe2, Radio, CalendarPlus, X } from "lucide-react";
import type { State, Post } from "./types";
import { Globe } from "./Globe";

const time = (n: number) =>
  new Date(n).toLocaleTimeString("en-GB", { hour12: false });
export function ContactsPage({
  state,
  busy,
  post,
}: {
  state: State;
  busy: boolean;
  post: Post;
}) {
  const contact = state.contacts;
  const [plan, setPlan] = useState(contact.plan),
    [delay, setDelay] = useState(5);
  const [procedure, setProcedure] = useState("OBSERVATION-001:1"),
    [startDelay, setStartDelay] = useState(10),
    [deadline, setDeadline] = useState(180);
  const [notice, setNotice] = useState("");
  const active =
    state.run && ["RUNNING", "PAUSED", "ABORTING"].includes(state.run.status);
  async function configure(e: React.FormEvent) {
    e.preventDefault();
    if (
      await post("/contacts", {
        ...plan,
        enabled: true,
        epochMillis: Date.now() + delay * 1000,
      })
    )
      setNotice("Contact plan saved. The TCP link follows these windows.");
  }
  async function schedule(e: React.FormEvent) {
    e.preventDefault();
    const [procedureId, version] = procedure.split(":"),
      notBefore = Date.now() + startDelay * 1000;
    if (
      await post("/runs", {
        requestId: crypto.randomUUID(),
        procedureId,
        version: +version,
        notBefore,
        expiresAt: notBefore + deadline * 1000,
      })
    )
      setNotice(
        "Procedure scheduled with its saved definition. It will start when contact and guards permit.",
      );
  }
  const until = Math.max(
    0,
    Math.ceil(
      ((contact.open ? contact.nextClose : contact.nextOpen) - state.now) /
        1000,
    ),
  );
  return (
    <>
      <div className="contact-summary">
        <div>
          <span className="tiny">LINK POLICY</span>
          <strong>
            {contact.plan.enabled
              ? contact.open
                ? "In contact"
                : "Blackout"
              : "Continuous contact"}
          </strong>
        </div>
        <div>
          <span className="tiny">NEXT TRANSITION</span>
          <strong>{contact.plan.enabled ? `${until}s` : "Manual plan"}</strong>
        </div>
        <div>
          <span className="tiny">SCHEDULED RUNS</span>
          <strong>{state.scheduled.length}</strong>
        </div>
        <span className="model-label">
          Accelerated simulation · no live orbital data
        </span>
      </div>
      <div className="contact-layout">
        <section className="panel globe-panel">
          <div className="panel-title">
            <h2>
              <Globe2 size={16} /> Contact geometry
            </h2>
            <span>{contact.plan.station} / ASTER-01</span>
          </div>
          <Globe contact={contact} />
          <div className="globe-note">
            The schematic follows the saved contact cycle. Windows are
            configured below; this is not an orbit or radio-coverage prediction.
          </div>
        </section>
        <form className="panel contact-form" onSubmit={configure}>
          <div className="panel-title">
            <h2>
              <Radio size={16} /> Communication cycle
            </h2>
            <span>{contact.plan.enabled ? "ENABLED" : "PREVIEW"}</span>
          </div>
          <div className="form-body">
            <div className="field-grid">
              <label>
                Station label
                <input
                  required
                  maxLength={32}
                  value={plan.station}
                  onChange={(e) =>
                    setPlan({ ...plan, station: e.target.value })
                  }
                />
              </label>
              <label>
                First contact in (s)
                <input
                  required
                  type="number"
                  min={0}
                  max={600}
                  value={delay}
                  onChange={(e) => setDelay(+e.target.value)}
                />
              </label>
            </div>
            <div className="field-grid">
              <label>
                Station latitude
                <input
                  required
                  type="number"
                  min={-85}
                  max={85}
                  step="0.1"
                  value={plan.latitude}
                  onChange={(e) =>
                    setPlan({ ...plan, latitude: +e.target.value })
                  }
                />
              </label>
              <label>
                Station longitude
                <input
                  required
                  type="number"
                  min={-180}
                  max={180}
                  step="0.1"
                  value={plan.longitude}
                  onChange={(e) =>
                    setPlan({ ...plan, longitude: +e.target.value })
                  }
                />
              </label>
            </div>
            <div className="field-grid">
              <label>
                Cycle length (s)
                <input
                  required
                  type="number"
                  min={30}
                  max={600}
                  value={plan.periodSeconds}
                  onChange={(e) =>
                    setPlan({ ...plan, periodSeconds: +e.target.value })
                  }
                />
              </label>
              <label>
                Contact length (s)
                <input
                  required
                  type="number"
                  min={5}
                  max={plan.periodSeconds - 10}
                  value={plan.windowSeconds}
                  onChange={(e) =>
                    setPlan({ ...plan, windowSeconds: +e.target.value })
                  }
                />
              </label>
            </div>
            <p>
              Between windows, the TCP connection closes. The spacecraft keeps
              executing accepted commands and recording their outcomes.
            </p>
            <button
              className="primary"
              type="submit"
              disabled={busy || Boolean(active)}
            >
              Apply contact plan
            </button>
            <button
              type="button"
              disabled={busy || !contact.plan.enabled}
              onClick={async () => {
                if (
                  await post("/contacts", { ...contact.plan, enabled: false })
                )
                  setNotice(
                    "Continuous contact restored. Reconcile uncertain commands before resuming.",
                  );
              }}
            >
              Restore continuous contact
            </button>
            {active && (
              <small>
                Resolve the active run before changing the cycle. Continuous
                contact can be restored for recovery.
              </small>
            )}
          </div>
        </form>
      </div>
      {notice && (
        <p className="publish-notice" role="status">
          ✓ {notice}
        </p>
      )}
      <section className="panel contact-windows">
        <div className="panel-title">
          <h2>
            <Clock3 size={16} /> Upcoming windows
          </h2>
          <span>
            {contact.plan.enabled
              ? "SAVED CONTACT PLAN"
              : "PREVIEW · NOT ENFORCED"}
          </span>
        </div>
        <div className="window-cards">
          {contact.windows.map((w, i) => (
            <div
              className={
                "window-card " +
                (contact.plan.enabled && i === 0 && contact.open
                  ? "current"
                  : "")
              }
              key={w.start}
            >
              <span className="tiny">
                PASS {String(i + 1).padStart(2, "0")}
              </span>
              <strong>
                {time(w.start)} — {time(w.end)}
              </strong>
              <div className="window-bar">
                <i
                  style={{
                    width: `${(contact.plan.windowSeconds / contact.plan.periodSeconds) * 100}%`,
                  }}
                />
              </div>
              <small>
                {contact.plan.windowSeconds}s contact /{" "}
                {contact.plan.periodSeconds - contact.plan.windowSeconds}s
                blackout
              </small>
            </div>
          ))}
        </div>
      </section>
      <div className="schedule-layout">
        <form className="panel" onSubmit={schedule}>
          <div className="panel-title">
            <h2>
              <CalendarPlus size={16} /> Schedule a procedure
            </h2>
            <span>IMMUTABLE VERSION</span>
          </div>
          <div className="form-body">
            <label>
              Scheduled procedure
              <select
                aria-label="Scheduled procedure"
                value={procedure}
                onChange={(e) => setProcedure(e.target.value)}
              >
                {state.procedures.map((p) => (
                  <option
                    key={`${p.id}:${p.version}`}
                    value={`${p.id}:${p.version}`}
                  >
                    {p.name} · v{p.version}
                  </option>
                ))}
              </select>
            </label>
            <div className="field-grid">
              <label>
                Start after (s)
                <input
                  required
                  type="number"
                  min={0}
                  max={3600}
                  value={startDelay}
                  onChange={(e) => setStartDelay(+e.target.value)}
                />
              </label>
              <label>
                Start deadline (+s)
                <input
                  required
                  type="number"
                  min={5}
                  max={86400}
                  value={deadline}
                  onChange={(e) => setDeadline(+e.target.value)}
                />
              </label>
            </div>
            <button
              type="button"
              onClick={() =>
                setStartDelay(
                  Math.max(2, Math.ceil((contact.nextOpen - state.now) / 1000)),
                )
              }
            >
              Use next contact
            </button>
            <p>
              Runs wait for an available instrument, a contact window and fresh
              telemetry. Missing the start deadline fails the run without
              transmitting commands.
            </p>
            <button className="primary" type="submit" disabled={busy}>
              Schedule procedure
            </button>
          </div>
        </form>
        <section className="panel">
          <div className="panel-title">
            <h2>Pending executions</h2>
            <span>{state.scheduled.length} queued</span>
          </div>
          {state.scheduled.length === 0 ? (
            <div className="empty">
              <CalendarPlus />
              <h3>The schedule is clear</h3>
              <p>Choose a saved procedure and its start window.</p>
            </div>
          ) : (
            <div className="scheduled-list">
              {state.scheduled.map((run) => (
                <div className="scheduled-row" key={run.id}>
                  <div>
                    <strong>
                      {run.definition.name}{" "}
                      <span className="mono">v{run.version}</span>
                    </strong>
                    <small>
                      After {time(run.notBefore)} · Start by{" "}
                      {time(run.expiresAt)}
                    </small>
                    <p>{run.reason}</p>
                  </div>
                  <button
                    aria-label={`Cancel scheduled run ${run.id}`}
                    disabled={busy}
                    onClick={() => post(`/runs/${run.id}/abort`)}
                  >
                    <X size={14} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </>
  );
}
