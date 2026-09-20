import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Activity,
  ArrowDownToLine,
  ArrowUpRight,
  Check,
  ChevronRight,
  CircleHelp,
  Command,
  Cpu,
  Crosshair,
  Database,
  FlaskConical,
  History,
  Layers,
  Pause,
  Play,
  Radio,
  RotateCcw,
  Satellite,
  ShieldCheck,
  Square,
  Terminal,
  Wifi,
  X,
  Zap,
} from "lucide-react";
import "@fontsource/dm-sans/400.css";
import "@fontsource/dm-sans/500.css";
import "@fontsource/dm-sans/600.css";
import "@fontsource/ibm-plex-mono/400.css";
import "./style.css";

type Telemetry = {
  battery: number;
  storage: number;
  instrument: string;
  mode: string;
  observations: number;
  tick: number;
  sequence: number;
  fault: string;
  receivedAt: number;
};
type Run = {
  id: string;
  status: string;
  step: number;
  reason: string;
  createdAt: number;
  version: number;
};
type Cmd = {
  id: string;
  kind: string;
  status: string;
  reason: string;
  createdAt: number;
};
type Event = {
  sequence: number;
  at: number;
  level: string;
  message: string;
  runId: string | null;
  commandId: string | null;
};
type State = {
  connected: boolean;
  fresh: boolean;
  now: number;
  telemetry: Telemetry | null;
  run: Run | null;
  commands: Cmd[];
  events: Event[];
  samples: Telemetry[];
  runs: Run[];
};
const time = (n: number) =>
  new Date(n).toLocaleTimeString("en-GB", { hour12: false });
const labels: Record<string, string> = {
  POWER_ON: "Power on instrument",
  CAPTURE: "Collect observation",
  POWER_OFF: "Power off instrument",
};
const steps = ["POWER_ON", "CAPTURE", "POWER_OFF"];
const scenarios = [
  [
    "NONE",
    "Nominal conditions",
    "Restore battery and storage to the baseline.",
  ],
  [
    "DROP_COMPLETION",
    "Lost completion acknowledgment",
    "The observation completes; its final acknowledgment is suppressed.",
  ],
  [
    "STALE_TELEMETRY",
    "Frozen telemetry",
    "Stop telemetry delivery while the spacecraft keeps running.",
  ],
  [
    "LOW_BATTERY",
    "Low battery",
    "Set battery to 15%. New observations will be blocked.",
  ],
  [
    "REJECT_CAPTURE",
    "Instrument rejection",
    "The spacecraft rejects the collection command.",
  ],
];

function Spark({
  samples,
  field,
}: {
  samples: Telemetry[];
  field: "battery" | "storage";
}) {
  const values = samples.length ? samples.map((s) => s[field]) : [0, 0];
  const points = values
    .map((v, i) => `${(i / (values.length - 1 || 1)) * 200},${44 - v * 0.4}`)
    .join(" ");
  return (
    <svg viewBox="0 0 200 50" className="spark" aria-label={`${field} history`}>
      <path d="M0 44H200" stroke="#2b2b2b" />
      <polyline
        points={points}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
      />
    </svg>
  );
}
function Craft({ instrument }: { instrument: string }) {
  return (
    <svg
      className="craft"
      viewBox="0 0 680 320"
      role="img"
      aria-label="Simulated spacecraft subsystem schematic"
    >
      <defs>
        <pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse">
          <path d="M24 0H0V24" fill="none" stroke="#1f1f1f" strokeWidth=".5" />
        </pattern>
        <pattern
          id="cells"
          width="23"
          height="25"
          patternUnits="userSpaceOnUse"
        >
          <rect
            width="20"
            height="22"
            x="1"
            y="1"
            fill="#222222"
            stroke="#4a4a4a"
            strokeWidth=".5"
          />
        </pattern>
        <linearGradient id="bus" x2="1" y2="1">
          <stop stopColor="#3b3b3b" />
          <stop offset="1" stopColor="#151515" />
        </linearGradient>
      </defs>
      <rect width="680" height="320" fill="url(#grid)" />
      <ellipse
        cx="340"
        cy="167"
        rx="225"
        ry="96"
        fill="none"
        stroke="#343434"
        strokeDasharray="4 9"
      />
      <path d="M90 167H590M340 30V285" stroke="#303030" strokeDasharray="3 7" />
      <g transform="translate(340 165) rotate(-20)">
        <path d="M-65 0H-90M65 0H90" stroke="#747474" strokeWidth="8" />
        <rect
          x="-244"
          y="-47"
          width="154"
          height="94"
          rx="2"
          fill="url(#cells)"
          stroke="#757575"
        />
        <rect
          x="90"
          y="-47"
          width="154"
          height="94"
          rx="2"
          fill="url(#cells)"
          stroke="#757575"
        />
        <path
          d="M-62 -62H40L64 -39V62H-39L-62 39Z"
          fill="url(#bus)"
          stroke="#7d7d7d"
        />
        <path
          d="M-62 -62L-39 -39H64M-39 -39V62M40 -62L64 -39"
          fill="none"
          stroke="#8e8e8e"
        />
        <rect
          x="-21"
          y="-21"
          width="59"
          height="62"
          rx="4"
          fill="#1c1c1c"
          stroke="#7a7a7a"
        />
        <circle
          cx="8"
          cy="10"
          r="20"
          fill="#0b0b0b"
          stroke="#767676"
          strokeWidth="3"
        />
        <circle
          cx="8"
          cy="10"
          r="11"
          fill={instrument === "COLLECTING" ? "var(--accent)" : "#292929"}
          stroke="#a3a3a3"
        />
        <path
          d="M-5 -62V-86M-29 -88Q-4 -112 21 -88Q-4 -69 -29 -88"
          fill="#333333"
          stroke="#959595"
        />
        <circle cx="48" cy="38" r="3" fill="var(--accent)" />
      </g>
      <g fill="#909090" fontFamily="monospace" fontSize="10">
        <path
          d="M148 103L117 72H58M483 112L536 73H619M384 211L472 270H608"
          fill="none"
          stroke="#595959"
        />
        <text x="58" y="63">
          EPS / SOLAR ARRAY
        </text>
        <text x="527" y="63">
          COMMS / LINK
        </text>
        <text x="478" y="287">
          PAYLOAD / OPTICAL
        </text>
      </g>
    </svg>
  );
}
function App() {
  const [wallTime, setWallTime] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setWallTime(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const [state, setState] = useState<State | null>(null),
    [stream, setStream] = useState(false),
    [tab, setTab] = useState("console"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [help, setHelp] = useState(false),
    [scenario, setScenario] = useState("NONE"),
    [selected, setSelected] = useState<string | null>(null),
    [replay, setReplay] = useState<Event[]>([]),
    [cursor, setCursor] = useState(0);
  useEffect(() => {
    fetch("/api/state")
      .then((r) => r.json())
      .then(setState)
      .catch(() => setError("Ground service is unavailable."));
    const es = new EventSource("/api/stream");
    es.addEventListener("state", (e) => {
      setState(JSON.parse((e as MessageEvent).data));
      setStream(true);
    });
    es.onerror = () => setStream(false);
    return () => es.close();
  }, []);
  async function post(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    try {
      const r = await fetch("/api" + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body ? JSON.stringify(body) : "{}",
      });
      const data = await r.json();
      if (!r.ok) throw new Error(data.message || "Request failed");
      const fresh = await fetch("/api/state");
      setState(await fresh.json());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function inspect(id: string) {
    setSelected(id);
    setError("");
    try {
      const r = await fetch(`/api/runs/${id}/export`);
      if (!r.ok) throw new Error("Could not load run history");
      const data = await r.json();
      const e = [...data.events].reverse();
      setReplay(e);
      setCursor(Math.max(0, e.length - 1));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const t = state?.telemetry,
    r = state?.run,
    active = r && ["RUNNING", "PAUSED", "ABORTING"].includes(r.status),
    commands = state?.commands || [],
    age = t ? Math.max(0, (wallTime - t.receivedAt) / 1000) : null;
  const live = Boolean(state?.connected && stream),
    fresh = Boolean(state?.fresh && stream && age !== null && age <= 5);
  const commandAction = (action: string) =>
    r && post(`/runs/${r.id}/${action}`);
  return (
    <div className="shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Apogee home">
          <div className="brand-symbol">
            <Satellite size={23} />
          </div>
          <span>
            apogee<span className="brand-sub">GROUND SYSTEMS</span>
          </span>
        </a>
        <div className="workspace">
          <span className="tiny">WORKSPACE</span>
          <div>
            <span className="craft-id">A</span>
            <div>
              ASTER-01<small>Spacecraft simulator</small>
            </div>
            <ChevronRight size={14} />
          </div>
        </div>
        <span className="tiny nav-label">OPERATIONS</span>
        <nav>
          {[
            ["console", "Mission console", Crosshair],
            ["procedure", "Procedures", Layers],
            ["history", "Run history", History],
          ].map(([id, label, Icon]) => (
            <button
              key={id as string}
              onClick={() => setTab(id as string)}
              className={tab === id ? "nav active" : "nav"}
            >
              <Icon size={17} />
              {label as string}
              {id === "console" && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="environment">
            <FlaskConical size={16} />
            <div>
              Simulation environment<small>Synthetic spacecraft data</small>
            </div>
          </div>
          <button className="nav" onClick={() => setHelp(true)}>
            <CircleHelp size={16} /> How this works <ArrowUpRight size={14} />
          </button>
          <div className="version">
            APOGEE / v0.1.0 <span>LOCAL</span>
          </div>
        </div>
      </aside>
      <div className="main">
        <header>
          <div className="breadcrumb">
            Operations <ChevronRight size={13} />
            <strong>
              {tab === "history"
                ? "Run history"
                : tab === "procedure"
                  ? "Procedures"
                  : "Mission console"}
            </strong>
          </div>
          <div className="header-right">
            <span className={"connection " + (stream ? "ok" : "warn")}>
              <i />
              {stream ? "Ground service live" : "Reconnecting to ground"}
            </span>
            <span className="clock">
              {state ? time(state.now) : "--:--:--"} <small>LOCAL</small>
            </span>
          </div>
        </header>
        <main>
          <div className="page-title">
            <div>
              <div className="eyebrow">
                ASTER-01 <span>/</span> OPERATIONS WORKSPACE
              </div>
              <h1>
                {tab === "history"
                  ? "Every action, accounted for."
                  : tab === "procedure"
                    ? "A plan with verifiable steps."
                    : "Mission console"}
              </h1>
              <p>
                {tab === "history"
                  ? "Inspect recorded decisions and replay the event sequence."
                  : tab === "procedure"
                    ? "OBSERVATION-001 · Version 1 · Exclusive instrument reservation"
                    : "Command with intent. Verify with evidence."}
              </p>
            </div>
            <span className="sim-badge">
              <FlaskConical size={13} /> SIMULATED MISSION
            </span>
          </div>
          {error && (
            <div className="error" role="alert">
              <span>{error}</span>
              <button onClick={() => setError("")} aria-label="Dismiss error">
                <X size={15} />
              </button>
            </div>
          )}
          {tab === "history" ? (
            <div className="history-layout">
              <section className="panel">
                <div className="panel-title">
                  <h2>Execution archive</h2>
                  <span>{state?.runs.length || 0} runs</span>
                </div>
                {!state?.runs.length ? (
                  <div className="empty">
                    <History />
                    <h3>No executions yet</h3>
                    <p>
                      Run the observation procedure to begin an auditable
                      history.
                    </p>
                    <button onClick={() => setTab("console")}>
                      Open mission console <ArrowUpRight size={14} />
                    </button>
                  </div>
                ) : (
                  state.runs.map((run) => (
                    <button
                      className={
                        "run-row " + (selected === run.id ? "selected" : "")
                      }
                      key={run.id}
                      onClick={() => inspect(run.id)}
                    >
                      <span>
                        <strong>Observation procedure</strong>
                        <small>
                          {new Date(run.createdAt).toLocaleString()} ·{" "}
                          {run.id.slice(0, 8)}
                        </small>
                      </span>
                      <span className={"status " + run.status.toLowerCase()}>
                        {run.status}
                      </span>
                      <ChevronRight size={15} />
                    </button>
                  ))
                )}
              </section>
              <section className="panel">
                <div className="panel-title">
                  <h2>Event replay</h2>
                  {selected && (
                    <a href={`/api/runs/${selected}/export`}>
                      <ArrowDownToLine size={14} /> Export JSON
                    </a>
                  )}
                </div>
                {!selected ? (
                  <div className="empty">
                    <Terminal />
                    <p>Select a run to inspect its recorded decisions.</p>
                  </div>
                ) : (
                  <div className="replay">
                    <p>Recorded event sequence · no commands are re-executed</p>
                    <input
                      aria-label="Replay position"
                      type="range"
                      min={0}
                      max={Math.max(0, replay.length - 1)}
                      value={cursor}
                      onChange={(e) => setCursor(+e.target.value)}
                    />
                    <div className="replay-focus">
                      <span>
                        {cursor + 1} / {replay.length}
                      </span>
                      <h3>{replay[cursor]?.message}</h3>
                      <small>{replay[cursor] && time(replay[cursor].at)}</small>
                    </div>
                    {replay
                      .slice(0, cursor + 1)
                      .reverse()
                      .map((e) => (
                        <div className="event" key={e.sequence}>
                          <span className={e.level.toLowerCase()}>●</span>
                          <time>{time(e.at)}</time>
                          <p>{e.message}</p>
                        </div>
                      ))}
                  </div>
                )}
              </section>
            </div>
          ) : (
            <>
              <div className="stats">
                <div className="stat">
                  <span>
                    <Wifi size={15} /> SPACECRAFT LINK
                  </span>
                  <strong className={live ? "accent-text" : "amber"}>
                    {!stream
                      ? "Unavailable"
                      : live
                        ? "Connected"
                        : "Disconnected"}
                  </strong>
                  <small>
                    TCP ·{" "}
                    {fresh ? "Telemetry current" : "Awaiting fresh telemetry"}
                  </small>
                </div>
                <div className="stat">
                  <span>
                    <Zap size={15} /> BATTERY RESERVE
                  </span>
                  <strong>
                    {t ? t.battery.toFixed(1) : "—"}
                    <em>%</em>
                  </strong>
                  <small>Minimum for observation: 30%</small>
                  <Spark samples={state?.samples || []} field="battery" />
                </div>
                <div className="stat">
                  <span>
                    <Database size={15} /> STORAGE USED
                  </span>
                  <strong>
                    {t ? t.storage.toFixed(0) : "—"}
                    <em>%</em>
                  </strong>
                  <small>Observation allocation: 20%</small>
                  <Spark samples={state?.samples || []} field="storage" />
                </div>
                <div className="stat">
                  <span>
                    <Activity size={15} /> TELEMETRY AGE
                  </span>
                  <strong className={fresh ? "" : "amber"}>
                    {age === null ? "—" : age.toFixed(1)}
                    <em>s</em>
                  </strong>
                  <small>
                    {fresh
                      ? "Valid for command preconditions"
                      : "Stale data cannot authorize actions"}
                  </small>
                </div>
              </div>
              <div className="console-grid">
                <div className="left-column">
                  <section className="panel vehicle-panel">
                    <div className="panel-title">
                      <h2>
                        <Satellite size={16} /> Spacecraft overview
                      </h2>
                      <span
                        className={"status " + (fresh ? "nominal" : "paused")}
                      >
                        {fresh ? t?.mode : "STATE UNKNOWN"}
                      </span>
                    </div>
                    <div className="vehicle-meta">
                      <span>
                        ASTER-01 <small>SIMULATED OBSERVATION SPACECRAFT</small>
                      </span>
                      <span className="mono">
                        T+{String(t?.tick || 0).padStart(6, "0")}s
                      </span>
                    </div>
                    <Craft instrument={t?.instrument || "OFF"} />
                    <div className="subsystems">
                      <div>
                        <i className={fresh ? "active-indicator" : "yellow"} />{" "}
                        Flight computer{" "}
                        <strong>{fresh ? "Reporting" : "Unknown"}</strong>
                      </div>
                      <div>
                        <i
                          className={
                            t?.instrument === "COLLECTING"
                              ? "active-indicator"
                              : "gray"
                          }
                        />{" "}
                        Instrument{" "}
                        <strong>{fresh ? t?.instrument : "UNKNOWN"}</strong>
                      </div>
                      <div>
                        <Cpu size={13} /> Observations{" "}
                        <strong>{t?.observations ?? "—"}</strong>
                      </div>
                    </div>
                  </section>
                  <section className="panel">
                    <div className="panel-title">
                      <h2>
                        <Terminal size={15} /> Command ledger
                      </h2>
                      <span>{commands.length} commands</span>
                    </div>
                    {!commands.length ? (
                      <div className="ledger-empty">
                        <Command size={21} />
                        <div>
                          Standing by for a procedure
                          <small>
                            Commands appear here after their intent is
                            persisted.
                          </small>
                        </div>
                      </div>
                    ) : (
                      <div className="table-wrap">
                        <table>
                          <thead>
                            <tr>
                              <th>COMMAND / ID</th>
                              <th>STATUS</th>
                              <th>EVIDENCE</th>
                            </tr>
                          </thead>
                          <tbody>
                            {commands.map((c) => (
                              <tr key={c.id}>
                                <td>
                                  <strong>{c.kind}</strong>
                                  <small>{c.id.slice(0, 8)}</small>
                                </td>
                                <td>
                                  <span
                                    className={
                                      "status " + c.status.toLowerCase()
                                    }
                                  >
                                    {c.status}
                                  </span>
                                </td>
                                <td>{c.reason}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </section>
                </div>
                <div className="right-column">
                  <section className="panel procedure">
                    <div className="panel-title">
                      <h2>
                        <Layers size={16} /> Observation procedure
                      </h2>
                      <span className="mono">v1.0</span>
                    </div>
                    <div className="procedure-desc">
                      Collect an observation, verify storage, and return the
                      instrument to standby.
                    </div>
                    <div className="precondition">
                      <ShieldCheck size={15} />
                      <span>
                        Fresh telemetry · Battery ≥ 30% · Storage ≤ 80%
                      </span>
                    </div>
                    <div className="steps">
                      {steps.map((s, i) => {
                        const c = commands.find((c) => c.kind === s),
                          done = c?.status === "COMPLETED";
                        return (
                          <div
                            key={s}
                            className={
                              "step " +
                              (done
                                ? "done"
                                : active && r.step === i
                                  ? "current"
                                  : "")
                            }
                          >
                            <div className="step-number">
                              {done ? (
                                <Check size={15} />
                              ) : (
                                String(i + 1).padStart(2, "0")
                              )}
                            </div>
                            <div>
                              <strong>{labels[s]}</strong>
                              <small>
                                {done
                                  ? "Completion verified"
                                  : c?.status ||
                                    [
                                      "Enable the observation payload",
                                      "Acquire and persist a 20% storage record",
                                      "Confirm instrument state is OFF",
                                    ][i]}
                              </small>
                            </div>
                            {done && (
                              <Check className="accent-text" size={15} />
                            )}
                          </div>
                        );
                      })}
                    </div>
                    <div className="run-state">
                      <span
                        className={
                          "status " + (r?.status || "idle").toLowerCase()
                        }
                      >
                        {r?.status || "READY"}
                      </span>
                      <p>
                        {r?.reason ||
                          "No procedure in progress. The instrument is available."}
                      </p>
                    </div>
                    <div className="procedure-actions">
                      {!active ? (
                        <button
                          className="primary"
                          disabled={busy || !fresh || !live}
                          onClick={() =>
                            post("/runs", { requestId: crypto.randomUUID() })
                          }
                        >
                          <Play size={15} /> Execute observation
                        </button>
                      ) : (
                        <>
                          <button
                            className="primary"
                            disabled={busy || r.status === "ABORTING"}
                            onClick={() =>
                              commandAction(
                                r.status === "PAUSED" ? "resume" : "pause",
                              )
                            }
                          >
                            {r.status === "PAUSED" ? (
                              <Play size={15} />
                            ) : (
                              <Pause size={15} />
                            )}{" "}
                            {r.status === "PAUSED" ? "Resume" : "Pause"}
                          </button>
                          <button
                            disabled={busy || r.status === "ABORTING"}
                            onClick={() => commandAction("abort")}
                          >
                            <Square size={13} /> Abort
                          </button>
                        </>
                      )}
                      {commands.some((c) =>
                        ["UNKNOWN", "SENT", "ACCEPTED"].includes(c.status),
                      ) && (
                        <button
                          className="reconcile"
                          disabled={busy || !live}
                          onClick={() => commandAction("reconcile")}
                        >
                          <RotateCcw size={14} /> Reconcile spacecraft state
                        </button>
                      )}
                    </div>
                  </section>
                  <section className="panel scenario">
                    <div className="panel-title">
                      <h2>
                        <FlaskConical size={16} /> Scenario controls
                      </h2>
                      <span className="tiny">SIMULATOR</span>
                    </div>
                    <label htmlFor="scenario">
                      Introduce a controlled failure
                    </label>
                    <select
                      id="scenario"
                      value={scenario}
                      onChange={(e) => setScenario(e.target.value)}
                    >
                      {scenarios.map(([id, label]) => (
                        <option key={id} value={id}>
                          {label}
                        </option>
                      ))}
                    </select>
                    <p>{scenarios.find((s) => s[0] === scenario)?.[2]}</p>
                    <div className="scenario-actions">
                      <button
                        disabled={busy || !live}
                        onClick={() => post("/scenario", { mode: scenario })}
                      >
                        Apply scenario <ArrowUpRight size={14} />
                      </button>
                      <button
                        disabled={busy || !live}
                        onClick={() => post("/disconnect")}
                      >
                        Disconnect 15s
                      </button>
                    </div>
                    <small>
                      Reported scenario:{" "}
                      <strong>{t?.fault || "UNKNOWN"}</strong>
                      {!fresh ? " · last known" : ""}
                    </small>
                  </section>
                </div>
              </div>
              <section className="panel event-panel">
                <div className="panel-title">
                  <h2>
                    <Activity size={15} /> Mission events
                  </h2>
                  <span>
                    <i className="live-dot" /> LIVE AUDIT TRAIL
                  </span>
                </div>
                {!state?.events.length ? (
                  <div className="no-events">
                    Procedure decisions and command outcomes will appear here.
                  </div>
                ) : (
                  <div className="event-list">
                    {state.events.slice(0, 12).map((e) => (
                      <div className="event" key={e.sequence}>
                        <span className={e.level.toLowerCase()}>●</span>
                        <time>{time(e.at)}</time>
                        <span
                          className={"event-level " + e.level.toLowerCase()}
                        >
                          {e.level}
                        </span>
                        <p>{e.message}</p>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </>
          )}
          <footer>
            <span>
              <ShieldCheck size={13} /> Requested ≠ accepted ≠ completed
            </span>
            <span>
              Java ground service · PostgreSQL · Independent simulator
            </span>
          </footer>
        </main>
      </div>
      {help && (
        <div className="modal-backdrop" onClick={() => setHelp(false)}>
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="help-title"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              className="close"
              onClick={() => setHelp(false)}
              aria-label="Close explanation"
            >
              <X size={18} />
            </button>
            <div className="eyebrow">UNDER THE CONSOLE</div>
            <h2 id="help-title">
              Real software.
              <br />
              Simulated spacecraft.
            </h2>
            <p>
              Apogee sends commands over a real TCP connection to a separate
              Java process. Battery, storage, and instrument readings are
              synthetic.
            </p>
            <ol>
              <li>
                <strong>Persist intent.</strong> PostgreSQL records each command
                before transmission.
              </li>
              <li>
                <strong>Verify execution.</strong> Acceptance alone does not
                prove completion.
              </li>
              <li>
                <strong>Reconcile uncertainty.</strong> Query the spacecraft
                ledger after a timeout; never blindly repeat an action.
              </li>
            </ol>
            <p>
              Try “Lost completion acknowledgment”, then execute an observation.
              Once it pauses, reconcile the outcome and resume.
            </p>
            <small>
              Educational ground-control simulator. No connection to real
              spacecraft. Replay inspects recorded events; it does not
              re-execute commands.
            </small>
          </section>
        </div>
      )}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
