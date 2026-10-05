-- SPDX-License-Identifier: GPL-3.0-only
-- Canonical QWERTY geometry, including diagonal neighbors across both row gaps.
-- This is a candidate prior; the Android touch layer uses actual laid-out keys.
local centers, neighbors = {}, {}
for y, row in ipairs({'qwertyuiop', 'asdfghjkl', 'zxcvbnm'}) do
  local offset = ({0, 0.5, 1.5})[y]
  for i = 1, #row do centers[row:sub(i, i)] = {x = i - 1 + offset, y = y - 1} end
end
for key, center in pairs(centers) do
  local adjacent = {}
  for other, position in pairs(centers) do
    local dx, dy = center.x - position.x, center.y - position.y
    -- Include touching corners too: F/V and N/K are one column and one row
    -- apart on the mobile layout, just as F/C and N/J are vertically aligned.
    if math.abs(dx) <= 1.01 and math.abs(dy) <= 1 then
      adjacent[#adjacent + 1] = other
    end
  end
  table.sort(adjacent)
  neighbors[key] = table.concat(adjacent)
end
return neighbors
