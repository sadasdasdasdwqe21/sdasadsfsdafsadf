# OBD Forge

An Android OBD-II diagnostics app: live data and custom dashboards, data logging, trouble codes with diagnostic guidance, all standard OBD-II modes, enhanced (UDS) ECU reads, engine analysis, a diagnostic assistant, performance timing, alerts and vehicle history. It works with ELM327-compatible adapters over Bluetooth Classic, Bluetooth LE and Wi-Fi, and includes a built-in simulated car so every feature can be tried without a vehicle.

## Install

1. Open the **Releases** page of this repository and download `OBDForge.apk` from the newest `obd-forge-N` release (`OBDForge-debug-full.apk` is the same app without code shrinking, kept as a fallback).
2. On the phone, allow installing apps from your browser/file manager when Android asks.
3. Open OBD Forge. Android 8.0 or newer is required.

Each build is signed with the same key, so a new build installs over the old one and keeps your data.

## First connection

- **Bluetooth Classic adapter** (most ELM327 dongles): pair it in Android Bluetooth settings first (PIN usually `1234` or `0000`), then *Connect → Bluetooth* and tap it.
- **BLE adapter** (Vgate iCar Pro BLE, OBDLink CX, Veepeak BLE…): *Connect → BLE*, scan, tap it. No pairing needed.
- **Wi-Fi adapter**: join the adapter's Wi-Fi (from the app's list or Android settings), then *Connect → Wi-Fi* with `192.168.0.10 : 35000` (the usual default).
- **No car?** *Connect → Simulator*, pick a scenario (normal, rich, lean, overheating, misfire, O2 fault, boost leak, communication problems, engine off).

Ignition must be on. The app detects the protocol automatically; you can force one under *Advanced*.

## Project layout

| Module | What it contains |
|---|---|
| `obd-core` (pure Kotlin/JVM) | Transport interface, ELM327 session, protocol detection, ISO-TP, frame parsing, all OBD-II services, UDS reads, PID database (100+ PIDs, 120+ channels), DTC database (450+ SAE codes) and knowledge base, live poller, engine analysis, diagnostic assistant, performance timing, alerts, CSV/JSON/XLSX exporters, manufacturer profile framework, expression evaluator, simulator |
| `app` (Android) | Bluetooth Classic / BLE / Wi-Fi transports, connection manager with auto-reconnect, foreground service, Room database, recorder, alert notifications, PDF reports, backups, Jetpack Compose UI |

Architecture maps to the requested modules: Connection Engine (`core.transport`, `app.connection`), OBD-II Protocol Engine (`core.elm`, `core.isotp`, `core.obd`), PID Database (`core.pid`), Manufacturer-Specific (`core.mfr`), Live Data (`core.live`, `app.live`), Data Logging (`app.record`), Graphing (`app.ui.components.Charts`), DTC Database (`core.dtc`), Diagnostic Intelligence (`core.analysis`, `core.assistant`), Vehicle Management (`app.data`), Performance (`core.perf`), Reporting (`core.export`, `app.report`), UI (`app.ui`).

Build locally with Android Studio (open the folder) or `./gradlew assembleDebug`. CI on the `obd-forge` branch runs all tests and publishes the APK as a release.

## Features

### Connection
- Bluetooth Classic (SPP, with insecure and channel-1 fallbacks for cheap clones), BLE (auto-detects the serial GATT service: FFF0, FFE0, 18F0 and generic notify/write services), Wi-Fi (TCP, bound to the adapter's network so mobile data keeps working).
- Device scan with signal strength, paired-device list, OBD-name detection; Wi-Fi network scan and per-app join (Android 10+).
- Adapter identification (ELM version, STN chip detection, clone warning), capability probe, battery voltage at the port.
- Automatic protocol search or forced protocol (CAN 11/29-bit 500/250k, KWP2000 fast/5-baud, ISO 9141-2, J1850 PWM/VPW), ISO baud 10400/9600.
- Single command thread with priorities (user actions jump ahead of polling), timeouts that interrupt the adapter so late answers never corrupt the next command, retry on corrupted replies, link-quality figure.
- Automatic reconnection with back-off when Bluetooth/Wi-Fi drops; recording continues across short drop-outs.
- Raw terminal with traffic log (shareable). ECU-changing commands are blocked unless Expert mode is on.

### Standard OBD-II
- Mode 01 with supported-PID discovery per ECU; unsupported PIDs are never polled; PIDs that stop answering are marked "no answer" instead of showing stale numbers. Multi-PID requests on CAN (up to 6 per request) with automatic fallback.
- Mode 02 freeze frame with side-by-side current values; Mode 03/07/0A stored, pending, permanent codes, grouped by system, recurrence notes, permanent-code explanation.
- Mode 04 clear codes behind a confirmation that lists every consequence.
- Mode 05 oxygen sensor tests (non-CAN), Mode 06 on-board monitor results with limits, pass/marginal/fail and bar graphics.
- Mode 08 EVAP seal test behind confirmation. Mode 09 VIN, calibration IDs, CVNs, ECU names, in-use performance counters.
- Readiness monitors since clear and this drive cycle.

### Enhanced (manufacturer) access
- Read-only UDS scan of engine/transmission and user-entered addresses: ISO 14229 identification DIDs and manufacturer DTCs (service 19) with status bits.
- Manufacturer profiles for the 13 requested makes plus a dedicated Subaru FA20DIT profile. Built-in profiles state honestly what standard OBD-II gives and what needs enhanced access; they include no invented manufacturer PIDs or codes.
- Import profile files (JSON) with your own enhanced values (service 21/22, any identifier, Torque-style formulas such as `((A*256)+B)/4`) and manufacturer code texts. Template export included.

### Live data, dashboards and graphs
- All supported channels list with min/avg/max, pinning, multi-select to graph.
- Nine preset dashboards (engine, fuel, turbo & boost, cooling, intake, emissions, transmission, electrical, general) plus unlimited custom ones; analogue dial, ring, digital, bar and line-graph gauges; resize (wide/tall), reorder, colours, warning thresholds, stats on/off.
- Calculated channels clearly tagged "calc": boost (MAP − baro), total fuel trims, commanded AFR, MAF-based fuel rate, economy, volumetric efficiency, RPM per km/h. GPS speed tagged "GPS". Estimates are never shown as ECU readings.
- Live multi-channel graph with 30 s / 1 min / 5 min windows, shared or per-channel axes, pause, pinch-zoom, scroll, cursor, PNG export.
- Refresh modes (fastest / balanced / saver) with slow-changing values polled less often; metric or imperial units; tablet layout with navigation rail.

### Recording
- Manual or automatic recording (on connect or above a speed; auto-stop after engine off), selectable logging interval, background recording through a foreground service.
- One-second batched writes to SQLite (WAL); sessions interrupted by a crash are recovered and flagged.
- Sessions organised by vehicle and date with titles and notes; replay at 1×/4×/16× with a scrubber; multi-channel plots; X–Y plots (RPM vs boost, throttle vs load, STFT vs LTFT, coolant vs load…); compare two or more sessions aligned at t = 0.
- Statistics per channel: min, max, mean, median, standard deviation, rate of change, peaks, time above threshold.
- Export CSV (Excel-friendly UTF-8), JSON, real `.xlsx`, PDF report, PNG graph; share sheet.

### Analysis and assistant
- Engine analysis on a recording or the last 15 minutes of live data: fuel trims by condition (idle / 2500 rpm / cruise / high load) per bank, idle-vs-cruise and bank-to-bank patterns, airflow (idle g/s per litre, VE at WOT, idle vacuum, IAT rise, throttle response, MAF/MAP correlation), boost (peak, WOT average, response time, overboost, commanded vs actual), misfire (Mode 06 per cylinder, RPM/load correlation, idle stability), cooling (warm-up time, overheating, swings), fuel system (rail pressure, consumption estimate), electrical, anomaly/dropout detection.
- Diagnostic assistant: explains each reading, scores possible causes by evidence for and against, lists measurements needed, orders tests cheapest first with what would confirm or rule out each, and takes your observations (sooty plugs, fuel smell, hissing…) and recorded test results into account. Nothing is called confirmed unless you record a test that confirms it, and it never suggests replacing a part on one reading.
- Plain-language questions answered offline; optional cloud model (your own Anthropic API key) with the same vehicle data and rules.

### Trouble code intelligence
- 450+ SAE generic codes; detailed entries (severity, symptoms, possible causes, procedure, relevant live readings, related codes, repair considerations) for the most common families; structured generic guidance for the rest. Manufacturer codes show as manufacturer-specific unless a profile defines them.

### Performance
- 0–60, 0–100, 0–160, 80–120 km/h, quarter and eighth mile, using GPS speed when available (OBD otherwise), interpolated start/finish, trap speed, estimated wheel/crank power and torque from your vehicle's mass, drag and drivetrain loss, speed trace and power-vs-RPM plot, history.
- Stationary free-rev test (rise/fall time). Safety confirmation before arming; automatic arm/start so the driver never touches the phone.

### Alerts, vehicles, data
- Configurable alerts (above/below/outside/magnitude, sustain time, cooldown, critical) for coolant, voltage, boost, fuel trims, intake temperature, oil temperature, fuel pressure, misfires, any channel, and new trouble codes; notifications and history.
- Vehicle profiles with all requested fields, automatic matching/creation by VIN, active vehicle, repair and maintenance log, diagnostic history.
- Full backup/restore (zip), daily automatic backups (last 5 kept), Android cloud backup of the database.

## What needs compatible hardware or manufacturer access

| Feature | Requirement |
|---|---|
| Any live data / codes | ELM327-compatible adapter and an OBD-II compliant vehicle (petrol from ~1996 US / 2001 EU, diesel later) |
| Fast multi-PID polling | CAN vehicle; many clone adapters are slower |
| Mode 06 results | CAN vehicle whose ECU implements Mode 06 |
| Per-cylinder misfire counts | ECU reporting Mode 06 MIDs A2–AD |
| Mode 08 EVAP test | Very few vehicles support it |
| Enhanced ECU scan (UDS) | CAN vehicle; modules behind a gateway or on other buses (common for ABS, airbag, body) may not be reachable with an ELM327 |
| Manufacturer values (e.g. Subaru CVT temperature, fine knock learning, requested boost) | A profile file with verified definitions for your ECU; not included without a verified source |
| PID 70 commanded/actual boost, PID 5E fuel rate, oil temperature etc. | Only if your ECU reports them as supported |
| GPS timing | Phone GPS with location permission |
| Cloud assistant | Internet and your own API key |

## Tests

- `obd-core`: 49 tests covering hex/JSON, ISO-TP segmentation/reassembly/flow control/sequence errors, frame parsing (CAN 11/29-bit, legacy, headers off, corrupted lines), PID formulas, DTC encode/decode, readiness, Mode 06, expressions, profiles, simulator end-to-end (connect, discovery, multi-PID, codes, freeze frame, clear, permanent codes, Mode 06/09, UDS, ISO 9141, forced wrong protocol, link drop and reconnect, noisy link), live poller and calculated channels, statistics, fuel trim/boost/cooling analysis on simulated rich/lean/overheat/boost-leak data, assistant ranking and test results, query engine, performance timers, CSV/JSON/XLSX export, alerts.
- `app`: unit conversion, terminal safety guard, dashboard codec and presets; Compose UI tests for gauges and the clear-codes confirmation (run on an emulator in CI).
