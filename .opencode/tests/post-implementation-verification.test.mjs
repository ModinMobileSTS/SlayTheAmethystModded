import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { test } from "node:test"

const root = resolve(import.meta.dirname, "../..")
const read = (path) => readFileSync(resolve(root, path), "utf8")
const skillPath = ".opencode/skills/post-implementation-verification/SKILL.md"

test("post-implementation skill is discoverable and describes its verification boundaries", () => {
  const skill = read(skillPath)
  assert.match(skill, /^---\nname: post-implementation-verification\ndescription: .+\n---\n/)
  const normalized = skill.replace(/\s+/g, " ")
  for (const term of ["Before implementation", "After implementation", "old behavior", "user changes", "do not automatically delegate", "separate user authorization", "not run or blocked"]) {
    assert.ok(normalized.includes(term), term)
  }
})

test("repository guidance uses risk-based verification and keeps TDD opt-in", () => {
  const rules = read("AGENTS.md")
  const readme = read(".opencode/README.md")
  assert.ok(rules.includes(skillPath))
  assert.match(rules, /Routine changes still require verification/)
  assert.match(rules, /only when the user explicitly requests this TDD workflow/i)
  assert.match(readme, /post-implementation-verification\/SKILL\.md/)
  assert.match(readme, /Single-slice TDD workflow \(opt-in\)/)
})
