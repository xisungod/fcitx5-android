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

-- Single-syllable fallback covers spellings such as hsi -> shi. Order is from
-- the pinned Rime Ice AXPL1 S rows in assets/typing/pinyin_touch_model.tsv.
local syllable_weights = {}
local syllable_rank = 0
for code in ([[
shi yi ji zhi bu you li de jian wei ren qi gong zhong wu xing yu zi xiang da zai xian ye fa jing xi
guo jie xin di yuan jia fu shu cheng ke zhe shang ge hui dao chu sheng zhu jin zheng le xiao guan
shou fang hua dian jiao he si chang tong wo ying ti yao yan qing qu bao jiu zuo ju shen er wen dong
xue yong mei quan ming du fen ban yin liang zhan ta gao wang bei hao xia she qian hai bi hu lai
zhang dan cai jiang huo ding nian ni dai mian ci ru xie bian yang dui an biao men xu neng gu duo fan
pin ben fei yue chan suo lu shui tian lian liu hou wan ping mu ba zu gan kai ling dang tou zhuan tai
bing tu tiao na huan su zhen lv gui nan san zui shuo nei mo min gai feng guang zhao cha ma che dou
kan chi gou shan wai jue bai ze zhou chuan zong zhuang duan yun ran liao qiu kong deng ya hen ri zao
luo ting mi ce lao pai qiang bo lei chong rong xun kou xuan shao que geng pei lun cun tan ai jun
kuai pian han me pi chuang kao hang se man can xiu cong te tui chao lan mao lin mai gang tao nv qin
bie song sui nong lie kuan suan e diao kuang hong ku rang huang gei pu zeng sha zhun tuo zou re qie
la po pan ceng tie gen nin chen meng tang piao long hun tuan cao bang lou chun ruan cuo shuang qiao
a qun xiong nao hei kang chou ka zhai sai nai pao cang lang peng miao niu huai mou zhuo zen ruo leng
rou kuo ning pa sun ou dun ao zan mang zha heng shun cu lve zhui gua ha kun juan wa guai za luan
pang nu zun bin fou reng shei die kui sou shuai zang zhua rao cui ken dei fo teng chai nuo niao en
sa chui niang shai run kua sao shua rui pen ne gun o mie sang nuan sen qia qiong beng diu tun zuan
ca eng zhei nang ga keng ang nie zei nen cou weng lia tei seng cuan nve chuo pou jiong shuan pie
chuai miu zhuai yo cen chua lo dia kei cei nou ei den biang fiao rua
]]):gmatch('%S+') do
  syllable_rank = syllable_rank + 1
  syllable_weights[code] = 1000 - syllable_rank
end

local function spelling_at(data, row, length)
  local start = 17 + row * data.width
  return data.data:sub(start, start + length - 1)
end

local function exact_weight(data, code)
  if not data then return 0 end
  local length = #code
  local lo, hi = 0, data.count
  while lo < hi do
    local mid = (lo + hi) // 2
    if spelling_at(data, mid, length) < code then lo = mid + 1 else hi = mid end
  end
  if lo < data.count and spelling_at(data, lo, length) == code then
    return string.unpack('<I4', data.data, 17 + lo * data.width + length)
  end
  return 0
end

local function prefix_lower(data, lo, hi, position, char)
  while lo < hi do
    local mid = (lo + hi) // 2
    if data.data:byte(17 + mid * data.width + position - 1) < char then
      lo = mid + 1
    else
      hi = mid
    end
  end
  return lo
end

-- One bounded dictionary lookup per operation. No edit chaining, and no scan
-- across the vocabulary. Range-based insertion only visits letters present
-- under each matching prefix in the sorted shard.
function M.single_edits(index, input)
  local length = #input
  if length < 3 or length > 25 or not input:match('^[a-z]+$') then return {} end

  local function find(kind, target_length)
    if target_length > 24 then return nil end
    local data = target_length >= 4 and shard(index, target_length) or nil
    local best, best_score
    local function consider(code, weight, repeated)
      -- A dropped/duplicated repeated letter is a little more plausible
      -- than an arbitrary deletion; dictionary weight still leads.
      local score = repeated and weight * 1.15 or weight
      if weight > 0 and code ~= input and
          (not best or score > best_score or (score == best_score and code < best.code)) then
        best = {kind = kind, code = code, weight = weight}
        best_score = score
      end
    end
    if kind == 'omitted' then
      if data and data.count > 0 then
        local lo, hi = 0, data.count
        for i = 1, length + 1 do
          local prefix, suffix = input:sub(1, i - 1), input:sub(i)
          local first = lo
          while first < hi do
            local char = data.data:byte(17 + first * data.width + i - 1)
            local last = prefix_lower(data, first + 1, hi, i, char + 1)
            local code = prefix .. string.char(char) .. suffix
            -- Search only within this prefix+letter branch.
            local left, right = first, last
            while left < right do
              local mid = (left + right) // 2
              if spelling_at(data, mid, target_length) < code then left = mid + 1 else right = mid end
            end
            if left < last and spelling_at(data, left, target_length) == code then
              local weight = string.unpack('<I4', data.data, 17 + left * data.width + target_length)
              consider(code, weight, char == input:byte(i - 1) or char == input:byte(i))
            end
            first = last
          end
          if i > length then break end
          local char = input:byte(i)
          lo = prefix_lower(data, lo, hi, i, char)
          hi = prefix_lower(data, lo, hi, i, char + 1)
          if lo == hi then break end
        end
      end
      if target_length <= 6 then
        for i = 1, length + 1 do
          local prefix, suffix = input:sub(1, i - 1), input:sub(i)
          for char = 97, 122 do
            local code = prefix .. string.char(char) .. suffix
            consider(code, syllable_weights[code] or 0,
              char == input:byte(i - 1) or char == input:byte(i))
          end
        end
      end
    elseif kind == 'extra' then
      for i = 1, length do
        local char = input:byte(i)
        local code = input:sub(1, i - 1) .. input:sub(i + 1)
        consider(code, math.max(exact_weight(data, code), syllable_weights[code] or 0),
          char == input:byte(i - 1) or char == input:byte(i + 1))
      end
    else
      for i = 1, length - 1 do
        if input:byte(i) ~= input:byte(i + 1) then
          local code = input:sub(1, i - 1) .. input:sub(i + 1, i + 1) ..
            input:sub(i, i) .. input:sub(i + 2)
          consider(code, math.max(exact_weight(data, code), syllable_weights[code] or 0))
        end
      end
    end
    return best
  end

  local edits = {}
  local function add(candidate)
    if candidate then edits[#edits + 1] = candidate end
  end
  -- A swap is more specific than an inferred insertion or deletion.
  add(find('swapped', length))
  add(find('extra', length - 1))
  add(find('omitted', length + 1))
  return edits
end

return M
