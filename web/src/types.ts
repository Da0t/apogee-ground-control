export type Telemetry = {
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
export type Run = {
  id: string;
  status: string;
  step: number;
  reason: string;
  createdAt: number;
  version: number;
  procedure: string;
  definition: Procedure;
  notBefore: number;
  expiresAt: number;
};
export type Cmd = {
  stepIndex: number;
  durationSeconds: number;
  timeoutSeconds: number;
  id: string;
  kind: string;
  status: string;
  reason: string;
  createdAt: number;
};
export type Event = {
  sequence: number;
  at: number;
  level: string;
  message: string;
  runId: string | null;
  commandId: string | null;
};
export type State = {
  connected: boolean;
  fresh: boolean;
  now: number;
  telemetry: Telemetry | null;
  run: Run | null;
  commands: Cmd[];
  events: Event[];
  samples: Telemetry[];
  runs: Run[];
  procedures: Procedure[];
  contacts: ContactStatus;
  scheduled: Run[];
};

export type Step = {
  kind: string;
  durationSeconds: number;
  timeoutSeconds: number;
  minBattery: number;
  maxStorage: number;
};
export type Procedure = {
  id: string;
  version: number;
  name: string;
  description: string;
  createdAt: number;
  steps: Step[];
};
export type ContactPlan = {
  enabled: boolean;
  epochMillis: number;
  periodSeconds: number;
  windowSeconds: number;
  station: string;
  latitude: number;
  longitude: number;
};
export type ContactStatus = {
  plan: ContactPlan;
  open: boolean;
  phase: number;
  nextOpen: number;
  nextClose: number;
  windows: { start: number; end: number }[];
};
export type Post = (path: string, body?: unknown) => Promise<boolean>;
