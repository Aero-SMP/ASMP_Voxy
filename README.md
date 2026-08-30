# Voxy

Voxy is an LoD rendering mod for Minecraft 1.21.1 on NeoForge 21.1.229.

This fork includes server-side LOD generation and streaming directly in Voxy.
Clients and servers use the same Voxy jar; no companion mod is required.

Please do not ask for support for this fork in Cortex's server. Use
https://discord.gg/6rH7nzmfg8 instead.

## Building

```shell
./gradlew build
```

The mod JAR is written to `build/libs/`.

## Deploying

Configure the ignored `deploy.properties`, then deploy with a commit message:

```shell
./gradlew deploy "Integrate server LoD streaming"
```

The task commits and pushes pending changes, stops the configured server,
removes existing Voxy jars, installs the new unified jar, restarts the server,
and updates its repository checkout.
