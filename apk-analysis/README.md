# APK Static Analysis — initial implementation

Offline, read-only APK inventory: ZIP entries, DEX names/version headers, native-library ABIs, resource counts, and text XML manifest package/version/permissions/components.

## Limits
- Binary AndroidManifest.xml is detected but not decoded yet.
- DEX decompilation, native disassembly, signature verification, resource decoding, runtime instrumentation and execution are not implemented.
- The analyzer never extracts files and applies archive-entry and manifest-size limits.
- Runtime analysis added later must only target apps/devices the operator owns or is authorized to test.

Run: `gradle :apk-analysis:test`.
