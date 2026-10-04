# Initial consolidated client startup failure

The installed fixed client jar had SHA-256 `c7231162b970cb25f58fdc754e6aa13af6f5f28f64e9dd713ecaa2949c1c7d28`. Modrinth's official instance URI was requested at 2026-10-04 00:33:03 UTC. The candidate Java process, PID 4632, started at 00:33:13.6576371 UTC and failed during NeoForge mod initialization at 00:33:34 UTC.

The first fatal Voxy error was `IllegalClassLoadError: com.aerosmp.voxy.client.VoxyClient is in a defined mixin package com.aerosmp.voxy.client.* owned by voxy.mixins.json and cannot be referenced directly`. The same restriction subsequently rejected `VoxyClient$SharedVertices`. The mixin configuration assigned the entire ordinary client package as its mixin package.

No Minecraft game window, fresh terrain status, updater readiness, renderer publication, or QUIC client session was reached. This attempt supplies startup-failure evidence only; it supplies no performance or rendering result. The converted warm cache and original cache/jar remained preserved. Both independent backup SSH helpers remained alive with unchanged PIDs 19292 and 30096.

See `candidate_official_launcher_requested.json`, `candidate_first_live_status_01.json`, and `candidate_launch_inventory.json`. The startup log receipt contains only selected diagnostic messages and public window/process metadata; no JVM launch arguments or authentication fields were captured.
