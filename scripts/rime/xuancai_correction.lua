-- Correction supplements exact spelling, without taking over short abbreviations or Lua commands.
local M = {}
function M.init(env)
  env.translator = Component.Translator(env.engine, '', 'script_translator@xuancai_correction')
  env.commands = {}
  for _, key in ipairs({'date', 'time', 'week', 'datetime', 'timestamp'}) do
    local command = env.engine.schema.config:get_string('date_translator/' .. key)
    if command then env.commands[command] = true end
  end
end
function M.func(input, seg, env)
  if #input < 3 or not input:match('^[a-z]+$') or env.commands[input] then return end
  local translation = env.translator:query(input, seg)
  if translation then for candidate in translation:iter() do yield(candidate) end end
end
function M.fini(env)
  env.translator = nil
end
return M
