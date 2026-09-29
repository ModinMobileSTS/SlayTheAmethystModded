import { Plugin } from "@opencode/plugin"

export default Plugin.define({
  id: "pve-compat",
  async setup(ctx) {
    await ctx.tool.transform((editor) => {
      for (const existing of editor.list()) {
        const schema = existing.input as Record<string, unknown>
        if (!schema || schema.type !== "object" || schema.required !== undefined) continue
        editor.update(existing.id, (tool) => {
          tool.input = { ...schema, required: [] }
        })
      }
    })
  },
})
