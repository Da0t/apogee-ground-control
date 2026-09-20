import React, { useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  FilePlus2,
  Layers,
  Play,
  Plus,
  Save,
  Trash2,
} from "lucide-react";
import type { Procedure, Step, Post } from "./types";

const labels: Record<string, string> = {
  POWER_ON: "Power on instrument",
  CAPTURE: "Collect observation",
  POWER_OFF: "Power off instrument",
};
export function ProceduresPage({
  procedures,
  busy,
  post,
  onSelect,
}: {
  procedures: Procedure[];
  busy: boolean;
  post: Post;
  onSelect: (p: Procedure) => void;
}) {
  const ordered = [...procedures].sort(
    (a, b) => a.id.localeCompare(b.id) || b.version - a.version,
  );
  const latest = ordered.filter(
    (p, i) => i === 0 || ordered[i - 1].id !== p.id,
  );
  const [draft, setDraft] = useState<Procedure>(
    ordered.find((p) => p.id === "OBSERVATION-001") || ordered[0],
  );
  const [existing, setExisting] = useState(draft.id);
  const [base, setBase] = useState(draft.version);
  const [notice, setNotice] = useState("");
  const edit = (values: Partial<Procedure>) => {
    setDraft({ ...draft, ...values });
    setNotice("");
  };
  const step = (index: number, values: Partial<Step>) =>
    edit({
      steps: draft.steps.map((s, i) => (i === index ? { ...s, ...values } : s)),
    });
  const move = (index: number, direction: number) => {
    const steps = [...draft.steps];
    [steps[index], steps[index + direction]] = [
      steps[index + direction],
      steps[index],
    ];
    edit({ steps });
  };
  const load = (p: Procedure) => {
    setDraft({ ...p, steps: p.steps.map((s) => ({ ...s })) });
    setExisting(p.id);
    setBase(latest.find((saved) => saved.id === p.id)?.version || p.version);
    setNotice("");
  };
  async function publish(event: React.FormEvent) {
    event.preventDefault();
    if (
      await post("/procedures", {
        ...draft,
        id: draft.id.trim(),
        name: draft.name.trim(),
        baseVersion: base,
      })
    ) {
      setExisting(draft.id.trim());
      setDraft({ ...draft, id: draft.id.trim(), version: base + 1 });
      setBase(base + 1);
      setNotice(
        `Published ${draft.id.trim()} v${base + 1}. Existing runs keep their original version.`,
      );
    }
  }
  return (
    <div className="procedure-library">
      <section className="panel library-list">
        <div className="panel-title">
          <h2>
            <Layers size={16} /> Procedure library
          </h2>
          <span>{latest.length} definitions</span>
        </div>
        <div className="library-toolbar">
          <button
            onClick={() => {
              setExisting("");
              setBase(0);
              setNotice("");
              setDraft({
                ...(procedures.find(
                  (p) => p.id === "OBSERVATION-001" && p.version === 1,
                ) || procedures[0]),
                id: "",
                version: 0,
                name: "",
                description: "",
              });
            }}
          >
            <FilePlus2 size={14} /> New procedure
          </button>
        </div>
        {latest.map((p) => (
          <button
            key={p.id}
            className={`library-row ${existing === p.id ? "selected" : ""}`}
            onClick={() => load(p)}
          >
            <span className="library-icon">
              <Layers size={18} />
            </span>
            <span>
              <strong>{p.name}</strong>
              <small>
                {p.id} · {p.steps.length} steps
              </small>
            </span>
            <span className="status">v{p.version}</span>
          </button>
        ))}
        <div className="library-note">
          <strong>Definitions are immutable.</strong>
          <p>
            Editing publishes a new version. Queued and running procedures
            retain their exact steps, guards, and deadlines.
          </p>
        </div>
      </section>
      <form className="panel procedure-editor" onSubmit={publish}>
        <div className="panel-title">
          <h2>{existing ? "Publish a revision" : "Create a procedure"}</h2>
          <span>VERSION {base + 1}</span>
        </div>
        <div className="editor-fields">
          {existing && (
            <label>
              Load saved version
              <select
                aria-label="Load saved version"
                value={draft.version}
                onChange={(e) =>
                  load(
                    procedures.find(
                      (p) =>
                        p.id === existing &&
                        p.version === Number(e.target.value),
                    )!,
                  )
                }
              >
                {ordered
                  .filter((p) => p.id === existing)
                  .map((p) => (
                    <option key={p.version} value={p.version}>
                      Version {p.version} · {p.name}
                    </option>
                  ))}
              </select>
            </label>
          )}
          <div className="field-grid">
            <label>
              Procedure ID
              <input
                required
                maxLength={40}
                pattern="[A-Z][A-Z0-9_\x2D]{2,39}"
                placeholder="SURVEY-001"
                value={draft.id}
                disabled={Boolean(existing)}
                onChange={(e) => edit({ id: e.target.value.toUpperCase() })}
              />
            </label>
            <label>
              Procedure name
              <input
                required
                maxLength={80}
                value={draft.name}
                onChange={(e) => edit({ name: e.target.value })}
              />
            </label>
          </div>
          <label>
            Description
            <textarea
              maxLength={400}
              rows={2}
              value={draft.description}
              onChange={(e) => edit({ description: e.target.value })}
              placeholder="What will this procedure accomplish?"
            />
          </label>
        </div>
        <div className="editor-step-heading">
          <span className="tiny">EXECUTION SEQUENCE</span>
          <small>Power on → collect or check → power off</small>
        </div>
        <div className="editable-steps">
          {draft.steps.map((s, i) => (
            <fieldset className="editable-step" key={i}>
              <legend>Step {i + 1}</legend>
              <div className="step-edit-top">
                <select
                  aria-label={`Step ${i + 1} command`}
                  value={s.kind}
                  onChange={(e) =>
                    step(i, {
                      kind: e.target.value,
                      durationSeconds: e.target.value === "CAPTURE" ? 6 : 2,
                      timeoutSeconds: 12,
                    })
                  }
                >
                  {Object.entries(labels).map(([k, l]) => (
                    <option key={k} value={k}>
                      {l}
                    </option>
                  ))}
                </select>
                <div className="step-tools">
                  <button
                    type="button"
                    aria-label={`Move step ${i + 1} up`}
                    disabled={i === 0}
                    onClick={() => move(i, -1)}
                  >
                    <ArrowUp size={14} />
                  </button>
                  <button
                    type="button"
                    aria-label={`Move step ${i + 1} down`}
                    disabled={i === draft.steps.length - 1}
                    onClick={() => move(i, 1)}
                  >
                    <ArrowDown size={14} />
                  </button>
                  <button
                    type="button"
                    aria-label={`Remove step ${i + 1}`}
                    disabled={draft.steps.length <= 2}
                    onClick={() =>
                      edit({ steps: draft.steps.filter((_, j) => i !== j) })
                    }
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
              <div className="step-fields">
                <label>
                  Duration (s)
                  <input
                    aria-label={`Step ${i + 1} duration`}
                    type="number"
                    min={2}
                    max={20}
                    required
                    disabled={s.kind !== "CAPTURE"}
                    value={s.durationSeconds}
                    onChange={(e) =>
                      step(i, {
                        durationSeconds: +e.target.value,
                        timeoutSeconds: Math.max(
                          s.timeoutSeconds,
                          +e.target.value + 2,
                        ),
                      })
                    }
                  />
                </label>
                <label>
                  Verify within (s)
                  <input
                    aria-label={`Step ${i + 1} timeout`}
                    type="number"
                    min={s.durationSeconds + 2}
                    max={120}
                    required
                    value={s.timeoutSeconds}
                    onChange={(e) =>
                      step(i, { timeoutSeconds: +e.target.value })
                    }
                  />
                </label>
                <label>
                  Min. battery (%)
                  <input
                    aria-label={`Step ${i + 1} minimum battery`}
                    type="number"
                    min={30}
                    max={100}
                    required
                    disabled={s.kind === "POWER_OFF"}
                    value={s.minBattery}
                    onChange={(e) => step(i, { minBattery: +e.target.value })}
                  />
                </label>
                <label>
                  Max. storage (%)
                  <input
                    aria-label={`Step ${i + 1} maximum storage`}
                    type="number"
                    min={0}
                    max={80}
                    required
                    disabled={s.kind !== "CAPTURE"}
                    value={s.maxStorage}
                    onChange={(e) => step(i, { maxStorage: +e.target.value })}
                  />
                </label>
              </div>
            </fieldset>
          ))}
        </div>
        <div className="editor-actions">
          <button
            type="button"
            disabled={draft.steps.length >= 8}
            onClick={() =>
              edit({
                steps: [
                  ...draft.steps.slice(0, -1),
                  {
                    kind: "CAPTURE",
                    durationSeconds: 6,
                    timeoutSeconds: 12,
                    minBattery: 30,
                    maxStorage: 80,
                  },
                  draft.steps[draft.steps.length - 1],
                ],
              })
            }
          >
            <Plus size={14} /> Add collection step
          </button>
          <span>{draft.steps.length} / 8 steps</span>
        </div>
        {notice && (
          <p role="status" className="publish-notice">
            <Checkmark />
            {notice}
          </p>
        )}
        <div className="editor-footer">
          <p>
            Guards are evaluated before each command. A collection consumes 20%
            storage. Timeouts preserve uncertainty; they do not retry a command.
          </p>
          <div>
            <button
              type="button"
              disabled={busy || !existing}
              onClick={() =>
                onSelect(
                  procedures.find(
                    (p) => p.id === existing && p.version === draft.version,
                  ) || latest.find((p) => p.id === existing)!,
                )
              }
            >
              <Play size={14} /> Open saved version
            </button>
            <button type="submit" className="primary" disabled={busy}>
              <Save size={14} /> Publish version {base + 1}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
function Checkmark() {
  return <span aria-hidden="true">✓</span>;
}
