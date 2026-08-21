# Vendored Google protobuf definitions

These are upstream Google `.proto` definitions, kept here only so the generated classes under
`app/src/main/java/com/google/` can be regenerated and audited. Nothing in the app imports these
files directly, and the build does not compile them — the generated Java is checked in. (They live
here rather than beside `listentogether.proto`, which is inside the `metroproto` submodule and so
belongs to a different repository.)

## Why they exist

`firebase-firestore` needs `com.google.type.LatLng` and `com.google.rpc.Status` at runtime:

| class | referenced from | reached by |
|---|---|---|
| `com.google.type.LatLng` | `com.google.firestore.v1.Value` | any field value — reads, writes, queries |
| `com.google.rpc.Status` | `com.google.firestore.v1.TargetChange` | any snapshot listener |

Both ship **only** in `com.google.firebase:protolite-well-known-types`, which the app excludes from
`firebase-firestore` in `app/build.gradle.kts`. That exclusion is not optional:

- `protolite-well-known-types` bundles a 2021-era copy of `com.google.protobuf.DescriptorProtos`
  (101 classes) that collides with `protobuf-javalite`, and duplicate classes fail dexing.
- The collision cannot be resolved from the other side. `protobuf-javalite` supplies the lite
  runtime (`GeneratedMessageLite`, `CodedInputStream`, …) that `protolite-well-known-types` does not
  contain, so it cannot be dropped. It also cannot be pinned to a conflict-free 3.x: NewPipeExtractor
  requires 4.33+, and 4.x gencode hard-fails against a 3.x runtime via
  `RuntimeVersion.validateProtobufGencodeVersion`.
- Bumping `protolite-well-known-types` does not help — 18.0.1 carries the same 101 classes.

Vendoring the two messages sidesteps the conflict entirely: they are the only classes Firestore
needs from that artifact, and neither is in the duplicated package.

`google/protobuf/any.proto` is present **only** to resolve `status.proto`'s import. It is not
generated — `com.google.protobuf.Any` already comes from `protobuf-javalite`.

## Regenerating

The gencode must be **lite** (`extends GeneratedMessageLite`). `option optimize_for = LITE_RUNTIME`
is silently ignored by protoc 35; the `lite:` output prefix is what actually selects it. Full-runtime
gencode (`extends GeneratedMessage`) compiles but fails at runtime against javalite.

Use the protoc the build already downloads, so the gencode version matches the resolved runtime
(`protobuf` in `gradle/libs.versions.toml`). Run from the repository root:

```bash
PROTOC=app/build/protoc/protoc-4.35.1-windows-x86_64.exe
"$PROTOC" -I app/src/main/proto --java_out=lite:app/src/main/java \
    app/src/main/proto/google/type/latlng.proto \
    app/src/main/proto/google/rpc/status.proto
```

Regenerate when `protobuf` is upgraded. A runtime **newer** than the gencode is fine; a runtime older
than the gencode fails fast with a clear `RuntimeVersion` error naming both versions.
