-- SPDX-License-Identifier: GPL-3.0-only
-- Read-only public vocabulary shards. No input history is written to disk.
local M = {}
local MAX_CACHE_BYTES = 4 * 1024 * 1024
local MAX_NODES, MAX_RESULTS = 6000, 256
local SEARCH_SECONDS = 0.006

function M.new(directory)
  return {directory = directory, shards = {}, order = {}, bytes = 0}
end

local function shard(index, length)
  if index.shards[length] then return index.shards[length] end
  local file = io.open(index.directory .. string.format('/%02d.bin', length), 'rb')
  if not file then return nil end
  local data = file:read('*a')
  file:close()
  if #data < 16 or data:sub(1, 8) ~= 'AXTI1\0\0\0' or data:byte(9) ~= length then return nil end
  local count = string.unpack('<I4', data, 13)
  local width = length + 4
  if #data ~= 16 + count * width or #data > MAX_CACHE_BYTES then return nil end
  while index.bytes + #data > MAX_CACHE_BYTES and #index.order > 0 do
    local oldest = table.remove(index.order, 1)
    index.bytes = index.bytes - #index.shards[oldest].data
    index.shards[oldest] = nil
  end
  local value = {data = data, count = count, width = width}
  index.shards[length] = value
  index.order[#index.order + 1] = length
  index.bytes = index.bytes + #data
  return value
end

-- Every branch is pruned to a contiguous prefix range in the sorted shard.
-- At two edits we do one binary exact lookup for the remaining suffix. This
-- avoids enumerating arbitrary strings or scanning the dictionary on keypress.
function M.search(index, input, neighbors)
  local length = #input
  if length < 4 or length > 24 or not input:match('^[a-z]+$') then return {} end
  local started = os.clock()
  local data = shard(index, length)
  if not data or data.count == 0 then return {} end
  local bytes, width = data.data, data.width
  local results, seen, nodes, stopped = {}, {}, 0, false
  local function code_at(row)
    local start = 17 + row * width
    return bytes:sub(start, start + length - 1)
  end
  local function add(row, edits)
    if seen[row] or edits == 0 then return end
    seen[row] = true
    local code = code_at(row)
    local weight = string.unpack('<I4', bytes, 17 + row * width + length)
    results[#results + 1] = {code = code, weight = weight, edits = edits,
      score = math.log(weight + 1) - edits * 1.5}
    if #results >= MAX_RESULTS then stopped = true end
  end
  local function lower(lo, hi, position, char)
    while lo < hi do
      local mid = (lo + hi) // 2
      if bytes:byte(17 + mid * width + position - 1) < char then lo = mid + 1 else hi = mid end
    end
    return lo
  end
  local function exact(lo, hi, key, edits)
    local end_row = hi
    while lo < hi do
      local mid = (lo + hi) // 2
      if code_at(mid) < key then lo = mid + 1 else hi = mid end
    end
    if lo < end_row and code_at(lo) == key then add(lo, edits) end
  end
  local visit
  visit = function(lo, hi, position, prefix, edits, limit)
    if stopped or lo >= hi then return end
    nodes = nodes + 1
    if nodes > MAX_NODES or (nodes % 64 == 0 and os.clock() - started > SEARCH_SECONDS) then
      stopped = true
      return
    end
    if edits == limit then
      exact(lo, hi, prefix .. input:sub(position), edits)
      return
    end
    if position > length then add(lo, edits); return end
    local original = input:sub(position, position)
    local allowed = original .. (neighbors[original] or ''):gsub(original, '')
    for i = 1, #allowed do
      local char = allowed:byte(i)
      local first = lower(lo, hi, position, char)
      local last = lower(first, hi, position, char + 1)
      if first < last then
        visit(first, last, position + 1, prefix .. string.char(char),
          edits + (i == 1 and 0 or 1), limit)
      end
      if stopped then break end
    end
  end
  -- All one-key repairs have priority over two-key searches under the budget.
  visit(0, data.count, 1, '', 0, 1)
  if not stopped then visit(0, data.count, 1, '', 0, 2) end
  table.sort(results, function(a, b)
    if a.score ~= b.score then return a.score > b.score end
    return a.code < b.code
  end)
  return results, {nodes = nodes, truncated = stopped, cache_bytes = index.bytes,
                   seconds = os.clock() - started}
end

return M
