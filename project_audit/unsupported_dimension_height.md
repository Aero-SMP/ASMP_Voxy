# Unsupported dimension height

Voxy's existing packed section key stores a signed eight-bit Y coordinate for
32-block base sections. Consequently a supported dimension's complete build
height must fit within `[-4096, 4096)` blocks, with a nonempty range.

The Java bridge now logs an explicit error once per unsupported dimension and
omits its metadata and completed-save notifications. It does not clamp heights,
wrap keys, generate partial terrain, or throw from the Minecraft tick. Native
`RegionLayout` independently rejects unrepresentable layouts.

Limitation: the current native manifest lists supported dimensions only. It has
no unsupported-dimension error entry, so whole-world download completion applies
only to supported manifest dimensions and must not be reported as covering a
taller omitted dimension. Such a dimension cannot use Voxy terrain until the
packed coordinate representation is redesigned. Minecraft remains usable.

This change has not been deployed or verified with a tall live dimension.
