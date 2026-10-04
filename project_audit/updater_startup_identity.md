# Client startup identity correction

The prior634b client-only update attempt did not produce a proven loaded634b game. The helper restored716 and requested launcher recovery after classifying a Java child as dead before readiness. Available game logs did not establish a new Voxy renderer or mixin crash. A short-lived launcher Java probe was a plausible cause of mistaken process identity, not a confirmed fatal cause.

The current helper no longer enumerates the host process tree to infer a game. Voxy writes `.voxy/updater/starting` atomically from its mod constructor with its own PID, start instant, launcher-parent PID and installed content hash. The helper clears old starting/readiness records after the old game's verified exit and artifact installation, before opening the official Modrinth launch URI. It then watches only the explicitly announced PID/start identity. A genuinely announced game that dies before readiness triggers restoration of the previous verified artifact and official launcher recovery.

The helper intentionally does not guess an identity for a process that fails before the Voxy constructor. Such a pre-bootstrap failure requires diagnosis or recovery over independent SSH. There is no newly invented timeout that turns an observation gap into a crash, and no access to authentication material or Minecraft JVM arguments.

Offline client compilation and class inspection passed. Frozen6bef920dda9f3497a75b5d961c0531b05ed57837f5d09b50f3c4af42d13052ec includes the startup fix, validated weighted-model/outward-face meshing, synchronized VRAM retirement, and native cutout-mipped alpha behavior. Its12 shared protocol classes are byte-identical to current716 and actual server867. The newer server/native candidates remain unpublished during the existing pressure run. Publication, actual startup, readiness and successful terrain rendering remain separate live gates.

The private live observer must open status/identity/artifact files with Windows delete sharing so observation cannot prevent an atomic replacement. Public process trace availability is limited by the user's Windows permissions; no elevation or protected argument inspection is required.
