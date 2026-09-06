# User conventions

- “LS” means “latest screenshot.” Find the most recently modified image in
  `/home/printer/screenshots` and inspect it with the image-viewing tool.

## Deployment and verification

- Deployment and testing are standing user-approved parts of implementing plans in this
  repository. Build, deploy and verify the relevant artifacts without asking for repeated
  confirmation. Commit and push completed plans to `main`.
- Use debug artifacts unless the current user request explicitly specifies otherwise.
- This standing approval includes a plan's required scoped server/client restarts and
  regeneration of obsolete, derived Voxy data. Plan wording asking for deployment/testing
  approval does not require asking the user again for these in-scope operations.
- Still resolve and validate exact targets before destructive operations. Preserve source
  world files, catalogs, identities, configuration, client caches and unrelated data unless
  their modification/removal is specifically part of the authorized task. Do not infer
  authorization for unrelated destructive work or bypass tool permission controls.
- Report deployment and actual live-test outcomes separately from build/unit-test results.
  Preserve failed tests and never relax hard safety ceilings or assertions to claim success.

## Phone notifications

- `notify "message"` sends a notification to the user's Android phone and supports Markdown.
- Only send notifications when the user requests one or explicitly asks to be notified
  when work completes.
