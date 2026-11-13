import assert from "node:assert/strict"
import { after, test } from "node:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { relative, resolve } from "node:path"
import plugin, { runWorkshopCli } from "../plugins/sts-workshop.ts"

const root = resolve(import.meta.dirname, "../..")
mkdirSync(resolve(root, "agent-tmp"), { recursive: true })
const temp = mkdtempSync(resolve(root, "agent-tmp/workshop-plugin-test-"))
const executable = resolve(temp, "cli")
writeFileSync(executable, `#!/usr/bin/env node
const fs = require("node:fs")
let text = ""
process.stdin.on("data", chunk => text += chunk)
process.stdin.on("end", () => {
  const input = JSON.parse(text)
  const result = { ...input, files: [{ path: input.outputDir + "/mod.jar", sizeBytes: 3, sha256: "abc" }] }
  const emit = value => console.log(JSON.stringify(value))
  if (input.query === "malformed") { console.log("not json"); return }
  if (input.query === "empty") return
  if (input.query === "failed") { emit({ type: "result", success: false, error: "Steam HTTP 429" }); process.exitCode = 1; return }
  if (input.query === "slow") { setTimeout(() => emit({ type: "result", success: true, result }), 30000); return }
  emit({ type: "progress", progress: { state: "Resolving" } })
  emit({ type: "result", success: true, result })
  if (input.query === "nonzero") process.exitCode = 3
  if (input.query === "duplicate") emit({ type: "result", success: true, result })
})
`)
chmodSync(executable, 0o755)
after(() => rmSync(temp, { recursive: true, force: true }))

async function setup(options = { executable }) {
  const tools = new Map()
  const namespaces = []
  const cleanup = await plugin.setup({
    options, location: { directory: root },
    tool: { async transform(register) { register({ namespace(value) { namespaces.push(value) }, add(tool) { tools.set(tool.name, tool) } }) } },
  })
  return { tools, namespaces, cleanup }
}
const { tools, namespaces, cleanup } = await setup()
after(cleanup)
const context = () => ({ signal: new AbortController().signal, async progress() {} })
const run = (name, input = {}, ctx = context()) => tools.get(name).execute(input, ctx).then(({ content }) => JSON.parse(content))

test("registers searchable OpenCode tools with narrow schemas and project permissions", () => {
  assert.deepEqual([...tools.keys()], ["search_mods", "get_mod_details", "download_mod"])
  assert.equal(namespaces[0].name, "sts_workshop")
  for (const [name, tool] of tools) {
    assert.equal(tool.options.permission, `sts_workshop_${name}`)
    assert.equal(tool.input.additionalProperties, false)
    assert.equal(tool.options.codemode, true)
  }
  assert.deepEqual(tools.get("download_mod").input.required, ["publishedFileId"])
})

test("passes Unicode query, pagination, category, language and sorting without shell interpretation", async () => {
  const input = { query: "中文 & $(touch nope)", page: 2, pageSize: 10, category: "Character", sort: "trend", days: 30, language: "schinese" }
  const updates = []
  const result = await run("search_mods", input, { ...context(), async progress(update) { updates.push(update) } })
  for (const key of Object.keys(input)) assert.equal(result[key], input[key])
  assert.equal(result.action, "search")
  assert.deepEqual(updates, [{ state: "Resolving" }])
  assert.equal(existsSync(resolve(root, "nope")), false)
})

test("preserves uint64 item IDs as strings", async () => {
  const result = await run("get_mod_details", { publishedFileId: "18446744073709551615" })
  assert.equal(result.publishedFileId, "18446744073709551615")
  assert.equal(result.action, "details")
  for (const id of ["0", "-1", "01", "1;true", "18446744073709551616", 123]) {
    await assert.rejects(run("get_mod_details", { publishedFileId: id }), /uint64/)
  }
})

test("downloads get isolated folders and persist complete machine-readable results", async () => {
  const input = { publishedFileId: "1605833019", threads: 4, outDir: relative(root, temp) }
  const [a, b] = await Promise.all([run("download_mod", input), run("download_mod", input)])
  assert.notEqual(a.outputDir, b.outputDir)
  assert.ok(a.outputDir.startsWith(temp))
  assert.equal(a.action, "download")
  assert.equal(a.threads, 4)
  assert.equal(a.fileCount, 1)
  assert.equal(JSON.parse(readFileSync(a.resultJson, "utf8")).files[0].sizeBytes, 3)
})

test("rejects escaped output paths and symlinks before invoking CLI", async () => {
  const link = resolve(temp, "outside")
  symlinkSync(dirnameOutside(root), link)
  for (const outDir of ["../outside", "/outside", relative(root, resolve(link, "new"))]) {
    await assert.rejects(run("download_mod", { publishedFileId: "123", outDir }), /inside the repository/)
  }
})
function dirnameOutside(path) { return resolve(path, "..") }

test("failure, corrupt output, missing result, duplicate result and nonzero exit cannot become success", async () => {
  for (const query of ["failed", "malformed", "empty", "duplicate", "nonzero"]) {
    await assert.rejects(run("search_mods", { query }), query === "failed" ? /Steam HTTP 429/ : /response|result|exited/)
  }
})

test("invalid deadlines and cancellation do not start successful work", async () => {
  for (const timeoutSeconds of [0, -1, 1.5, 7201, "1"]) await assert.rejects(run("search_mods", { timeoutSeconds }), /timeoutSeconds/)
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(run("search_mods", {}, { ...context(), signal: controller.signal }))
})

test("timeout terminates the process instead of returning partial success", async () => {
  await assert.rejects(run("search_mods", { query: "slow", timeoutSeconds: 1 }), /timed out/)
})

test("aborting an active operation stops its process", async () => {
  const controller = new AbortController()
  const operation = run("search_mods", { query: "slow" }, { ...context(), signal: controller.signal })
  const timer = setTimeout(() => controller.abort(), 100)
  try { await assert.rejects(operation, /cancelled/) } finally { clearTimeout(timer) }
})

test("plugin unload cancels active children", async () => {
  const instance = await setup()
  const operation = instance.tools.get("search_mods").execute({ query: "slow" }, context())
  const timer = setTimeout(instance.cleanup, 100)
  try { await assert.rejects(operation, /cancelled/) } finally { clearTimeout(timer) }
})

test("missing CLI gives an actionable build command", async () => {
  const instance = await setup({ executable: resolve(temp, "missing") })
  await assert.rejects(instance.tools.get("search_mods").execute({}, context()), /:tools:workshop-agent:installDist/)
  instance.cleanup()
  await assert.rejects(runWorkshopCli(resolve(temp, "missing"), root, {}, 10, context()), /ENOENT/)
})
