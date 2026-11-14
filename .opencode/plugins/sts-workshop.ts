import { spawn } from "node:child_process"
import { existsSync, realpathSync } from "node:fs"
import { mkdir, mkdtemp, writeFile } from "node:fs/promises"
import { dirname, isAbsolute, relative, resolve } from "node:path"
import { Plugin } from "@opencode/plugin"
import type { Info as PluginTool } from "@opencode/plugin/promise/tool"

type ObjectValue = Record<string, unknown>
type Context = { signal: AbortSignal; progress: (value: ObjectValue) => Promise<void> }
const BUILD_COMMAND = "flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :tools:workshop-agent:installDist --console=plain --max-workers=2"

function repoRoot(directory: string): string {
  let current = resolve(directory)
  while (!existsSync(resolve(current, "tools/workshop-agent/build.gradle.kts"))) {
    const parent = dirname(current)
    if (parent === current) throw new Error("Cannot locate tools/workshop-agent in this checkout")
    current = parent
  }
  return current
}

function inside(root: string, path: string): boolean {
  const rel = relative(root, path)
  return rel !== ".." && !rel.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) && !isAbsolute(rel)
}

function outputBase(root: string, value: unknown): string {
  if (value !== undefined && (typeof value !== "string" || !value.trim())) throw new Error("outDir must be a nonempty repository-relative path")
  const path = resolve(root, value as string || "agent-tmp/workshop-mods")
  if (!inside(root, path)) throw new Error("outDir must stay inside the repository")
  let existing = path
  while (!existsSync(existing)) existing = dirname(existing)
  if (!inside(realpathSync(root), resolve(realpathSync(existing), relative(existing, path)))) {
    throw new Error("outDir symlink must stay inside the repository")
  }
  return path
}

/** The CLI owns Steam; this adapter only handles schemas, local paths and process lifetime. */
export async function runWorkshopCli(
  executable: string, root: string, request: ObjectValue, timeoutSeconds: number, context: Context,
): Promise<ObjectValue> {
  context.signal.throwIfAborted()
  return new Promise((resolveResult, reject) => {
    const child = spawn(executable, [], { cwd: root, detached: process.platform !== "win32", stdio: ["pipe", "pipe", "pipe"] })
    let buffer = "", stderr = "", protocolError = ""
    let result: ObjectValue | undefined
    let cancelled = false, timedOut = false
    let hardKill: ReturnType<typeof setTimeout> | undefined
    const kill = (signal: NodeJS.Signals) => {
      if (!child.pid) return
      try {
        if (process.platform === "win32") child.kill(signal)
        else process.kill(-child.pid, signal)
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ESRCH") child.kill(signal)
      }
    }
    const stop = () => {
      kill("SIGTERM")
      hardKill ??= setTimeout(() => kill("SIGKILL"), 1500)
    }
    const abort = () => { cancelled = true; stop() }
    const timeout = setTimeout(() => { timedOut = true; stop() }, timeoutSeconds * 1000)
    const cleanup = () => {
      clearTimeout(timeout)
      if (hardKill) clearTimeout(hardKill)
      context.signal.removeEventListener("abort", abort)
    }
    context.signal.addEventListener("abort", abort, { once: true })
    if (context.signal.aborted) abort()
    child.stdout.setEncoding("utf8")
    child.stderr.setEncoding("utf8")
    child.stdout.on("data", (data: string) => {
      buffer += data
      if (buffer.length > 2_000_000) {
        protocolError = "Workshop CLI output exceeded the response limit"
        buffer = ""
        stop()
        return
      }
      let newline: number
      while ((newline = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, newline).trim()
        buffer = buffer.slice(newline + 1)
        if (!line) continue
        try {
          const message = JSON.parse(line) as ObjectValue
          if (message.type === "result") {
            if (result) throw new Error("Duplicate result")
            result = message
          } else if (message.type === "progress") {
            void context.progress(message.progress as ObjectValue).catch(() => {})
          } else throw new Error("Unknown message type")
        } catch {
          protocolError = "Invalid Workshop CLI response"
          stop()
        }
      }
    })
    child.stderr.on("data", (data: string) => { stderr = (stderr + data).slice(-6000) })
    // A CLI that exits before reading stdin reports through close, not an unhandled EPIPE.
    child.stdin.on("error", () => {})
    child.once("error", (error) => { cleanup(); reject(error) })
    child.once("close", (code) => {
      cleanup()
      if (cancelled || context.signal.aborted) return reject(new Error("Workshop operation cancelled"))
      if (timedOut) return reject(new Error(`Workshop operation timed out after ${timeoutSeconds}s`))
      if (protocolError || buffer.trim()) return reject(new Error(protocolError || "Incomplete Workshop CLI response"))
      if (code !== 0 || result?.success !== true) {
        return reject(new Error(String(result?.error || stderr || `Workshop CLI exited ${code} without a successful result`)))
      }
      if (!result.result || typeof result.result !== "object" || Array.isArray(result.result)) return reject(new Error("Missing Workshop CLI result"))
      resolveResult(result.result as ObjectValue)
    })
    child.stdin.end(JSON.stringify(request))
  })
}

const common = {
  appId: { type: "integer", minimum: 1, maximum: 4294967295, description: "Steam app ID; defaults to Slay the Spire (646570)." },
  language: { type: "string", pattern: "^[a-z_]{2,32}$", description: "Steam language, defaults to schinese; e.g. english, tchinese." },
  timeoutSeconds: { type: "integer", minimum: 1, maximum: 7200, description: "Operation deadline; defaults to 90 for reads, 900 for downloads." },
}
const itemId = { type: "string", pattern: "^[1-9][0-9]{0,19}$", description: "Exact publishedFileId from search results or a Steam Workshop URL, as a string (not the URL)." }

export default Plugin.define({
  id: "sts-workshop",
  async setup(ctx) {
    const root = repoRoot(ctx.location.directory)
    const executable = typeof ctx.options.executable === "string" ? ctx.options.executable
      : resolve(root, "tools/workshop-agent/build/install/workshop-agent/bin/workshop-agent")
    const running = new Set<AbortController>()
    await ctx.tool.transform((editor) => {
      editor.namespace({ name: "sts_workshop", description: "Search Steam Workshop mods, inspect their metadata, and download to this checkout using the launcher's anonymous SteamPipe engine." })
      const add = (name: string, action: string, description: string, properties: ObjectValue, required: string[]) => {
        editor.add({
          name, description,
          input: { type: "object", properties: { ...common, ...properties }, required, additionalProperties: false },
          options: { namespace: "sts_workshop", codemode: true, permission: `sts_workshop_${name}` },
          execute: async (raw, context) => {
            const input = raw as ObjectValue
            const timeout = input.timeoutSeconds ?? (action === "download" ? 900 : 90)
            if (typeof timeout !== "number" || !Number.isInteger(timeout) || timeout < 1 || timeout > 7200) throw new Error("Invalid timeoutSeconds")
            if (action !== "search" && (typeof input.publishedFileId !== "string" || !/^[1-9][0-9]{0,19}$/.test(input.publishedFileId) || BigInt(input.publishedFileId) > 18446744073709551615n)) {
              throw new Error("publishedFileId must be a positive uint64 decimal string")
            }
            context.signal.throwIfAborted()
            if (!existsSync(executable)) throw new Error(`Workshop CLI is not built. Run at repository root: ${BUILD_COMMAND}`)
            const { timeoutSeconds: _timeout, outDir, ...args } = input
            const request: ObjectValue = { ...args, action }
            let outputDir: string | undefined
            if (action === "download") {
              const base = outputBase(root, outDir)
              await mkdir(base, { recursive: true })
              outputBase(root, base)
              outputDir = await mkdtemp(resolve(base, `${input.publishedFileId}-`))
              request.outputDir = outputDir
            }
            const controller = new AbortController()
            running.add(controller)
            try {
              const result = await runWorkshopCli(executable, root, request, timeout, {
                signal: AbortSignal.any([context.signal, controller.signal]), progress: context.progress,
              })
              if (outputDir) {
                const resultJson = resolve(outputDir, "result.json")
                await writeFile(resultJson, JSON.stringify(result, null, 2) + "\n")
                // Preserve the complete list on disk for mods with thousands of loose assets.
                const files = Array.isArray(result.files) ? result.files : []
                return { content: JSON.stringify({ ...result, files: files.slice(0, 100), fileCount: files.length, filesTruncated: files.length > 100, resultJson }) }
              }
              return { content: JSON.stringify(result) }
            } catch (error) {
              const message = error instanceof Error ? error.message : String(error)
              throw new Error(outputDir ? `${message}. Partial artifacts: ${outputDir}` : message)
            } finally {
              running.delete(controller)
            }
          },
        } as PluginTool)
      }
      add("search_mods", "search", "Search public Steam Workshop mods by name/keywords, or browse with an empty query. Returns publishedFileId, title, author, summary, URL and pagination. Uses the launcher's legacy/SSR browse formats without a Steam account; read get_mod_details before downloading an ambiguous match.", {
        query: { type: "string", maxLength: 200, description: "Mod name or keywords; empty browses the market." },
        page: { type: "integer", minimum: 1 },
        pageSize: { type: "integer", minimum: 1, maximum: 30, description: "Default 20." },
        sort: { type: "string", enum: ["totaluniquesubscribers", "trend", "mostrecent", "lastupdated"], description: "Default totaluniquesubscribers, matching the launcher's market default." },
        category: { type: "string", maxLength: 80, description: "Steam required tag, e.g. Character, Api, Tools, Qol, Cards; omit for all." },
        days: { type: "integer", enum: [-1, 1, 7, 30, 90, 180, 365], description: "Time window for trend only; -1 means all time, default 7." },
      }, [])
      add("get_mod_details", "details", "Read public Workshop metadata for a publishedFileId: title, description, creator, size, tags, API-reported children, update time and direct/UGC source. Children may be incomplete; collections cannot be downloaded as a mod.", { publishedFileId: itemId }, ["publishedFileId"])
      add("download_mod", "download", "Download one Workshop mod to a new local folder using the launcher's workshop-core engine (direct URL or anonymous SteamPipe/UGC). Returns file paths, sizes, SHA-256 and log/metadata paths. Defaults to agent-tmp/workshop-mods/<id>-<unique> and never overwrites previous downloads. Does not install on Android or download dependencies automatically. Requires the JVM CLI build described in .opencode/README.md.", {
        publishedFileId: itemId,
        outDir: { type: "string", description: "Base directory inside this repository; a fresh per-download child directory is created." },
        threads: { type: "integer", minimum: 1, maximum: 32, description: "Concurrent UGC chunks; default 8." },
      }, ["publishedFileId"])
    })
    return () => { for (const controller of running) controller.abort() }
  },
})
