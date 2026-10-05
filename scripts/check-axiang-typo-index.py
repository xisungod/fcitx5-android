#!/usr/bin/env python3
"""Exercise the packaged Lua typo search against dictionary-derived mutations.

This checks retrieval, candidate validity and bounded work, independently of the
Android touch tests and native Rime candidate ranking. It needs liblua5.4, not a
Lua executable. Successful stdout is one JSON report suitable for CI artifacts.
"""
import argparse
import ctypes
import ctypes.util
import hashlib
import json
import math
from pathlib import Path
import statistics
import string
import struct
import sys


MAX_CACHE = 4 * 1024 * 1024
MAX_RESULTS = 256
MAX_NODES = 6001  # The first over-budget visit terminates the production walk.


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def qwerty_edges():
    # Half-key integer coordinates give an independent boundary description:
    # same-row neighbours are one key apart; adjacent rows may share an edge or corner.
    positions = {}
    for row, (letters, left) in enumerate(zip(
            ('qwertyuiop', 'asdfghjkl', 'zxcvbnm'), (0, 1, 3))):
        positions.update({letter: (2 * column + left, row)
                          for column, letter in enumerate(letters)})
    adjacent = {}
    for letter, (x, y) in positions.items():
        adjacent[letter] = ''.join(sorted(other for other, (ox, oy) in positions.items()
                                         if (y == oy and abs(x - ox) == 2)
                                         or (abs(y - oy) == 1 and abs(x - ox) <= 2)))
    return adjacent


def read_index(stage):
    directory = stage / 'lua' / 'axiang_typo'
    manifest = json.loads((directory / 'SOURCE.json').read_text())
    check(manifest['scope'] == {'min_letters': 4, 'max_letters': 24,
                               'min_characters': 2, 'max_characters': 5,
                               'max_adjacent_substitutions': 2}, 'Unexpected index scope')
    sources = manifest['sources_sha256']
    check(bool(sources), 'The index must identify its public dictionary sources')
    for name, expected in sources.items():
        path = (stage / name).resolve()
        check(path.is_relative_to(stage), 'Dictionary source escapes the stage')
        check(digest(path) == expected, f'Source digest mismatch: {name}')
    records, by_length, by_letter = {}, {}, {}
    total_bytes, maximum_shard = 0, 0
    for length in range(4, 25):
        name = f'{length:02d}.bin'
        data = (directory / name).read_bytes()
        expected = manifest['files'][name]
        check(data[:8] == b'AXTI1\0\0\0' and data[8] == length, f'Bad header: {name}')
        count = struct.unpack_from('<I', data, 12)[0]
        check(len(data) == 16 + count * (length + 4), f'Bad shard size: {name}')
        check(expected == {'records': count, 'bytes': len(data),
                           'sha256': hashlib.sha256(data).hexdigest()}, f'Bad shard manifest: {name}')
        check(len(data) <= MAX_CACHE, f'One shard exceeds the cache budget: {name}')
        total_bytes += len(data)
        maximum_shard = max(maximum_shard, len(data))
        previous = ''
        for offset in range(16, len(data), length + 4):
            code = data[offset:offset + length].decode('ascii')
            weight = struct.unpack_from('<I', data, offset + length)[0]
            check(previous < code and set(code) <= set(string.ascii_lowercase) and weight > 0,
                  f'Invalid or unsorted dictionary record: {name}/{code}')
            previous = code
            records[code] = weight
            candidate = (-weight, code)
            by_length[length] = min(by_length.get(length, candidate), candidate)
            for letter in set(code):
                by_letter[letter] = min(by_letter.get(letter, candidate), candidate)
    check(len(records) == manifest['records'], 'Record count does not match manifest')
    check(total_bytes == manifest['total_bytes'], 'Total bytes do not match manifest')
    check(set(by_letter) == set(string.ascii_lowercase), 'Dictionary must exercise all 26 letters')
    return manifest, records, by_length, by_letter, maximum_shard


def make_cases(adjacent, by_length, by_letter):
    cases = []
    for intended in sorted(adjacent):
        target = by_letter[intended][1]
        position = target.index(intended)
        for typed in adjacent[intended]:
            raw = target[:position] + typed + target[position + 1:]
            cases.append({'kind': 'single', 'edge': intended + '>' + typed,
                          'input': raw, 'target': target, 'edits': 1})
    for length, (_, target) in sorted(by_length.items()):
        # Select both endpoints without consulting the search or hand-picking
        # words. Every indexed length must retrieve a genuine two-edit target.
        raw = adjacent[target[0]][0] + target[1:-1] + adjacent[target[-1]][-1]
        cases.append({'kind': 'double', 'length': length,
                      'input': raw, 'target': target, 'edits': 2})
    # Different lengths exercise eviction/reloading; repeated characters also
    # explore busy prefix ranges without assuming a particular Chinese answer.
    for length in list(range(4, 25)) + list(range(24, 3, -1)):
        cases.append({'kind': 'budget', 'input': 'a' * length, 'target': '', 'edits': 0})
    for raw in ('', 'a', 'abc', 'a' * 25, 'Aaaa', 'ni hao', 'a1aa', "a'aa", '中文'):
        cases.append({'kind': 'scope', 'input': raw, 'target': '', 'edits': 0})
    return cases


def lua_string(value):
    # Lua decimal escapes safely handle any stage path or Unicode invalid-input
    # fixture; JSON's Unicode escapes are not Lua string escapes.
    return '"' + ''.join(f'\\{byte:03d}' for byte in value.encode()) + '"'


class Lua:
    def __init__(self, library):
        self.api = ctypes.CDLL(library)
        self.api.luaL_newstate.restype = ctypes.c_void_p
        self.api.luaL_openlibs.argtypes = [ctypes.c_void_p]
        self.api.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.api.luaL_loadstring.restype = ctypes.c_int
        self.api.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
                                      ctypes.c_int, ctypes.c_ssize_t, ctypes.c_void_p]
        self.api.lua_pcallk.restype = ctypes.c_int
        self.api.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                         ctypes.POINTER(ctypes.c_size_t)]
        self.api.lua_tolstring.restype = ctypes.c_void_p
        self.api.lua_close.argtypes = [ctypes.c_void_p]

    def execute(self, script):
        state = self.api.luaL_newstate()
        check(bool(state), 'Could not create a Lua state')
        try:
            self.api.luaL_openlibs(state)
            status = self.api.luaL_loadstring(state, script.encode())
            if status == 0:
                status = self.api.lua_pcallk(state, 0, 1, 0, 0, None)
            size = ctypes.c_size_t()
            value = self.api.lua_tolstring(state, -1, ctypes.byref(size))
            output = ctypes.string_at(value, size.value).decode() if value else ''
            check(status == 0, f'Actual Lua search failed: {output}')
            return output
        finally:
            self.api.lua_close(state)


def run_lua(stage, cases, library):
    setup = '\n'.join((
        'local module = dofile(' + lua_string(str(stage / 'lua/axiang_typo_index.lua')) + ')',
        'local adjacent = dofile(' + lua_string(str(stage / 'lua/axiang_qwerty_neighbors.lua')) + ')',
        'local index = module.new(' + lua_string(str(stage / 'lua/axiang_typo')) + ')',
        'local cases = {' + ','.join(lua_string(case['input']) for case in cases) + '}',
    ))
    # Assertions here inspect real Lua runtime state, not a Python recreation of
    # the trie search. Candidate validity is checked independently below.
    script = setup + r'''
local output, maximum_nodes, slowest_input = {}, -1, nil
for key, values in pairs(adjacent) do output[#output + 1] = 'GRAPH\t' .. key .. '\t' .. values end
for number, input in ipairs(cases) do
  local before = index.bytes
  local results, stats = module.search(index, input, adjacent)
  stats = stats or {nodes = 0, truncated = false, cache_bytes = index.bytes, seconds = 0}
  local actual_bytes, shards = 0, 0
  for _, value in pairs(index.shards) do actual_bytes = actual_bytes + #value.data; shards = shards + 1 end
  assert(actual_bytes == index.bytes and index.bytes <= 4 * 1024 * 1024, 'cache accounting/budget')
  assert(stats.cache_bytes == index.bytes, 'reported cache differs from retained shards')
  assert(stats.nodes <= 6001 and #results <= 256, 'unbounded search work/results')
  if stats.nodes > maximum_nodes then maximum_nodes, slowest_input = stats.nodes, input end
  local encoded = {}
  for _, result in ipairs(results) do
    encoded[#encoded + 1] = result.code .. ':' .. result.weight .. ':' .. result.edits .. ':' .. result.score
  end
  output[#output + 1] = table.concat({'QUERY', number, #results, stats.nodes,
    stats.truncated and 1 or 0, stats.cache_bytes, string.format('%.9f', stats.seconds),
    before, shards, table.concat(encoded, ',')}, '\t')
end
-- Force the actual elapsed-time guard independently of host speed. Select a
-- naturally busy query observed above, so it reaches the 64-node clock check.
assert(maximum_nodes >= 64, 'dictionary fixtures did not exercise the time guard')
local real_clock, ticks = os.clock, 0
os.clock = function() ticks = ticks + 1; return ticks * .010 end
local ok, results, stats = pcall(module.search, index, slowest_input, adjacent)
os.clock = real_clock
assert(ok, results)
assert(stats.truncated and stats.nodes == 64, 'elapsed-time guard did not stop at first overdue check')
output[#output + 1] = table.concat({'CLOCK_GUARD', stats.nodes, #results, ticks}, '\t')
collectgarbage('collect')
output[#output + 1] = 'LUA_HEAP_KIB\t' .. collectgarbage('count')
return table.concat(output, '\n')
'''
    return Lua(library).execute(script)


def verify_report(output, cases, adjacent, records):
    graph, queries, clock_guard, heap = {}, [], None, None
    for line in output.splitlines():
        columns = line.split('\t')
        if columns[0] == 'GRAPH':
            graph[columns[1]] = ''.join(sorted(set(columns[2]) - {columns[1]}))
        elif columns[0] == 'CLOCK_GUARD':
            clock_guard = {'nodes': int(columns[1]), 'results': int(columns[2]), 'clock_reads': int(columns[3])}
        elif columns[0] == 'LUA_HEAP_KIB':
            heap = float(columns[1])
        else:
            check(columns[0] == 'QUERY' and len(columns) == 10, f'Bad Lua report row: {line}')
            case = cases[int(columns[1]) - 1]
            results = []
            for encoded in filter(None, columns[9].split(',')):
                code, weight, edits, score = encoded.split(':')
                weight, edits, score = int(weight), int(edits), float(score)
                raw = case['input']
                differences = [(a, b) for a, b in zip(raw, code) if a != b]
                check(len(code) == len(raw) and 1 <= len(differences) <= 2,
                      f'Non-substitution result for {raw}: {code}')
                check(edits == len(differences) and all(b in adjacent[a] for a, b in differences),
                      f'Non-neighbour or incorrect edit distance: {raw}/{code}')
                check(records.get(code) == weight and math.isfinite(score), f'Non-dictionary candidate: {code}')
                results.append(code)
            check(len(set(results)) == len(results) == int(columns[2]), 'Duplicate/miscounted Lua results')
            rank = results.index(case['target']) + 1 if case['target'] in results else None
            check(not case['target'] or rank is not None,
                  f"Dictionary-derived {case['kind']} repair was not retrieved: {case['input']} -> {case['target']}")
            if case['kind'] == 'scope':
                check(not results and int(columns[5]) == int(columns[7]), 'Out-of-scope input read/changed the index')
            seconds = float(columns[6])
            check(math.isfinite(seconds) and seconds >= 0, 'Invalid CPU timing')
            queries.append({**case, 'target_rank': rank, 'results': len(results), 'nodes': int(columns[3]),
                            'truncated': columns[4] == '1', 'cache_bytes': int(columns[5]),
                            'cpu_ms': round(seconds * 1000, 6), 'cached_shards': int(columns[8])})
    check(graph == adjacent, 'Production neighbour graph differs from independent QWERTY geometry')
    check(len(queries) == len(cases) and clock_guard is not None, 'Incomplete actual-Lua execution')
    return queries, clock_guard, heap


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--lua-lib', help='Optional liblua5.4 shared library path')
    args = parser.parse_args()
    stage = args.stage.resolve()
    library = args.lua_lib or ctypes.util.find_library('lua5.4')
    check(bool(library), 'liblua5.4 is required (the Lua executable is not required)')
    manifest, records, by_length, by_letter, maximum_shard = read_index(stage)
    adjacent = qwerty_edges()
    cases = make_cases(adjacent, by_length, by_letter)
    queries, clock_guard, heap = verify_report(run_lua(stage, cases, library), cases, adjacent, records)
    measured = sorted(query['cpu_ms'] for query in queries if query['kind'] != 'scope')
    singles = [query for query in queries if query['kind'] == 'single']
    report = {
        'status': 'passed', 'runtime': 'actual packaged Lua 5.4 modules via ctypes',
        'selection': 'Highest source-weight indexed spelling containing each intended letter; endpoint mutations at every indexed length. No word allowlist or search-informed sample selection.',
        'scope': 'Index retrieval only; all directed geometric neighbour substitutions sampled once, plus two substitutions per indexed length. This does not establish complete vocabulary recall, native candidate ranking, or Android touch/latency accuracy.',
        'source_sha256': manifest['sources_sha256'],
        'tested_lua_sha256': {name: digest(stage / 'lua' / name) for name in
                              ('axiang_typo_index.lua', 'axiang_qwerty_neighbors.lua')},
        'records': len(records),
        'total_index_bytes': manifest['total_bytes'], 'maximum_shard_bytes': maximum_shard,
        'directed_neighbour_pairs': len(singles), 'letters_covered': len(adjacent),
        'cross_row_pairs': sum(1 for query in singles if not any(all(letter in row for letter in query['edge'].split('>'))
                                                             for row in ('qwertyuiop', 'asdfghjkl', 'zxcvbnm'))),
        'double_substitution_lengths': sorted(by_length), 'queries': len(queries),
        'limits': {'cache_bytes': MAX_CACHE, 'nodes': MAX_NODES, 'results': MAX_RESULTS, 'target_cpu_ms': 6},
        'maximum_retained_cache_bytes': max(query['cache_bytes'] for query in queries),
        'lua_heap_after_gc_kib': heap, 'maximum_nodes': max(query['nodes'] for query in queries),
        'truncated_queries': sum(query['truncated'] for query in queries),
        'cpu_ms': {'median': statistics.median(measured), 'p95': measured[math.ceil(len(measured) * .95) - 1],
                   'maximum': max(measured), 'over_target': sum(value > 6 for value in measured)},
        'timing_note': 'Real host CPU timings include cold shard loading; the 6ms search guard is polled every 64 nodes. Hardware-sensitive timings are reported, while its termination is also tested with a controlled clock.',
        'controlled_clock_guard': clock_guard, 'cases': queries,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'AXiang typo index verification failed: {error}', file=sys.stderr)
        raise SystemExit(1)
