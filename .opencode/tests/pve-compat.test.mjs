import assert from "node:assert/strict"
import { test } from "node:test"

import plugin from "../plugins/pve-compat.ts"

test("V2 compatibility transform adds an empty required array only when missing", async () => {
  const tools = new Map([
    ["optional", { input: { type: "object", properties: { name: { type: "string" } } } }],
    ["mandatory", { input: { type: "object", properties: {}, required: ["id"] } }],
  ])
  await plugin.setup({
    tool: {
      async transform(register) {
        register({
          list() { return [...tools].map(([id, value]) => ({ id, ...value })) },
          update(id, change) { change(tools.get(id)) },
        })
      },
    },
  })
  assert.deepEqual(tools.get("optional").input.required, [])
  assert.deepEqual(tools.get("mandatory").input.required, ["id"])
})
