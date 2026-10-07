// Browser telemetry (FR-031, FR-032): the one exporter, the allow-list policy and the wiring of the
// OpenTelemetry web SDK. `main.tsx` calls `initTelemetry` at runtime; tests import the modules.
export { initTelemetry, type Telemetry, type TelemetryOptions } from './setup.ts';
