# resources: NOT-YET-IMPLEMENTED (steps MC4 and MC5)

Places for the bundle inputs of macos.md 3.2 and 8.1. Every file here is a marked stub: nothing here is used by any build, nothing is
signed, nothing registers a service. The Team ID is an OWNER decision (M-D10) and appears nowhere in the repository; where a file needs it,
the text says `OWNER-FILL` and a later signing step substitutes it at sign time.

| file | step | state |
|---|---|---|
| `Info.plist.template` | MC4 | stub |
| `xyz.mdhv.asom.node.plist` (LaunchAgent, mode A) | MC4 | stub |
| `xyz.mdhv.asom.noded.plist` (LaunchDaemon, mode B) | MC9 (M2), excluded from the bundle until then | stub |
| `entitlements/node.entitlements` | MC5 | stub |
| `entitlements/jvm-launcher.entitlements` | MC5 | stub |
| `entitlements/helper.entitlements` | MC5 | the spec fixes it (an empty dictionary); unused until MC5 |
| `distribution.xml` | MC5 | stub |
| `ASOM.icns` | MC5 | not created (no visual design ambition, Invariant 7) |
