#!/usr/bin/env python3
"""Read-only, symmetric physical-source ledger; never imports/runs project code."""
from pathlib import Path
import json
import re
import sys

SOURCE = {'.java', '.rs', '.comp', '.glsl', '.vsh', '.fsh', '.vert', '.frag'}
TOOL_SOURCE = SOURCE | {'.py', '.sh', '.ps1'}
GENERATED = {'build', 'target', '.gradle', '__pycache__', '.pytest_cache', '.git',
             '.idea', 'out', 'node_modules', 'runtime'}
RUST_FIXTURES = {'faults.rs', 'refresh_measurements.rs'}


def scrub_rust(text):
    """Preserve character/line offsets, blank comments and string/char literals."""
    out = list(text)
    i = 0

    def blank(start, end):
        for position in range(start, end):
            if out[position] != '\n':
                out[position] = ' '

    while i < len(text):
        if text.startswith('//', i):
            end = text.find('\n', i)
            end = len(text) if end < 0 else end
        elif text.startswith('/*', i):
            end, depth = i + 2, 1
            while end < len(text) and depth:
                if text.startswith('/*', end):
                    depth += 1
                    end += 2
                elif text.startswith('*/', end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
        elif raw := re.match(r'(?:br|r)(#+)?"', text[i:]):
            marker = '"' + (raw.group(1) or '')
            end = text.find(marker, i + len(raw.group()))
            end = len(text) if end < 0 else end + len(marker)
        elif text[i] == '"':
            end = i + 1
            while end < len(text):
                if text[end] == '\\':
                    end += 2
                elif text[end] == '"':
                    end += 1
                    break
                else:
                    end += 1
        elif text[i] == "'" and (char := re.match(r"'(?:\\.|[^'\\])'", text[i:])):
            end = i + len(char.group())
        else:
            i += 1
            continue
        blank(i, end)
        i = end
    return ''.join(out)


def gated_test_ranges(text):
    """Audit this checkout's explicit #[cfg(test)] items, not arbitrary Rust cfg."""
    clean = scrub_rust(text)
    ranges, covered_until = [], -1
    for match in re.finditer(r'#\[cfg\(test\)\]', clean):
        if match.start() < covered_until:
            continue
        position = match.end()
        while True:
            while position < len(clean) and clean[position].isspace():
                position += 1
            if not clean.startswith('#[', position):
                break
            depth, position = 1, position + 2
            while depth and position < len(clean):
                if clean[position] == '[':
                    depth += 1
                elif clean[position] == ']':
                    depth -= 1
                position += 1
        item_start = position
        parens = brackets = 0
        while position < len(clean):
            char = clean[position]
            if char == '(':
                parens += 1
            elif char == ')':
                parens -= 1
            elif char == '[':
                brackets += 1
            elif char == ']':
                brackets -= 1
            elif not parens and not brackets and char in '{;':
                break
            position += 1
        if position == len(clean):
            raise ValueError('Cannot find end of an explicit cfg(test) item')
        if clean[position] == '{':
            depth, position = 1, position + 1
            while depth and position < len(clean):
                if clean[position] == '{':
                    depth += 1
                elif clean[position] == '}':
                    depth -= 1
                position += 1
            if depth:
                raise ValueError('Unterminated cfg(test) body')
        else:
            position += 1
        first = text.count('\n', 0, match.start()) + 1
        last = text.count('\n', 0, position - 1) + 1
        ranges.append({'first': first, 'last': last,
                       'item': clean[item_start:position].split('\n')[0].strip()})
        covered_until = position
    return ranges


def ledger(root):
    result = {}

    def count(roots, predicate=lambda path: True, extensions=SOURCE, prune=False):
        files, directories = [], set()
        for relative in roots:
            path = root / relative
            if not path.is_dir():
                continue
            directories.add(path)
            for entry in path.rglob('*'):
                if prune and any(part in GENERATED for part in entry.relative_to(path).parts):
                    continue
                if entry.is_dir():
                    directories.add(entry)
                elif entry.is_file() and entry.suffix in extensions and predicate(entry):
                    files.append(entry)
        return {'files': len(files), 'lines': sum(len(path.read_bytes().splitlines()) for path in files),
                'directories': len(directories),
                'empty_directories': sum(not any(path.iterdir()) for path in directories)}, files

    main_roots = ['src/main', 'server/src/main', 'rust-server/src']
    result['plan_main_paths'], _ = count(main_roots)
    for name, roots in [('client_main', ['src/main']), ('server_main', ['server/src/main']),
                        ('rust_main_paths', ['rust-server/src']),
                        ('debug', ['src/debug', 'server/src/debug']),
                        ('java_tests', ['src/schedulerTest', 'server/src/lifecycleTest'])]:
        result[name], _ = count(roots)
    is_fixture = lambda path: path.name.endswith('_tests.rs') or path.name in RUST_FIXTURES
    result['rust_test_files'], _ = count(['rust-server/src'], is_fixture)
    result['native_pressure'], _ = count(['rust-server/src'], lambda path: path.name == 'live_pressure.rs')
    result['main_without_standalone_tests_pressure'], production = count(
        main_roots, lambda path: not is_fixture(path) and path.name != 'live_pressure.rs')
    ranges = {str(path.relative_to(root)): gated_test_ranges(path.read_text())
              for path in production if path.suffix == '.rs'}
    result['embedded_rust_tests'] = {
        'lines': sum(item['last'] - item['first'] + 1 for items in ranges.values() for item in items),
        'ranges': {path: items for path, items in ranges.items() if items}}
    result['release_source'] = dict(result['main_without_standalone_tests_pressure'])
    result['release_source']['lines'] -= result['embedded_rust_tests']['lines']
    for name, predicate in [('tools_runtime', lambda path: not path.name.startswith('test_')),
                            ('tools_tests', lambda path: path.name.startswith('test_')),
                            ('tools_all', lambda path: True)]:
        result[name], _ = count(['tools'], predicate, TOOL_SOURCE, True)
    groups = ['plan_main_paths', 'debug', 'java_tests', 'tools_all']
    result['maintained_all_sources'] = {field: sum(result[group][field] for group in groups)
                                       for field in ['files', 'lines', 'directories', 'empty_directories']}
    resource_files = [path for relative in ['src/main', 'server/src/main', 'src/debug', 'server/src/debug']
                      for path in (root / relative).rglob('*') if path.is_file() and path.suffix not in SOURCE]
    result['resources'] = {'files': len(resource_files),
                           'text_lines': sum(len(path.read_bytes().splitlines())
                                             for path in resource_files if path.suffix != '.png'),
                           'bytes': sum(path.stat().st_size for path in resource_files)}
    return result


paths = sys.argv[1:] or ['/home/aerosmp/Desktop/ASMP_Voxy_Restart',
                          '/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates']
print(json.dumps({str(Path(path).resolve()): ledger(Path(path)) for path in paths}, indent=2))
