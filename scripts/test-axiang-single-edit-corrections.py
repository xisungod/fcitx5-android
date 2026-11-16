#!/usr/bin/env python3
"""Test production Lua lookup and Rime candidate assembly with a tiny public fixture."""
import argparse
import ctypes.util
import importlib.util
from pathlib import Path
import struct
import sys
from tempfile import TemporaryDirectory

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent


def lua_module():
    spec = importlib.util.spec_from_file_location("typo_check", SCRIPTS / "check-axiang-typo-index.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture(directory):
    rows = {
        # Input nhaoi has competing dictionary entries per operation.
        "nahoi": 18000, "nhoai": 12000,  # swap
        "nhao": 18000, "haoi": 12000,  # delete
        "nihaoi": 18000, "nhaoil": 12000,  # insert
        "nihao": 22000, "zhuangtai": 37000,
    }
    for length in range(4, 25):
        records = sorted((key, weight) for key, weight in rows.items() if len(key) == length)
        payload = b"".join(code.encode("ascii") + struct.pack("<I", weight) for code, weight in records)
        (directory / f"{length:02}.bin").write_bytes(
            b"AXTI1\0\0\0" + struct.pack("<B3xI", length, len(records)) + payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lua-lib", help="Optional explicit liblua5.4 shared library")
    parser.add_argument("--stage", type=Path, help="Optional packaged data for a real-dictionary lookup")
    args = parser.parse_args()
    lib = args.lua_lib or ctypes.util.find_library("lua5.4")
    if not lib:
        parser.error("liblua5.4 is required")
    module = lua_module()
    with TemporaryDirectory(prefix="axiang-single-edit-") as tmp:
        directory = Path(tmp)
        fixture(directory)
        lua_file = SCRIPTS / "rime/axiang_typo_index.lua"
        lua_source = lua_file.read_text()
        # The compact fallback list must remain in lockstep with the tracked public model.
        fallback = lua_source.split("for code in ([[", 1)[1].split("]])", 1)[0].split()
        model = SCRIPTS.parent / "app/src/main/assets/typing/pinyin_touch_model.tsv"
        entries = [line.split("\t")[1:] for line in model.read_text().splitlines() if line.startswith("S\t")]
        assert fallback == [code for code, _ in sorted(entries, key=lambda row: (-int(row[1]), row[0]))]
        setup = "\n".join((
            "package.path = " + module.lua_string(str(SCRIPTS / "rime/?.lua")) + " .. ';' .. package.path",
            "local dictionary = " + module.lua_string(str(directory)),
            "local stage = " + (module.lua_string(str(args.stage / "lua/axiang_typo")) if args.stage else "nil"),
            "local index = require('axiang_typo_index')",
            "local state = index.new(dictionary)",
        ))
        script = setup + r'''
local function kinds(rows)
  local result = {}
  for _, candidate in ipairs(rows) do
    assert(not result[candidate.kind], 'more than one code for a single edit')
    result[candidate.kind] = candidate.code
  end
  return result
end
local codes = kinds(index.single_edits(state, 'nhaoi'))
assert(codes.swapped == 'nahoi' and codes.extra == 'nhao' and codes.omitted == 'nihaoi')
assert(kinds(index.single_edits(state, 'hsi')).swapped == 'shi', 'canonical short syllable')
assert(kinds(index.single_edits(state, 'shii')).extra == 'shi', 'short syllable duplicate')
assert(kinds(index.single_edits(state, 'nhao')).omitted == 'nihao', 'dictionary omission')
assert(kinds(index.single_edits(state, 'ihao')).omitted == 'nihao', 'initial omission')
assert(kinds(index.single_edits(state, 'niha')).omitted == 'nihao', 'terminal omission')
assert(kinds(index.single_edits(state, 'nihhao')).extra == 'nihao', 'dictionary doubled key')
assert(kinds(index.single_edits(state, 'zhuagn tai')).swapped == nil, 'mixed spelling')
assert(kinds(index.single_edits(state, 'zhuagntai')).swapped == 'zhuangtai')
assert(next(index.single_edits(state, 'abc1')) == nil)
assert(next(index.single_edits(state, "ni'hao")) == nil)
assert(next(index.single_edits(state, 'as')) == nil)
assert(next(index.single_edits(state, ('a'):rep(26))) == nil)
assert(state.bytes <= 4 * 1024 * 1024, 'shard cache grew')
if stage then
  local packaged = index.new(stage)
  assert(kinds(index.single_edits(packaged, 'hsi')).swapped == 'shi')
  local swaps = kinds(index.single_edits(packaged, 'nihoa'))
  assert(swaps.swapped == 'nihao', 'hello regression against packaged dictionary')
  assert(kinds(index.single_edits(packaged, 'zhonguo')).omitted == 'zhongguo',
    'prefer a plausible repeated-letter omission when frequencies are close')
end

-- Fake only the Rime boundary. Run the *real* Lua translator and assert it
-- consumes the entire original segment while exact decoding the edited code.
local corrector = require('xuancai_correction')
local segment = {start = 3, _end = 8}
local raw = 'nhaoi'
local current_head = raw
local seen, generated = {}, {}
local function translation(values)
  return {iter = function()
    local pos = 0
    return function() pos = pos + 1; return values[pos] end
  end}
end
local env = {commands = {}, heads = {}, head_order = {}, typo_index = state}
env.translator = {query = function() return nil end}
env.exact = {query = function(_, input, seg)
  assert(seg == segment)
  seen[#seen + 1] = input
  local words = {nhaoi = '原拼写', nahoi = '调序', nhao = '多字', nihaoi = '漏字'}
  if not words[input] then return nil end
  local complete = {type = 'phrase', text = words[input], comment = input == raw and current_head or input,
    _end = segment.start + #input, quality = .71}
  if input == raw then return translation({complete}) end
  return translation({{type = 'phrase', text = '错误尾长', comment = input,
    _end = segment.start + #input - 1, quality = 3}, complete})
end}
function Candidate(kind, start, finish, value, comment)
  return {type = kind, start = start, _end = finish, text = value, comment = comment}
end
function yield(candidate)
  if candidate.type == 'axiang_swapped_repair' or candidate.type == 'axiang_extra_repair' or
      candidate.type == 'axiang_omitted_repair' then
    assert(candidate.start == 3 and candidate._end == 8 and candidate.preedit == raw)
    assert(candidate.quality < .71, 'exact original spelling was not preferred')
    assert(candidate.comment == '纠错')
    generated[#generated + 1] = candidate.text
  end
end
corrector.func(raw, segment, env)
assert(table.concat(generated, '/') == '调序/多字/漏字', table.concat(generated, '/'))
assert(table.concat(seen, '/') == 'nhaoi/nahoi/nhao/nihaoi', 'query budget or order changed')

generated, seen = {}, {}
current_head = 'na ha o i'
corrector.func(raw, segment, env)
assert(#generated == 0 and #seen == 1, 'intentional abbreviation or alias was overridden')
seen = {}
env.commands[raw] = true
corrector.func(raw, segment, env)
assert(#seen == 0, 'Lua command was overridden')
return '{"single_edit_operations":3,"original_spelling_first":true,"bounded_probes":3}'
'''
        print(module.Lua(lib).execute(script))


if __name__ == "__main__":
    main()
