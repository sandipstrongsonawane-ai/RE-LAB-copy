# APK Static Analysis

Offline, read-only APK inventory: ZIP entries, DEX names/version headers, native-library ABIs, resource counts, and text or binary Android manifest package/version/permissions/components. Binary Android XML decoding extracts supported manifest metadata without extracting archive entries or executing app code.

## Security review limits
- Selected sensitive permissions and exported components without component-level permissions produce heuristic review prompts, not vulnerability verdicts.
- Component-level permission metadata is reported; application-level permission inheritance and Android runtime authorization still require contextual review.
- Malformed or unsupported binary manifest data is reported as a decode warning, never silently treated as a clean manifest.
- DEX decompilation, native disassembly, signature verification, resource decoding, runtime instrumentation and execution are not implemented.
- Archive-entry and manifest-size limits protect the read-only inspection path.
- Runtime analysis must only target apps/devices the operator owns or is authorized to test.

Run: `gradle :apk-analysis:test`.
