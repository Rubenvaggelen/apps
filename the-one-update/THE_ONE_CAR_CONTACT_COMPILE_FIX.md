# The One Car – contact compile fix

Fixed Kotlin compilation errors in `RadioContactStore.kt` caused by predicate-style `removeAll { ... }` calls on pending/allowed contact sets.

Changes:
- use explicit `MutableSet<String>` instances
- remove case-insensitive matches with simple loops + `remove()`
- preserve offline on/off contact selection and later sync
- keep the Radio tile and station screen from the previous build

Validation performed in the working environment:
- no predicate-style `removeAll { ... }` remains in the carradio Kotlin sources
- `RadioContactStore.kt` + `ContactAliases.kt` compile successfully with Kotlin against Android API stubs
- Android XML files parse successfully
- output ZIP integrity checked
