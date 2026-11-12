import assert from "node:assert/strict"
import { readFileSync, readdirSync } from "node:fs"
import { resolve } from "node:path"
import { test } from "node:test"

const root = resolve(import.meta.dirname, "../..")
const base = resolve(root, ".opencode/skills")
const stages = [
  "feature-value-design",
  "feature-isolated-implementation",
  "feature-integration-design",
  "feature-integration",
  "feature-cleanup-verification",
]
const entry = "feature-refactoring"
const read = (id) => readFileSync(resolve(base, id, "SKILL.md"), "utf8")

test("entry advertises exactly the five implemented stages in accepted order", () => {
  const text = read(entry)
  const listed = [...text.matchAll(/^\d\. `([^`]+)`/gm)].map((m) => m[1])
  assert.deepEqual(listed, stages)
  assert.doesNotMatch(text, /待实现|feature-migration-mapping|feature-legacy-removal/)
  for (const id of stages) assert.ok(read(id).length > 0)
})

test("skills are discoverable siblings with valid local document links", () => {
  for (const id of [entry, ...stages]) {
    const text = read(id)
    assert.ok(text.startsWith(`---\nname: ${id}\ndescription: `), id)
    assert.match(text, /^---\nname: [^\n]+\ndescription: [^\n]+\n---\n/)
    const folder = resolve(base, id)
    assert.equal(readdirSync(folder).filter((p) => p === "SKILL.md").length, 1)
    for (const match of text.matchAll(/\]\(([^)]+\.md)\)/g)) {
      assert.ok(readFileSync(resolve(folder, match[1]), "utf8").length > 0, match[1])
    }
    assert.match(text, /停止|不自动推进/, id)
  }
})

test("value design and integration design retain distinct human decision gates", () => {
  const value = read(stages[0])
  assert.match(value, /不设计类、模块、技术接口和适配层/)
  assert.match(value, /不要求全面枚举接口或生成一比一迁移表/)
  assert.match(value, /经人类确认后/)
  const isolated = read(stages[1])
  assert.match(isolated, /重大架构取舍必须先经人类确认/)
  assert.match(isolated, /不修改真实业务入口或切换调用方/)
  const design = read(stages[2])
  assert.match(design, /不修改生产代码或测试/)
  assert.match(design, /清单和接入方案须由人类明确确认/)
})

test("each writing stage reuses Test-After without deferring integration evidence", () => {
  for (const id of [stages[1], stages[3], stages[4]]) {
    assert.match(read(id), /加载\s*`post-implementation-verification`/)
  }
  assert.ok(read("post-implementation-verification").length > 0)
  assert.match(read(stages[3]), /不能把所有测试拖到最后/)
  assert.match(read(stages[3]), /接入尚未完成/)
  assert.match(read(stages[4]), /接入未完成或证据不足时，先返回接入阶段/)
  assert.match(read(stages[4]), /业务契约测试需有新覆盖去向/)
  assert.match(read(stages[4]), /未决清理项或必要验证阻塞时报告部分完成/)
})

test("shared handoff preserves scope, evidence and current device restrictions", () => {
  const shared = readFileSync(resolve(base, entry, "references/handoff.md"), "utf8")
  for (const term of ["PASS / FAIL / NOT RUN / BLOCKED", "工作目录", "退出结果",
    "保留其他人的未提交修改", "本次明确指定的目标设备和操作授权",
    "connected*AndroidTest", "不自动创建计划文件", "阶段完成后停止并交接"]) {
    assert.ok(shared.includes(term), term)
  }
})
