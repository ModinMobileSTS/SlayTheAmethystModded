import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { test } from "node:test"

const root = resolve(import.meta.dirname, "../..")
const read = (path) => readFileSync(resolve(root, path), "utf8")
const names = ["tdd-slice", "tdd-red", "tdd-green", "tdd-refactor", "test-runner", "code-reviewer"]
// Inspect the checked-in native V2 rule declarations, not a substitute runtime evaluator.
function agent(name) {
  const text = read(`.opencode/agents/${name}.md`)
  const frontmatter = text.split("---")[1]
  const rules = frontmatter.split("\n").filter((line) => line.trim().startsWith("- {"))
    .map((line) => JSON.parse(line.trim().slice(2)))
  return { text, frontmatter, rules }
}

test("TDD agents use native V2 declarations and explicitly deny device tools", () => {
  for (const name of names) {
    const { text, frontmatter, rules } = agent(name)
    assert.match(frontmatter, /mode: subagent/)
    assert.match(frontmatter, /description:/)
    assert.doesNotMatch(frontmatter, /temperature:|request:|\btools:|\bpermission:/)
    assert.ok(rules.some((rule) => rule.action === "sts_*" && rule.resource === "*" && rule.effect === "deny"), name)
    assert.match(text, /AGENTS\.md/)
    assert.match(text, /shell/i)
  }
})

test("only the dispatcher can delegate, to exactly the five serial stage roles", () => {
  for (const name of names) {
    const { text, rules } = agent(name)
    const delegation = rules.filter((rule) => rule.action === "subagent")
    assert.deepEqual(delegation[0], { action: "subagent", resource: "*", effect: "deny" })
    assert.deepEqual(delegation.slice(1).map((rule) => [rule.resource, rule.effect]),
      name === "tdd-slice" ? names.slice(1).map((role) => [role, "allow"]) : [])
    if (name === "tdd-slice") assert.match(text, /serially.*foreground/)
  }
})

test("read-only roles cannot edit or execute arbitrary shell commands", () => {
  for (const name of ["tdd-slice", "test-runner", "code-reviewer"]) {
    assert.deepEqual(agent(name).rules.filter((rule) => rule.action === "edit"),
      [{ action: "edit", resource: "*", effect: "deny" }])
  }
  for (const name of ["tdd-slice", "code-reviewer"]) {
    assert.deepEqual(agent(name).rules.filter((rule) => rule.action === "shell"), [
      { action: "shell", resource: "*", effect: "deny" },
      { action: "shell", resource: "git status *", effect: "allow" },
      { action: "shell", resource: "git diff *", effect: "allow" },
    ])
  }
})

test("workflow keeps behavior gates, complete handoffs and project-neutral self-checks", () => {
  const skill = read(".opencode/skills/tdd-development/SKILL.md")
  for (const term of ["structural Red", "Infrastructure", "frozen", "two corrective cycles", "No change required", "NOT RUN", "self-check"]) {
    assert.ok(skill.includes(term), term)
  }
  assert.doesNotMatch(skill, /environment_test\.lua|SpireForge|scripts\/lua/)
  const template = read(".opencode/skills/tdd-development/references/slice-task-template.md")
  for (const term of ["Project context path", "Accepted design", "Allowed production paths", "Allowed test/fixture paths", "Frozen", "working directory", "Baseline", "Documentation owner", "Previous evidence"]) {
    assert.ok(template.includes(term), term)
  }
})

test("project context references real local entry points without changing device approvals", () => {
  const config = JSON.parse(read("opencode.json"))
  assert.equal(config.experimental.subagent_depth, 2)
  assert.ok(config.permissions.every((rule) => rule.action.startsWith("sts_") && rule.effect === "ask"))
  const context = read(".opencode/skills/tdd-development/project/slay-the-amethyst.md")
  for (const path of ["AGENTS.md", ".opencode/package.json", "app/build.gradle.kts", "workshop-core/build.gradle.kts", "online-service/package.json", "cloud-function/package.json"]) {
    read(path)
    assert.ok(context.includes(path), path)
  }
  assert.match(context, /README\.md/)
  assert.match(context, /explicit authorization/)
  assert.match(context, /disabled/)
})
