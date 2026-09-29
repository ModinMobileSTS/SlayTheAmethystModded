import assert from "node:assert/strict"
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, chmodSync, rmSync, symlinkSync } from "node:fs"
import { resolve, relative } from "node:path"
import { test } from "node:test"

import plugin from "../plugins/sts-harness.ts"

const repoRoot = resolve(import.meta.dirname, "../..")
mkdirSync(resolve(repoRoot, "agent-tmp"), { recursive: true })
const testRoot = mkdtempSync(resolve(repoRoot, "agent-tmp/sts-tools-test-"))
const fakePython = resolve(testRoot, "fake-python")
const artifactBase = relative(repoRoot, testRoot)

writeFileSync(fakePython, `#!/usr/bin/env node
const fs = require("node:fs")
const path = require("node:path")
const args = process.argv.slice(2)
const param = (flag) => args[args.indexOf(flag) + 1]
const action = param("--command")
const log = ${JSON.stringify(resolve(testRoot, "events.log"))}
fs.appendFileSync(log, action + ":start\\n")
const output = path.resolve(process.cwd(), param("--out-dir"), action)
fs.mkdirSync(output, { recursive: true })
setTimeout(() => {
  fs.appendFileSync(log, action + ":end\\n")
  const resultPath = path.join(output, "result.json")
  fs.writeFileSync(resultPath, JSON.stringify({ success: action !== "perf", status: "DONE", message: action, artifacts: { resultJson: resultPath }, argv: args, operations: [{ many: "details" }] }))
  if (action === "play") process.stdout.write("play> Harness result: " + resultPath + "\\n")
  else console.log("Harness result: " + resultPath)
}, action === "status" ? 150 : action === "perf-bench" ? 30_000 : 5)
`)
chmodSync(fakePython, 0o755)

const tools = new Map()
const editor = {
  namespace() {},
  add(tool) { tools.set(tool.name, tool) },
}
await plugin.setup({
  options: { pythonExecutable: fakePython },
  location: { directory: repoRoot, project: { canonical: repoRoot } },
  tool: { async transform(register) { register(editor) } },
})

function run(name, input = {}, signal = new AbortController().signal) {
  return tools.get(name).execute({ outDir: artifactBase, ...input }, { signal, async progress() {} }).then(({ content }) => JSON.parse(content))
}

test("exposes scoped tools and maps actions to Python CLI arguments", async () => {
  const names = [
    "get_game_status", "control_game_lifecycle", "collect_game_diagnostics",
    "run_gameplay_scenario", "manage_game_probe_agent", "execute_game_console_command",
    "inspect_or_reload_game_class", "decompile_installed_game_classes", "manage_enabled_mods",
    "run_game_performance_tests", "test_steam_cloud_sync",
  ]
  assert.deepEqual([...tools.keys()], names)
  for (const name of names) {
    assert.equal(tools.get(name).options.permission, `sts_${name}`)
    assert.ok(tools.get(name).description)
  }
  const config = JSON.parse(readFileSync(resolve(repoRoot, "opencode.json"), "utf8"))
  const approvalActions = config.permissions.filter((rule) => rule.effect === "ask").map((rule) => rule.action)
  assert.deepEqual(approvalActions, [
    "control_game_lifecycle", "run_gameplay_scenario", "manage_game_probe_agent",
    "execute_game_console_command", "inspect_or_reload_game_class", "manage_enabled_mods",
    "run_game_performance_tests", "test_steam_cloud_sync",
  ].map((name) => `sts_${name}`))
  const result = await run("collect_game_diagnostics", { action: "screenshot" })
  assert.equal(result.status, "DONE")
  assert.equal(result.message, "screenshot")
  assert.equal(result.success, true)
  assert.match(result.resultJson, /result\.json$/)
  assert.deepEqual(result.result.argv.slice(0, 5), ["-m", "scripts.tools.main", "sts-harness", "--command", "screenshot"])
  assert.equal(result.result.argv.at(-2), "--out-dir")
  assert.equal(result.result.argv.at(-1), artifactBase)
  assert.equal(result.result.operations, undefined)
})

test("maps status, game lifecycle and deterministic single-room seed", async () => {
  const status = await run("get_game_status")
  assert.equal(status.result.argv[4], "status")
  const started = await run("run_gameplay_scenario", {
    action: "single-room",
    singleRoomSeed: "9223372036854775807",
    singleRoomEntry: "boss",
  })
  assert.equal(started.result.argv[4], "single-room")
  assert.equal(started.result.argv.at(started.result.argv.indexOf("--single-room-seed") + 1), "9223372036854775807")
  const stopped = await run("control_game_lifecycle", { action: "stop" })
  assert.equal(stopped.result.argv[4], "stop")
  assert.equal(started.result.argv.at(started.result.argv.indexOf("--timeout-seconds") + 1), "300")
})

test("maps the remaining domain tools to supported Python commands", async () => {
  const cases = [
    ["manage_game_probe_agent", { action: "agent-list" }, "agent-list"],
    ["execute_game_console_command", { consoleCommand: "gold 10" }, "console"],
    ["inspect_or_reload_game_class", { target: ["com.example.Foo"] }, "hotreload"],
    ["decompile_installed_game_classes", { target: ["com.example.Foo"] }, "decompil"],
    ["test_steam_cloud_sync", {}, "steam-cloud-sync"],
    ["run_game_performance_tests", { action: "startup-cache-profile" }, "startup-cache-profile"],
  ]
  for (const [tool, input, expected] of cases) {
    const result = await run(tool, input)
    assert.equal(result.result.argv[4], expected)
    if (expected === "steam-cloud-sync") {
      assert.equal(result.result.argv.at(result.result.argv.indexOf("--timeout-seconds") + 1), "720")
    }
  }
})

test("parses a result path after an interactive prompt", async () => {
  const result = await run("run_gameplay_scenario", { action: "play" })
  assert.equal(result.success, true)
  assert.equal(result.result.argv[4], "play")
  assert.match(result.resultJson, /result\.json$/)
})

test("rejects unsupported actions and paths outside the repository", async () => {
  await assert.rejects(tools.get("control_game_lifecycle").execute({ action: "arbitrary" }, { signal: new AbortController().signal, async progress() {} }), /Invalid control_game_lifecycle action/)
  await assert.rejects(tools.get("decompile_installed_game_classes").execute({ target: ["A"], outDir: "../elsewhere" }, { signal: new AbortController().signal, async progress() {} }), /stay inside the repository/)
  symlinkSync("/tmp", resolve(testRoot, "outlink"))
  await assert.rejects(run("collect_game_diagnostics", { action: "doctor", outDir: `${artifactBase}/outlink/artifacts` }), /stay inside the repository/)
  await assert.rejects(run("test_steam_cloud_sync", { cloudSyncRelativePath: "../../other" }), /stay below sts/)
})

test("serializes concurrent calls even with auto-selected device serial", async () => {
  const events = resolve(testRoot, "events.log")
  writeFileSync(events, "")
  const first = run("get_game_status")
  const second = run("manage_enabled_mods", { action: "mods" })
  await Promise.all([first, second])
  const lines = readFileSync(events, "utf8").trim().split("\n")
  assert.deepEqual(lines, ["status:start", "status:end", "mods:start", "mods:end"])
})

test("a cancelled queued call does not start or release a later call early", async () => {
  const events = resolve(testRoot, "events.log")
  writeFileSync(events, "")
  const first = run("get_game_status")
  const controller = new AbortController()
  const queued = run("manage_enabled_mods", { action: "mods" }, controller.signal)
  const third = run("control_game_lifecycle", { action: "stop" })
  controller.abort()
  await assert.rejects(queued, /cancelled while waiting/)
  await Promise.all([first, third])
  assert.deepEqual(readFileSync(events, "utf8").trim().split("\n"), ["status:start", "status:end", "stop:start", "stop:end"])
})

test("aborting a long-running scenario terminates it without reporting success", async () => {
  const controller = new AbortController()
  const promise = run("run_game_performance_tests", { action: "perf-bench" }, controller.signal)
  setTimeout(() => controller.abort(), 100)
  const result = await promise
  assert.equal(result.cancelled, true)
  assert.equal(result.success, false)
  assert.equal(result.status, "CANCELLED")
})

test("treats a successful process exit with a failed harness result as failure", async () => {
  const result = await run("run_game_performance_tests", { action: "perf" })
  assert.equal(result.exitCode, 0)
  assert.equal(result.success, false)
})

test.after(() => {
  rmSync(testRoot, { recursive: true, force: true })
})
