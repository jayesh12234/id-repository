# Optional local jars

`kernel-auth-adapter.jar` was required when the stack ran a real `datashare-service`.
Data Share is now **static WireMock stubs** in `mappings/id-repository.json` (no Java transformer), so this folder is **optional** for the laptop stack.

Keep a copy here only if you still want to run a real data-share image outside compose.
