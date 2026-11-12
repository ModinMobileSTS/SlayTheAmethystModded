import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { test } from "node:test"

const root = resolve(import.meta.dirname, "../..")
const read = (path) => readFileSync(resolve(root, path), "utf8")
const skillPath = ".opencode/skills/test-after/SKILL.md"

test("Test-After skill is discoverable and describes its verification boundaries", () => {
  const skill = read(skillPath)
  assert.match(skill, /^---\nname: test-after\ndescription: .+\n---\n/)
  const normalized = skill.replace(/\s+/g, " ")
  for (const term of ["Before implementation", "After implementation", "old behavior", "user changes", "do not automatically delegate", "separate user authorization", "not run or blocked"]) {
    assert.ok(normalized.includes(term), term)
  }
})

test("repository guidance uses risk-based verification without the retired TDD section", () => {
  const rules = read("AGENTS.md")
  const readme = read(".opencode/README.md")
  assert.ok(rules.includes(skillPath))
  assert.match(rules, /Routine changes still require verification/)
  assert.doesNotMatch(rules, /TDD workflow|tdd-development|tdd-slice/i)
  assert.match(readme, /test-after\/SKILL\.md/)
})
