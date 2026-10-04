"""Preserve and inspect hash-verified Testing terrain records from a real render ray."""

import argparse
import collections
import datetime
import hashlib
import itertools
import json
import math
from pathlib import Path
import struct
from typing import NamedTuple
import zlib


PROJECT = Path(__file__).resolve().parents[2]
RECORDS = Path("/home/aerosmp/Desktop/Voxy_Testing/.voxy/terrain/server/records")
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
SIDES = ((-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1))


class NbtArray(NamedTuple):
    tag: int
    values: tuple


class NbtReader:
    def __init__(self, data):
        self.data, self.offset = memoryview(data), 0

    def take(self, length):
        if length < 0 or length > len(self.data) - self.offset:
            raise ValueError("Truncated NBT value")
        start = self.offset
        self.offset += length
        return self.data[start : self.offset]

    def number(self, code):
        return struct.unpack(">" + code, self.take(struct.calcsize(code)))[0]

    def string(self):
        # Java NBT strings use modified UTF-8, including encoded nulls and surrogate pairs.
        value = bytes(self.take(self.number("H"))).replace(b"\xc0\x80", b"\0")
        text = value.decode("utf-8", "surrogatepass")
        return text.encode("utf-16-le", "surrogatepass").decode(
            "utf-16-le", "surrogatepass"
        )

    def value(self, tag):
        if 1 <= tag <= 6:
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[tag])
        if tag in (7, 11, 12):
            count = self.number("i")
            code = {7: "b", 11: "i", 12: "q"}[tag]
            raw = self.take(count * struct.calcsize(code))
            return NbtArray(tag, struct.unpack(">" + str(count) + code, raw))
        if tag == 8:
            return self.string()
        if tag == 9:
            child, count = self.number("B"), self.number("i")
            if (
                count < 0
                or count > len(self.data) - self.offset
                or child > 12
                or child == 0
                and count
            ):
                raise ValueError("Invalid NBT list")
            return [self.value(child) for _ in range(count)]
        if tag == 10:
            result = {}
            while (child := self.number("B")) != 0:
                name = self.string()
                if name in result:
                    raise ValueError("Duplicate NBT compound field")
                result[name] = self.value(child)
            return result
        raise ValueError("Unknown NBT tag " + str(tag))

    def root(self):
        if self.number("B") != 10:
            raise ValueError("Terrain root must be an NBT compound")
        self.string()
        result = self.value(10)
        if self.offset != len(self.data):
            raise ValueError("Trailing NBT bytes")
        return result


class Frame:
    def __init__(self, record):
        payload = record[32:]
        self.digest = hashlib.sha256(payload).hexdigest()
        if len(record) <= 32 or record[:32].hex() != self.digest:
            raise ValueError("Record digest does not match payload")
        inflater = zlib.decompressobj()
        raw = inflater.decompress(payload) + inflater.flush()
        if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
            raise ValueError("Incomplete or trailing compressed terrain bytes")
        tag = NbtReader(raw).root()
        palette = tag.get("palette")
        if not isinstance(palette, list) or not 1 <= len(palette) <= 34**3:
            raise ValueError("Invalid terrain palette")
        self.palette = []
        for index, item in enumerate(palette):
            state, biome, light = item["state"], item["biome"], item["light"]
            if not isinstance(state, dict) or not isinstance(state.get("Name"), str):
                raise ValueError("Invalid palette block state")
            properties = state.get("Properties", {})
            if not isinstance(properties, dict) or any(
                not isinstance(v, str) for v in properties.values()
            ):
                raise ValueError("Invalid block state properties")
            if (
                not isinstance(biome, str)
                or not biome
                or type(light) is not int
                or not -128 <= light <= 127
            ):
                raise ValueError("Invalid biome or light")
            fluid = (
                state["Name"]
                if state["Name"] in ("minecraft:water", "minecraft:lava")
                else None
            )
            if properties.get("waterlogged") == "true":
                fluid = "minecraft:water"
            self.palette.append(
                dict(
                    index=index,
                    state=state,
                    biome=biome,
                    blockLight=light & 15,
                    skyLight=(light & 255) >> 4,
                    fluidHint=fluid,
                )
            )
        self.bits = max(1, (len(palette) - 1).bit_length())
        self.per_word = 64 // self.bits
        data = tag.get("data")
        if not isinstance(data, NbtArray) or data.tag != 12:
            raise ValueError("Terrain data must be an NBT long array")
        self.data = data.values
        if len(self.data) != math.ceil(34**3 / self.per_word):
            raise ValueError("Invalid padded terrain data length")
        for index in range(34**3):
            if self.index(index) >= len(palette):
                raise ValueError("Terrain palette index out of range")
        if type(tag.get("children")) is not int or not -128 <= tag["children"] <= 127:
            raise ValueError("Invalid terrain children mask")
        if not isinstance(tag.get("entities"), list) or any(
            not isinstance(entity, dict) for entity in tag["entities"]
        ):
            raise ValueError("Invalid terrain entity list")
        self.children = tag["children"] & 255

    def index(self, offset):
        word, slot = divmod(offset, self.per_word)
        return (self.data[word] >> (slot * self.bits)) & ((1 << self.bits) - 1)

    def cell(self, point):
        if any(value < -1 or value > 32 for value in point):
            return None
        x, y, z = point
        return self.index(x + 1 + 34 * (z + 1) + 1156 * (y + 1))


def relative(key):
    level, x, y, z = key
    if (
        len(key) != 4
        or any(type(value) is not int for value in key)
        or not 0 <= level <= 4
    ):
        raise ValueError("Invalid inspection key")
    shift = 4 - level
    return (
        Path(f"r_{x >> shift}_{y >> shift}_{z >> shift}") / f"{level}_{x}_{y}_{z}.vxs"
    )


def ray_cells(key, start, end):
    scale = 1 << key[0]
    low = [value * 32 * scale for value in key[1:]]
    length = math.dist(start, end)
    direction = [(b - a) / length for a, b in zip(start, end)]
    enter, leave = 0.0, length
    for axis, delta in enumerate(direction):
        if delta:
            edges = [
                (low[axis] + side * 32 * scale - start[axis]) / delta for side in (0, 1)
            ]
            enter, leave = max(enter, min(edges)), min(leave, max(edges))
        elif not low[axis] <= start[axis] <= low[axis] + 32 * scale:
            return
    if enter >= leave:
        return
    # The slab interval proves entry lies in this core; floating reconstruction may
    # land outside by several ULPs. Clamp its first cell to that exact geometric domain.
    cell = [
        min(
            31,
            max(
                0,
                math.floor(
                    (math.nextafter(a + enter * d, a + enter * d + d) - base) / scale
                ),
            ),
        )
        for a, d, base in zip(start, direction, low)
    ]
    while all(0 <= value < 32 for value in cell) and enter < leave:
        yield tuple(cell), enter
        next_distance = [
            (low[i] + (cell[i] + (d > 0)) * scale - start[i]) / d if d else math.inf
            for i, d in enumerate(direction)
        ]
        enter = min(next_distance)
        for axis, distance in enumerate(next_distance):
            if distance == enter:
                cell[axis] += 1 if direction[axis] > 0 else -1


def sample(frame, key, point):
    index = frame.cell(point)
    scale = 1 << key[0]
    return dict(
        cell=point,
        worldLow=[(k * 32 + p) * scale for k, p in zip(key[1:], point)],
        paletteIndex=index,
    )


def describe(frame, key, inspection):
    ray = [
        dict(sample(frame, key, cell), distance=distance)
        for cell, distance in ray_cells(
            key, inspection["rayStart"], inspection["rayEnd"]
        )
    ]
    groups = {}
    for axis in range(3):
        for sign in (-1, 1):
            tangents = [i for i in range(3) if i != axis]
            for u, v in itertools.product(range(32), repeat=2):
                point = [0, 0, 0]
                point[axis], point[tangents[0]], point[tangents[1]] = (
                    (0 if sign < 0 else 31),
                    u,
                    v,
                )
                neighbor = point.copy()
                neighbor[axis] += sign
                own, other = frame.cell(point), frame.cell(neighbor)
                a, b = frame.palette[own], frame.palette[other]
                if a["state"]["Name"] in AIR:
                    continue
                identity = (axis, sign, own, other)
                if identity not in groups:
                    groups[identity] = dict(
                        axis=axis,
                        sign=sign,
                        corePalette=own,
                        haloPalette=other,
                        count=0,
                        example=sample(frame, key, point),
                    )
                groups[identity]["count"] += 1
    locations = {tuple(ray[index]["cell"]) for index in (0, -1)} if ray else set()
    preceding_fluid, occupied = None, False
    for value in ray:
        entry = frame.palette[value["paletteIndex"]]
        if not occupied and entry["state"]["Name"] not in AIR:
            locations.add(tuple(value["cell"]))
            occupied = True
        if entry["fluidHint"] != preceding_fluid:
            locations.add(tuple(value["cell"]))
        preceding_fluid = entry["fluidHint"]
    stencils = [
        dict(
            center=point,
            cells=[
                sample(frame, key, tuple(a + b for a, b in zip(point, offset)))
                for offset in itertools.product((-1, 0, 1), repeat=3)
            ],
        )
        for point in sorted(locations)
    ]
    return dict(
        children=frame.children,
        palette=frame.palette,
        rayCells=ray,
        boundaryTransitions=list(groups.values()),
        stencils=stencils,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inspection", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--records", type=Path, required=True)
    args = parser.parse_args()
    source, output = args.records.resolve(), args.output.resolve()
    try:
        source.relative_to(RECORDS.resolve())
    except ValueError:
        # A preserved snapshot supports reanalysis without reading the live server again.
        source.relative_to(PROJECT / "project_audit" / "live_client")
    output.relative_to(PROJECT / "project_audit" / "live_client")
    inspection_bytes = args.inspection.read_bytes()
    inspection = json.loads(inspection_bytes)
    image = args.inspection.with_name(
        args.inspection.name.removesuffix(".inspection.json") + ".png"
    )
    png = image.read_bytes()
    if png[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", png[16:24]) != (
        inspection["targetWidth"],
        inspection["targetHeight"],
    ):
        raise ValueError("Screenshot physical dimensions differ from inspection")
    requests = {}

    def request(key, role, owner=None):
        key = tuple(key)
        relative(key)
        item = requests.setdefault(
            key, dict(key=key, roles=[], geometryHashes=[], raySelected=False)
        )
        if role not in item["roles"]:
            item["roles"].append(role)
        if owner:
            item["raySelected"] |= role == "ray-hit"
            digest = owner.get("geometryHash")
            if digest and digest not in item["geometryHashes"]:
                item["geometryHashes"].append(digest)

    for hit in inspection["hits"]:
        request(hit["key"], "ray-hit", hit)
        for parent in hit["parents"]:
            request(parent["key"], "parent", parent)
        level, x, y, z = hit["key"]
        for dx, dy, dz in SIDES:
            request((level, x + dx, y + dy, z + dz), "same-level-neighbor")
    output.mkdir(parents=True, exist_ok=False)
    manifest, details = [], []
    for key, item in sorted(requests.items()):
        path = source / relative(key)
        item["source"] = str(path)
        item["selectedOwnership"] = "ray-selected" if item["raySelected"] else "unknown"
        try:
            record = path.read_bytes()
            item.update(
                recordSha256=hashlib.sha256(record).hexdigest(),
                bytes=len(record),
                headerSha256=record[:32].hex(),
                payloadSha256=hashlib.sha256(record[32:]).hexdigest(),
            )
            destination = output / "records" / relative(key)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(record)
            frame = Frame(record)
            item["status"] = "verified"
            item["geometryMatch"] = (
                all(value == frame.digest for value in item["geometryHashes"])
                if item["geometryHashes"]
                else None
            )
            if item["geometryHashes"]:
                details.append(
                    dict(
                        key=key,
                        geometryMatch=item["geometryMatch"],
                        **describe(frame, key, inspection),
                    )
                )
        except FileNotFoundError:
            item["status"] = "missing"
        except (
            OSError,
            ValueError,
            KeyError,
            TypeError,
            struct.error,
            zlib.error,
        ) as failure:
            item.update(status="invalid", error=str(failure))
        manifest.append(item)
    receipt = dict(
        timeUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        toolSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        inspection=str(args.inspection.resolve()),
        inspectionSha256=hashlib.sha256(inspection_bytes).hexdigest(),
        screenshot=str(image.resolve()),
        screenshotSha256=hashlib.sha256(png).hexdigest(),
        pixel=inspection["pixel"],
        target=[inspection["targetWidth"], inspection["targetHeight"]],
        records=manifest,
        limitations="Ray hits are section AABBs, not mesh intersections. Server-neighbor existence does not prove selected ownership. fluidHint recognizes vanilla water/lava and waterlogged states only. No block entity NBT is reported.",
    )
    (output / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    (output / "samples.json").write_text(json.dumps(details, indent=2) + "\n")
    print(
        json.dumps(
            dict(
                output=str(output),
                records=len(manifest),
                statuses=dict(collections.Counter(item["status"] for item in manifest)),
                geometryMismatches=[
                    item["key"]
                    for item in manifest
                    if item.get("geometryMatch") is False
                ],
            )
        )
    )


if __name__ == "__main__":
    main()
