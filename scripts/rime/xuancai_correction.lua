-- Correction supplements exact spelling, without taking over short abbreviations or Lua commands.
local M = {}
local neighbors = {}
for _, row in ipairs({'qwertyuiop', 'asdfghjkl', 'zxcvbnm'}) do
  for i = 1, #row do
    neighbors[row:sub(i, i)] = row:sub(math.max(1, i - 1), math.min(#row, i + 1))
  end
end

-- Bounded weighted edit distance: adjacent keys cost 1, omissions/transpositions
-- cost 2, unrelated substitutions cost 4. Never rewrite the composing input.
local function distance(a, b, limit)
  if math.abs(#a - #b) * 2 > limit then return limit + 1 end
  local previous, older = {}, nil
  for j = 0, #b do previous[j] = j * 2 end
  for i = 1, #a do
    local current, lowest = {[0] = i * 2}, i * 2
    local x = a:sub(i, i)
    for j = 1, #b do
      local y = b:sub(j, j)
      local cost = x == y and 0 or ((neighbors[x] or ''):find(y, 1, true) and 1 or 4)
      local value = math.min(previous[j] + 2, current[j - 1] + 2, previous[j - 1] + cost)
      if older and j > 1 and x == b:sub(j - 1, j - 1) and a:sub(i - 1, i - 1) == y then
        value = math.min(value, older[j - 2] + 2)
      end
      current[j], lowest = value, math.min(lowest, value)
    end
    if lowest > limit then return limit + 1 end
    older, previous = previous, current
  end
  return previous[#b]
end

local function phrase_repair(input, seg, env)
  if #input < 16 or #input > 64 then return end
  -- A complete dictionary phrase wins. The normal translator's learned phrases
  -- also have higher quality than this fallback; this probe never learns twice.
  local exact = env.exact:query(input, seg)
  if exact then
    for candidate in exact:iter() do
      if candidate._end == seg._end and candidate.type ~= 'sentence' then return end
      break
    end
  end
  local matches, blocked = {}, {}
  -- Rime may keep a long phrase only up to the initial of its final syllable,
  -- then append unrelated words for the remainder. Probe at most four suffix
  -- lengths and eight candidates each; no dictionary scan on the typing path.
  for trim = 0, 4 do
    local query = input:sub(1, #input - trim)
    local translation = env.translator:query(query, seg)
    if translation then
      local count = 0
      for candidate in translation:iter() do
        count = count + 1
        if candidate.type == 'phrase' and (utf8.len(candidate.text) or 0) >= 6 then
          local spelling = candidate.comment
          local code = spelling:gsub(' ', '')
          if code:match('^[a-z]+$') and #code >= 16 and #code <= 64 then
            local final = spelling:match('%S+$')
            if trim == 0 and candidate._end < seg._end and candidate.preedit:match('%S+$') == final then
              blocked[candidate.text] = true -- keep intentionally appended words
            end
            local limit = math.min(10, math.floor(#code * 0.42))
            if not blocked[candidate.text] and input:sub(1, #code) ~= code then
              local score = distance(input, code, limit)
              if score > 0 and score <= limit then
                local previous = matches[candidate.text]
                if not previous or score < previous.score then
                  matches[candidate.text] = {text = candidate.text, score = score, spelling = spelling,
                    quality = candidate.quality}
                end
              end
            end
          end
        end
        if count >= 8 then break end
      end
    end
  end
  local ranked = {}
  for text, candidate in pairs(matches) do
    if not blocked[text] then ranked[#ranked + 1] = candidate end
  end
  table.sort(ranked, function(a, b)
    if a.score ~= b.score then return a.score < b.score end
    if a.quality ~= b.quality then return a.quality > b.quality end
    return a.text < b.text
  end)
  -- Ambiguous repairs remain ordinary candidates; do not force a guessed winner.
  if #ranked > 1 and ranked[1].score == ranked[2].score then return end
  local best = ranked[1]
  if best then
    local candidate = Candidate('xuancai_phrase_repair', seg.start, seg._end, best.text, '纠错')
    candidate.preedit = input
    candidate.quality = 1.3
    yield(candidate)
  end
end

function M.init(env)
  env.translator = Component.Translator(env.engine, '', 'script_translator@xuancai_correction')
  env.exact = Component.Translator(env.engine, '', 'script_translator@xuancai_exact')
  env.commands = {}
  for _, key in ipairs({'date', 'time', 'week', 'datetime', 'timestamp'}) do
    local command = env.engine.schema.config:get_string('date_translator/' .. key)
    if command then env.commands[command] = true end
  end
end
function M.func(input, seg, env)
  if #input < 3 or not input:match('^[a-z]+$') or env.commands[input] then return end
  phrase_repair(input, seg, env)
  local translation = env.translator:query(input, seg)
  if translation then for candidate in translation:iter() do yield(candidate) end end
end
function M.fini(env)
  env.translator = nil
  env.exact = nil
end
return M
