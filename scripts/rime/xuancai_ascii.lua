-- Mobile Latin mode commits printable characters immediately, without an inline preedit.
-- Toggling alone preserves pending Chinese; the first English character confirms it.
local M = {}
function M.func(key, env)
  local context = env.engine.context
  if not context:get_option('ascii_mode') or key:release() or key:ctrl()
      or key:alt() or key:super() then return 2 end
  local code = key.keycode
  if code < 0x20 or code >= 0x7f then return 2 end
  if context:is_composing() then
    context:set_option('ascii_mode', false)
    context:commit()
    context:set_option('ascii_mode', true)
  end
  env.engine:commit_text(string.char(code))
  return 1
end
return M
