import { spawn, type ChildProcess } from "node:child_process"
import { existsSync, readFileSync, realpathSync } from "node:fs"
import { dirname, resolve, relative, isAbsolute } from "node:path"

import { Plugin } from "@opencode/plugin"
import type { Info as PluginTool } from "@opencode/plugin/promise/tool"

type JsonObject = Record<string, unknown>

type ToolContext = {
  signal: AbortSignal
  progress: (update: Record<string, unknown>) => Promise<void>
}

type HarnessInput = JsonObject & {
  deviceSerial?: string
  connectorPort?: number
  timeoutSeconds?: number
  pollIntervalSeconds?: number
  outDir?: string
}

type HarnessExecution = {
  exitCode: number | null
  cancelled: boolean
  timedOut: boolean
  stdout: string
  stderr: string
}

const MAX_CAPTURED_OUTPUT = 12_000
const DEFAULT_TIMEOUT_SECONDS = 120
const DEFAULT_LONG_TIMEOUT_SECONDS = 720
// The Python scenario timeout excludes setup, Gradle log export, and cleanup.
// Keep the process alive long enough for those phases to write result.json.
const PROCESS_TIMEOUT_GRACE_SECONDS = 180
// Connector device selection and Gradle's ADB operations can affect other sessions.
// Serialize all harness calls, including calls where the serial is selected automatically.
let deviceLock: Promise<void> = Promise.resolve()

function findRepoRoot(directory: string): string {
  let current = resolve(directory)
  while (true) {
    if (existsSync(resolve(current, "scripts/tools/main.py"))) return current
    const parent = dirname(current)
    if (current === parent) throw new Error(`No harness checkout found above ${directory}`)
    current = parent
  }
}

const COMMON_PROPERTIES = {
  deviceSerial: { type: "string", description: "ADB device serial. Required when multiple devices are online." },
  connectorPort: { type: "integer", minimum: 1, maximum: 65535, description: "Existing connector daemon port." },
  timeoutSeconds: { type: "integer", minimum: 1, maximum: 43200, description: "Harness operation timeout." },
  pollIntervalSeconds: { type: "integer", minimum: 1, maximum: 60 },
  outDir: { type: "string", description: "Repository-relative artifact base directory." },
}

function objectSchema(properties: JsonObject, required: string[] = []): JsonObject {
  return {
    type: "object",
    properties: { ...COMMON_PROPERTIES, ...properties },
    required,
    additionalProperties: false,
  }
}

function addString(args: string[], flag: string, value: unknown): void {
  if (typeof value === "string" && value.trim()) args.push(flag, value)
}

function addNumber(args: string[], flag: string, value: unknown): void {
  if (typeof value === "number" && Number.isFinite(value)) args.push(flag, String(value))
}

function addBoolean(args: string[], flag: string, value: unknown): void {
  if (value === true) args.push(flag)
}

function addRepeated(args: string[], flag: string, value: unknown): void {
  if (!Array.isArray(value)) return
  for (const item of value) addString(args, flag, item)
}

function appendCommon(args: string[], input: HarnessInput, repoRoot: string, command: string): void {
  addString(args, "--device-serial", input.deviceSerial)
  addNumber(args, "--connector-port", input.connectorPort)
  addNumber(args, "--timeout-seconds", timeoutFor(input, command))
  addNumber(args, "--poll-interval-seconds", input.pollIntervalSeconds)
  if (typeof input.outDir === "string" && input.outDir.trim()) {
    args.push("--out-dir", repoPath(input.outDir, "outDir", repoRoot))
  } else {
    args.push("--out-dir", "agent-tmp/harness-tools")
  }
}

function repoPath(value: string, label: string, repoRoot: string): string {
  const candidate = resolve(repoRoot, value)
  let existing = candidate
  while (!existsSync(existing)) {
    const parent = dirname(existing)
    if (parent === existing) throw new Error(`${label} cannot be resolved: ${value}`)
    existing = parent
  }
  const target = resolve(realpathSync(existing), relative(existing, candidate))
  const rel = relative(realpathSync(repoRoot), target)
  const outside = rel === ".." || rel.startsWith("../") || rel.startsWith("..\\") || isAbsolute(rel)
  if (outside) throw new Error(`${label} must stay inside the repository: ${value}`)
  return relative(repoRoot, candidate) || "."
}

function appendLaunchOptions(args: string[], input: HarnessInput, repoRoot: string): void {
  addString(args, "--launch-mode", input.launchMode)
  addBoolean(args, "--force-jvm-crash", input.forceJvmCrash)
  addBoolean(args, "--force-runtime-crash", input.forceRuntimeCrash)
  addBoolean(args, "--debug-mode", input.debugMode)
  addBoolean(args, "--autoplay", input.autoplay)
  addString(args, "--autoplay-save-mode", input.autoplaySaveMode)
  addString(args, "--autoplay-mode", input.autoplayMode)
  addBoolean(args, "--skip-install", input.skipInstall)
  addBoolean(args, "--no-stop-after-smoke", input.noStopAfterSmoke)
  addBoolean(args, "--disable-card-obtain-effect-ownership-compat", input.disableCardObtainEffectOwnershipCompat)
  addString(args, "--single-room-spec", localPath(input.singleRoomSpec, "singleRoomSpec", repoRoot))
  addString(args, "--single-room-device-spec", input.singleRoomDeviceSpec)
  addString(args, "--single-room-character", input.singleRoomCharacter)
  addString(args, "--single-room-monster", input.singleRoomMonster)
  addString(args, "--single-room-cards", input.singleRoomCards)
  addString(args, "--single-room-entry", input.singleRoomEntry)
  addString(args, "--single-room-dungeon", input.singleRoomDungeon)
  addString(args, "--single-room-seed", input.singleRoomSeed)
}

function localPath(value: unknown, label: string, repoRoot: string): string {
  return typeof value === "string" && value.trim() ? repoPath(value, label, repoRoot) : ""
}

function deviceRelativePath(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) return ""
  const normalized = value.replaceAll("\\", "/")
  if (normalized.startsWith("/") || normalized.split("/").includes("..")) {
    throw new Error("cloudSyncRelativePath must stay below sts/ on the device")
  }
  return value
}

const TOOL_COMMANDS: Record<string, readonly string[]> = {
  get_game_status: ["status"],
  control_game_lifecycle: ["install", "start", "stop", "exit"],
  collect_game_diagnostics: ["doctor", "logs", "screenshot"],
  run_gameplay_scenario: ["smoke", "single-room", "play"],
  manage_game_probe_agent: ["agent-attach", "agent-detach", "agent-list", "agent-status"],
  execute_game_console_command: ["console"],
  inspect_or_reload_game_class: ["hotreload"],
  decompile_installed_game_classes: ["decompil"],
  manage_enabled_mods: ["mods", "set-mods"],
  run_game_performance_tests: ["perf", "startup-cache-profile", "perf-bench"],
  test_steam_cloud_sync: ["steam-cloud-sync"],
}

function resolveHarnessCommand(toolName: string, input: HarnessInput): string {
  const allowed = TOOL_COMMANDS[toolName]
  if (!allowed) throw new Error(`Unknown harness tool: ${toolName}`)
  const action = allowed.length === 1 ? allowed[0] : input.action
  if (typeof action !== "string" || !allowed.includes(action)) throw new Error(`Invalid ${toolName} action: ${String(action)}`)
  return action
}

function commandArgs(toolName: string, input: HarnessInput, repoRoot: string): string[] {
  const command = resolveHarnessCommand(toolName, input)
  const args = ["-m", "scripts.tools.main", "sts-harness", "--command", command]
  appendCommon(args, input, repoRoot, command)
  return args
}

async function withDeviceLock<T>(signal: AbortSignal, work: () => Promise<T>): Promise<T> {
  const previous = deviceLock
  let release!: () => void
  const current = new Promise<void>((resolveRelease) => { release = resolveRelease })
  deviceLock = current
  let acquired = false
  try {
    await new Promise<void>((resolveWait, rejectWait) => {
      const abort = () => rejectWait(new Error("Harness call cancelled while waiting for the device"))
      if (signal.aborted) return abort()
      signal.addEventListener("abort", abort, { once: true })
      void previous.then(resolveWait, rejectWait).finally(() => signal.removeEventListener("abort", abort))
    })
    acquired = true
    if (signal.aborted) throw new Error("Harness call cancelled while waiting for the device")
    return await work()
  } finally {
    if (acquired) release()
    else void previous.then(release, release)
  }
}

function terminateProcess(child: ChildProcess): void {
  if (!child.pid) return
  try {
    if (process.platform === "win32") child.kill("SIGTERM")
    else process.kill(-child.pid, "SIGTERM")
  } catch {
    try { child.kill("SIGTERM") } catch { /* already exited */ }
  }
  setTimeout(() => {
    try {
      if (process.platform === "win32") child.kill("SIGKILL")
      else process.kill(-child.pid!, "SIGKILL")
    } catch { /* already exited */ }
  }, 2_000).unref()
}

async function executeHarness(
  args: string[],
  repoRoot: string,
  context: ToolContext,
  pythonExecutable: string,
  timeoutSeconds: number,
): Promise<HarnessExecution> {
  if (context.signal.aborted) {
    return { exitCode: null, cancelled: true, timedOut: false, stdout: "", stderr: "" }
  }

  await context.progress({ status: "starting", command: args[4] })
  if (context.signal.aborted) {
    return { exitCode: null, cancelled: true, timedOut: false, stdout: "", stderr: "" }
  }
  const child = spawn(pythonExecutable, args, {
    cwd: repoRoot,
    env: { ...process.env, PYTHONUNBUFFERED: "1" },
    stdio: ["ignore", "pipe", "pipe"],
    detached: process.platform !== "win32",
    windowsHide: true,
  })

  let stdout = ""
  let stderr = ""
  const capture = (current: string, chunk: Buffer): string => {
    const next = current + chunk.toString("utf8")
    return next.length > MAX_CAPTURED_OUTPUT ? next.slice(-MAX_CAPTURED_OUTPUT) : next
  }
  child.stdout?.on("data", (chunk: Buffer) => { stdout = capture(stdout, chunk) })
  child.stderr?.on("data", (chunk: Buffer) => { stderr = capture(stderr, chunk) })

  const exit = new Promise<number | null>((resolveExit, rejectExit) => {
    child.once("error", rejectExit)
    child.once("exit", (code) => resolveExit(code))
  })
  let cancelled = false
  let timedOut = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const abort = () => {
    cancelled = true
    terminateProcess(child)
  }
  context.signal.addEventListener("abort", abort, { once: true })
  if (context.signal.aborted) abort()
  timer = setTimeout(() => {
    timedOut = true
    terminateProcess(child)
  }, Math.max(DEFAULT_TIMEOUT_SECONDS, timeoutSeconds + PROCESS_TIMEOUT_GRACE_SECONDS) * 1000)

  try {
    const exitCode = await exit
    return { exitCode, cancelled, timedOut, stdout, stderr }
  } catch (error) {
    return { exitCode: null, cancelled, timedOut, stdout, stderr: `${stderr}\n${String(error)}`.trim() }
  } finally {
    if (timer) clearTimeout(timer)
    context.signal.removeEventListener("abort", abort)
  }
}

function resultPathFromOutput(output: string): string {
  // Interactive harness commands can leave their prompt on the same line.
  const matches = [...output.matchAll(/Harness result: ([^\r\n]+)$/gm)]
  return matches.at(-1)?.[1]?.trim() ?? ""
}

function loadResult(resultPath: string): JsonObject | null {
  if (!resultPath || !existsSync(resultPath)) return null
  try {
    const value: unknown = JSON.parse(readFileSync(resultPath, "utf8"))
    return value && typeof value === "object" ? value as JsonObject : null
  } catch {
    return null
  }
}

function compactResult(result: JsonObject | null): JsonObject | null {
  if (!result) return null
  const copy = { ...result }
  delete copy.operations
  const cloud = copy.steamCloudSync
  if (cloud && typeof cloud === "object") {
    const cloudResult = cloud as JsonObject
    const pollCount = Array.isArray(cloudResult.polls) ? cloudResult.polls.length : 0
    copy.steamCloudSync = { ...cloudResult, polls: `[${pollCount} poll records; see artifact result.json]` }
  }
  return copy
}

function formatExecution(execution: HarnessExecution, resultPath: string): string {
  const result = loadResult(resultPath)
  const success = !execution.cancelled && !execution.timedOut && execution.exitCode === 0 && result?.success === true
  const summary: JsonObject = {
    exitCode: execution.exitCode,
    cancelled: execution.cancelled,
    timedOut: execution.timedOut,
    resultJson: resultPath || null,
    success,
    status: execution.cancelled ? "CANCELLED" : execution.timedOut ? "TIMED_OUT" : result?.status ?? "NO_RESULT",
    message: result?.message ?? "",
    artifacts: result?.artifacts ?? {},
    statusSnapshot: result?.statusSnapshot ?? null,
    result: compactResult(result),
  }
  if (execution.stderr.trim()) summary.stderrTail = execution.stderr.slice(-4000)
  if (execution.stdout.trim() && !resultPath) summary.stdoutTail = execution.stdout.slice(-4000)
  return JSON.stringify(summary, null, 2)
}

function timeoutFor(input: HarnessInput, command: string): number {
  const long = ["install", "perf-bench", "startup-cache-profile", "steam-cloud-sync"].includes(command)
  const scenario = command === "single-room" || command === "logs" || (command === "smoke" && input.autoplay === true)
  return typeof input.timeoutSeconds === "number" && input.timeoutSeconds > 0
    ? input.timeoutSeconds
    : long ? DEFAULT_LONG_TIMEOUT_SECONDS : scenario ? 300 : DEFAULT_TIMEOUT_SECONDS
}

function buildTool(
  command: string,
  description: string,
  input: JsonObject,
  configure: (args: string[], value: HarnessInput, repoRoot: string) => void,
  repoRoot: string,
  pythonExecutable: string,
): PluginTool {
  return {
    name: command,
    description,
    input,
    options: { namespace: "sts", permission: `sts_${command}` },
    execute: async (value: HarnessInput, context: ToolContext) => {
      const args = commandArgs(command, value, repoRoot)
      configure(args, value, repoRoot)
      const actualCommand = resolveHarnessCommand(command, value)
      const execution = await withDeviceLock(context.signal, () => executeHarness(
        args,
        repoRoot,
        context,
        pythonExecutable,
        timeoutFor(value, actualCommand),
      ))
      const resultPath = resultPathFromOutput(`${execution.stdout}\n${execution.stderr}`)
      return { content: formatExecution(execution, resultPath) }
    },
  } as unknown as PluginTool
}

export default Plugin.define({
  id: "sts-harness",
  async setup(ctx) {
    const settings = ctx.options as Record<string, unknown>
    // Do not accidentally execute the canonical checkout's harness in a worktree.
    const repoRoot = findRepoRoot(ctx.location.directory)
    const pythonExecutable = typeof settings.pythonExecutable === "string" && settings.pythonExecutable.trim()
      ? settings.pythonExecutable
      : process.env.STS_HARNESS_PYTHON || (process.platform === "win32" ? "python" : "python3")

    await ctx.tool.transform((editor) => {
      editor.namespace({ name: "sts", description: "SlayTheAmethyst Android harness" })

      editor.add(buildTool(
        "get_game_status",
        "Inspect the running SlayTheAmethyst process, runtime signal, installed package, and Android device. Use for a status snapshot, not logs or screenshots.",
        objectSchema({}),
        () => {},
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "control_game_lifecycle",
        "Install the debug APK (action: install), launch the game (start), force-stop it (stop), or request a graceful exit (exit). Changes device/game state; no arbitrary shell commands.",
        objectSchema({
          action: { type: "string", enum: ["install", "start", "stop", "exit"] },
          launchMode: { type: "string", enum: ["mts_basemod", "mts", "vanilla"] },
          autoplay: { type: "boolean" },
          autoplaySaveMode: { type: "string", enum: ["fresh", "continue"] },
          autoplayMode: { type: "string", enum: ["normal", "single_room"] },
          debugMode: { type: "boolean" },
          forceJvmCrash: { type: "boolean" },
          forceRuntimeCrash: { type: "boolean" },
          skipInstall: { type: "boolean" },
          singleRoomSpec: { type: "string" },
          singleRoomDeviceSpec: { type: "string" },
          singleRoomCharacter: { type: "string" },
          singleRoomMonster: { type: "string" },
          singleRoomCards: { type: "string" },
          singleRoomEntry: { type: "string", enum: ["first", "boss"] },
          singleRoomDungeon: { type: "string" },
          singleRoomSeed: { type: "string", pattern: "^-?[0-9]+$", description: "Signed 64-bit seed passed as a string to avoid JavaScript number precision loss." },
          disableCardObtainEffectOwnershipCompat: { type: "boolean" },
        }, ["action"]),
        (args, value, root) => appendLaunchOptions(args, value, root),
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "collect_game_diagnostics",
        "Diagnose the Android game environment (action: doctor), collect device/game logs (logs), or capture a device screenshot (screenshot). Returns artifact paths; for a quick process snapshot use get_game_status.",
        objectSchema({ action: { type: "string", enum: ["doctor", "logs", "screenshot"] } }, ["action"]),
        () => {},
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "run_gameplay_scenario",
        "Run an Android game smoke check (action: smoke), enter a specified combat room (single-room), or run a game-probe play session (play). May launch/install the game; returns structured artifacts.",
        objectSchema({
          action: { type: "string", enum: ["smoke", "single-room", "play"] },
          launchMode: { type: "string", enum: ["mts_basemod", "mts"] },
          skipInstall: { type: "boolean" },
          noStopAfterSmoke: { type: "boolean" },
          debugMode: { type: "boolean" },
          autoplay: { type: "boolean" },
          autoplayMode: { type: "string", enum: ["normal", "single_room"] },
          autoplaySaveMode: { type: "string", enum: ["fresh", "continue"] },
          singleRoomSpec: { type: "string" },
          singleRoomCharacter: { type: "string" },
          singleRoomMonster: { type: "string" },
          singleRoomCards: { type: "string" },
          singleRoomEntry: { type: "string", enum: ["first", "boss"] },
          singleRoomDungeon: { type: "string" },
          singleRoomSeed: { type: "string", pattern: "^-?[0-9]+$", description: "Signed 64-bit seed passed as a string to avoid JavaScript number precision loss." },
          agentCommand: { type: "string", enum: ["attach", "detach", "list", "status"] },
          agentSpec: { type: "string" },
          agentPort: { type: "integer", minimum: 1, maximum: 65535 },
          agentDuration: { type: "number", minimum: 0, maximum: 3600 },
        }, ["action"]),
        (args, value, root) => {
          appendLaunchOptions(args, value, root)
          addString(args, "--agent-command", value.agentCommand)
          addString(args, "--agent-spec", value.agentSpec)
          addNumber(args, "--agent-port", value.agentPort)
          addNumber(args, "--agent-duration", value.agentDuration)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "manage_game_probe_agent",
        "Attach (action: agent-attach), detach (agent-detach), list (agent-list), or inspect (agent-status) a game-probe agent. Attach captures data for agentDuration.",
        objectSchema({
          action: { type: "string", enum: ["agent-attach", "agent-detach", "agent-list", "agent-status"] },
          agentSpec: { type: "string" },
          agentPort: { type: "integer", minimum: 1, maximum: 65535 },
          agentDuration: { type: "number", minimum: 0, maximum: 3600 },
        }, ["action"]),
        (args, value) => {
          addString(args, "--agent-spec", value.agentSpec)
          addNumber(args, "--agent-port", value.agentPort)
          addNumber(args, "--agent-duration", value.agentDuration)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "execute_game_console_command",
        "Execute one BaseMod DevConsole command in the running game through game-probe. This can change game state; no interactive REPL is available.",
        objectSchema({
          consoleCommand: { type: "string", minLength: 1 },
          agentPort: { type: "integer", minimum: 1, maximum: 65535 },
        }, ["consoleCommand"]),
        (args, value) => {
          addString(args, "--console-command", value.consoleCommand)
          addNumber(args, "--agent-port", value.agentPort)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "inspect_or_reload_game_class",
        "Redefine a running game class from a repository-local .class file (redefineClassFile), or dump and decompile one live class (target). Use decompile_installed_game_classes for classes from the installed game.",
        objectSchema({
          redefineClassFile: { type: "string" },
          target: { type: "array", items: { type: "string" }, minItems: 1, maxItems: 1 },
          agentPort: { type: "integer", minimum: 1, maximum: 65535 },
        }),
        (args, value, root) => {
          addString(args, "--redefine-class", localPath(value.redefineClassFile, "redefineClassFile", root))
          addRepeated(args, "--target", value.target)
          addNumber(args, "--agent-port", value.agentPort)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "decompile_installed_game_classes",
        "Decompile one or more class targets from the installed Android game into repository-local artifacts. Does not redefine a running class.",
        objectSchema({
          target: { type: "array", items: { type: "string" }, minItems: 1 },
        }, ["target"]),
        (args, value) => addRepeated(args, "--target", value.target),
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "manage_enabled_mods",
        "List the current mod selection (action: mods) or change which mods are enabled (set-mods) using explicit mod tokens, a mod list file, or enable/disable-all flags.",
        objectSchema({
          action: { type: "string", enum: ["mods", "set-mods"] },
          mods: { type: "array", items: { type: "string" } },
          modListFile: { type: "string" },
          enableAllMods: { type: "boolean" },
          disableAllMods: { type: "boolean" },
        }, ["action"]),
        (args, value, root) => {
          addRepeated(args, "--mods", value.mods)
          addString(args, "--mod-list-file", localPath(value.modListFile, "modListFile", root))
          addBoolean(args, "--enable-all-mods", value.enableAllMods)
          addBoolean(args, "--disable-all-mods", value.disableAllMods)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "run_game_performance_tests",
        "Collect game performance metrics (action: perf), profile startup caching (startup-cache-profile), or run the full performance benchmark (perf-bench). Returns metrics and artifacts; updating a benchmark baseline requires perfBenchUpdateBaseline.",
        objectSchema({
          action: { type: "string", enum: ["perf", "startup-cache-profile", "perf-bench"] },
          skipInstall: { type: "boolean" },
          cacheHitRuns: { type: "integer", minimum: 1, maximum: 20 },
          noClearStartupCache: { type: "boolean" },
          perfBenchBaseline: { type: "string" },
          perfBenchUpdateBaseline: { type: "boolean" },
          perfBenchEnableProfiler: { type: "boolean" },
          perfBenchProfilerSeconds: { type: "integer", minimum: 1, maximum: 600 },
          perfBenchCharacter: { type: "string" },
          agentPort: { type: "integer", minimum: 1, maximum: 65535 },
          agentSpec: { type: "string" },
          agentDuration: { type: "number", minimum: 0, maximum: 3600 },
        }, ["action"]),
        (args, value, root) => {
          appendLaunchOptions(args, value, root)
          addNumber(args, "--cache-hit-runs", value.cacheHitRuns)
          addBoolean(args, "--no-clear-startup-cache", value.noClearStartupCache)
          addString(args, "--perf-bench-baseline", localPath(value.perfBenchBaseline, "perfBenchBaseline", root))
          addBoolean(args, "--update-baseline", value.perfBenchUpdateBaseline)
          addBoolean(args, "--perf-bench-enable-profiler", value.perfBenchEnableProfiler)
          addNumber(args, "--perf-bench-profiler-seconds", value.perfBenchProfilerSeconds)
          addString(args, "--perf-bench-character", value.perfBenchCharacter)
          addNumber(args, "--agent-port", value.agentPort)
          addString(args, "--agent-spec", value.agentSpec)
          addNumber(args, "--agent-duration", value.agentDuration)
        },
        repoRoot,
        pythonExecutable,
      ))

      editor.add(buildTool(
        "test_steam_cloud_sync",
        "Test Steam Cloud synchronization for the Android game using a test payload, collecting before/poll/final snapshots. May write the test file on the device.",
        objectSchema({
          skipInstall: { type: "boolean" },
          cloudSyncRelativePath: { type: "string" },
          cloudSyncPayload: { type: "string" },
          cloudSyncSourceFile: { type: "string" },
          cloudSyncPullIntervalSeconds: { type: "integer", minimum: 1, maximum: 300 },
        }),
        (args, value, root) => {
          addBoolean(args, "--skip-install", value.skipInstall)
          addString(args, "--cloud-sync-relative-path", deviceRelativePath(value.cloudSyncRelativePath))
          addString(args, "--cloud-sync-payload", value.cloudSyncPayload)
          addString(args, "--cloud-sync-source-file", localPath(value.cloudSyncSourceFile, "cloudSyncSourceFile", root))
          addNumber(args, "--cloud-sync-pull-interval-seconds", value.cloudSyncPullIntervalSeconds)
        },
        repoRoot,
        pythonExecutable,
      ))
    })
  },
})
