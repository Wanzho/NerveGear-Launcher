#!/usr/bin/env python3
"""Replace a PE launcher's icon without Windows tools or changing its code.

The original resources (including version and manifest) are retained. A fresh
resource section is appended, and the PE resource directory points to it.
Intended for the unsigned jpackage template in the pinned Windows JDK only.
"""

import struct
from pathlib import Path


def align(value, boundary):
    """Round a byte count up to an executable alignment boundary."""
    return (value + boundary - 1) // boundary * boundary


class PE:
    """Read just the PE headers and resource directory needed for branding."""

    def __init__(self, data):
        self.data = bytearray(data)
        self.pe = struct.unpack_from('<I', data, 0x3C)[0]
        if data[:2] != b'MZ' or data[self.pe:self.pe + 4] != b'PE\0\0':
            raise ValueError('Not a PE executable')
        self.count = struct.unpack_from('<H', data, self.pe + 6)[0]
        self.optional = self.pe + 24
        magic = struct.unpack_from('<H', data, self.optional)[0]
        if magic not in (0x10B, 0x20B):
            raise ValueError('Unsupported PE optional header')
        self.directories = self.optional + (112 if magic == 0x20B else 96)
        self.table = self.optional + struct.unpack_from('<H', data, self.pe + 20)[0]
        self.sections = []
        for i in range(self.count):
            header = self.table + 40 * i
            size, rva, raw_size, raw = struct.unpack_from('<IIII', data, header + 8)
            self.sections.append((rva, max(size, raw_size), raw, raw_size))

    def file_offset(self, rva):
        """Translate an RVA in an existing section into a file offset."""
        for start, size, raw, _ in self.sections:
            if start <= rva < start + size:
                return raw + rva - start
        raise ValueError('Resource RVA is outside the executable sections')

    def resources(self):
        """Decode resources into nested dictionaries with byte-valued leaves."""
        rva, size = struct.unpack_from('<II', self.data, self.directories + 16)
        if not rva or not size:
            return {}
        base = self.file_offset(rva)

        def read_tree(offset, depth=0):
            if depth > 8:
                raise ValueError('Unexpected resource directory depth')
            named, numbered = struct.unpack_from('<HH', self.data, base + offset + 12)
            result = {}
            for index in range(named + numbered):
                key, value = struct.unpack_from('<II', self.data, base + offset + 16 + index * 8)
                if key & 0x80000000:
                    name_offset = base + (key & 0x7FFFFFFF)
                    length = struct.unpack_from('<H', self.data, name_offset)[0]
                    key = self.data[name_offset + 2:name_offset + 2 + length * 2].decode('utf-16le')
                if value & 0x80000000:
                    result[key] = read_tree(value & 0x7FFFFFFF, depth + 1)
                else:
                    address, length, codepage, reserved = struct.unpack_from('<IIII', self.data, base + value)
                    raw = self.file_offset(address)
                    result[key] = (bytes(self.data[raw:raw + length]), codepage, reserved)
            return result

        return read_tree(0)


def resource_blob(tree, section_rva):
    """Encode resource directories, names, descriptors and aligned payloads."""
    blob = bytearray()
    payloads = []

    def reserve(length):
        offset = len(blob)
        blob.extend(b'\0' * length)
        return offset

    def encode_tree(node):
        keys = sorted(node, key=lambda key: (isinstance(key, int), key))
        start = reserve(16 + 8 * len(keys))
        named = sum(isinstance(key, str) for key in keys)
        struct.pack_into('<HH', blob, start + 12, named, len(keys) - named)
        for index, key in enumerate(keys):
            if isinstance(key, str):
                encoded = key.encode('utf-16le')
                name = reserve(2 + len(encoded))
                struct.pack_into('<H', blob, name, len(encoded) // 2)
                blob[name + 2:name + 2 + len(encoded)] = encoded
                key_value = name | 0x80000000
            else:
                key_value = key
            child = node[key]
            if isinstance(child, dict):
                # Directory entries must remain DWORD-aligned after name strings.
                reserve(align(len(blob), 4) - len(blob))
                value = encode_tree(child) | 0x80000000
            else:
                reserve(align(len(blob), 4) - len(blob))
                value = reserve(16)
                payloads.append((value, child))
            struct.pack_into('<II', blob, start + 16 + 8 * index, key_value, value)
        return start

    encode_tree(tree)
    for descriptor, (data, codepage, reserved) in payloads:
        reserve(align(len(blob), 4) - len(blob))
        struct.pack_into('<IIII', blob, descriptor, section_rva + len(blob), len(data), codepage, reserved)
        blob.extend(data)
    return blob


def replace_icon(executable, icon):
    """Brand an unsigned jpackage PE template in place from an ICO file."""
    target = Path(executable)
    pe = PE(target.read_bytes())
    if struct.unpack_from('<II', pe.data, pe.directories + 32) != (0, 0):
        raise ValueError('Refusing to modify an Authenticode-signed executable')
    ico = Path(icon).read_bytes()
    reserved, kind, count = struct.unpack_from('<HHH', ico)
    if reserved or kind != 1 or not count:
        raise ValueError('Not a Windows ICO file')
    tree = pe.resources()
    icon_resources = {}
    group = bytearray(struct.pack('<HHH', 0, 1, count))
    for index in range(count):
        offset = 6 + index * 16
        width, height, colors, zero, planes, bits, size, raw = struct.unpack_from('<BBBBHHII', ico, offset)
        image = ico[raw:raw + size]
        if len(image) != size or raw < 6 + 16 * count:
            raise ValueError('Invalid ICO payload bounds')
        icon_resources[index + 1] = {1033: (image, 0, 0)}
        group.extend(struct.pack('<BBBBHHIH', width, height, colors, zero, planes, bits, size, index + 1))
    tree[3] = icon_resources
    tree[14] = {101: {1033: (bytes(group), 0, 0)}}

    header = pe.table + 40 * pe.count
    first_raw = min(section[2] for section in pe.sections if section[3])
    if header + 40 > first_raw or any(pe.data[header:header + 40]):
        raise ValueError('No spare PE section-header space for icon branding')
    section_align, file_align = struct.unpack_from('<II', pe.data, pe.optional + 32)
    rva = align(max(start + size for start, size, _, _ in pe.sections), section_align)
    raw = align(len(pe.data), file_align)
    blob = resource_blob(tree, rva)
    raw_size = align(len(blob), file_align)
    pe.data.extend(b'\0' * (raw - len(pe.data)))
    pe.data.extend(blob)
    pe.data.extend(b'\0' * (raw_size - len(blob)))
    pe.data[header:header + 40] = struct.pack('<8sIIIIIIHHI', b'.saoicon', len(blob), rva, raw_size, raw, 0, 0, 0, 0, 0x40000040)
    struct.pack_into('<H', pe.data, pe.pe + 6, pe.count + 1)
    struct.pack_into('<I', pe.data, pe.optional + 56, align(rva + len(blob), section_align))
    initialized = struct.unpack_from('<I', pe.data, pe.optional + 8)[0]
    struct.pack_into('<I', pe.data, pe.optional + 8, initialized + raw_size)
    struct.pack_into('<I', pe.data, pe.optional + 64, 0)  # Optional checksum is unused for application EXEs.
    struct.pack_into('<II', pe.data, pe.directories + 16, rva, len(blob))
    verified = PE(pe.data).resources()
    if verified != tree:
        raise ValueError('Resource round-trip verification failed')
    target.write_bytes(pe.data)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('executable', type=Path)
    parser.add_argument('icon', type=Path)
    args = parser.parse_args()
    replace_icon(args.executable, args.icon)
