# MCSkill launcher backend integration — design

Date: 2026-08-17
Status: Approved by user, ready for implementation planning

## 1. Goal

Add a new account/auth backend to Amethyst-Android that authenticates against
mcskill.net's real launcher gRPC service (`launchernew.mcskill.ru:443`), lets a
logged-in mcskill user browse the server network's available modded "clients"
(prebuilt Minecraft + mod bundles), and download/install one so it launches
through Amethyst's existing profile pipeline like any other version.

This mirrors, in Java/Android, functionality already reverse-engineered and
proven out in four small Python packages the user authored: `auth`, `mcproto`,
`update`, `client` (all on `github.com/Faticc`). `mcproto` wraps three gRPC
services (`AuthService`, `ClientService`, `UpdateService`) defined by protobuf
messages; the generated `_pb2.py` files were decoded to reconstruct the exact
`.proto` schema (see §7) since no `.proto` source was checked into those repos.

## 2. Non-goals (v1)

- **MFA login.** The `auth.proto` `MfaChallenge` message carries no fields and
  the `AuthService` exposes no RPC to submit a code — the reference Python
  client can only report "MFA required" and stop. We do the same: show a
  clear "not supported yet" error rather than inventing UI for a flow the
  server API cannot complete.
- **Server-provided Java runtimes.** `update.proto` exposes
  `GetJavaFileTree`/`DownloadJavaFiles`, but the reference Python client's own
  download helpers never call them. Amethyst already bundles OpenJDK 8/17/21;
  v1 maps a client's `java_version` to the closest bundled runtime instead of
  fetching the server's build.
- **CDN fallback nodes.** `GetFallbackNode` exists in the proto for
  multi-node failover but is unused by the reference client's download paths.
  Always download via the streamed `DownloadFiles`/`DownloadAssetFiles` RPCs
  on the primary channel.
- **iOS.** Android module only (`app_pojavlauncher` + new `:mcskill_client`).

## 3. Why gRPC-over-okhttp, not a hand-rolled client

mcskill's server only exposes gRPC (HTTP/2 + protobuf framing + trailers) —
there is no REST/JSON gateway to fall back to. Amethyst's existing networking
(`java.net.HttpURLConnection`) cannot speak gRPC's framing, and hand-rolling
HTTP/2 + protobuf wire format from scratch is not a realistic option. The only
practical approach is the standard Android gRPC stack:

- `com.google.protobuf:protobuf-javalite`
- `io.grpc:grpc-okhttp`, `io.grpc:grpc-protobuf-lite`, `io.grpc:grpc-stub`
- `com.google.protobuf` Gradle plugin, generating Java stubs at build time
  from checked-in `.proto` files (§7) — no hand-maintained protocol code.

All of these resolve from Maven Central, already a configured repository
(`settings.gradle:12`).

**ALPN/TLS note:** minSdk is 21. grpc-java's own guidance for older Android
versions is to install Conscrypt as the security provider (e.g. in
`Application.onCreate`) so HTTP/2 ALPN negotiation is reliable across device
TLS stacks — skipping this risks silent connection failures on some older
devices. This should be treated as required, not optional, setup.

## 4. Module layout

New Gradle module **`:mcskill_client`**, following the existing pattern of
small standalone modules (`:arc_dns_injector`, `:MioLibPatcher`,
`:forge_installer` in `settings.gradle:23-25`). Contents:

- `src/main/proto/{common,auth,client,update}.proto` — reconstructed schema.
- Generated Java gRPC stubs (build-time, via the protobuf Gradle plugin).
- `McSkillChannel` — owns the `ManagedChannel` and the three service stubs,
  mirroring `mcproto/mcproto/core.py`.
- `McSkillAuth` — `login(username, password)`, `logout(sessionId)`,
  `getProfile(sessionId)`, mirroring `auth/auth/core.py`.
- `McSkillClients` — `getClients(sessionId)`, `getClient(clientId, sessionId)`,
  mirroring `client/client/core.py`.
- `McSkillUpdater` — `getFileTree`, `downloadFiles` (streamed),
  `getAssetFileTree`, `downloadAssetFiles`, mirroring the subset of
  `update/update/core.py` needed per §2's non-goals.

`app_pojavlauncher` depends on `:mcskill_client` the same way it depends on
the other sibling modules.

## 5. Auth integration

- `value/MinecraftAccount.java` gains one new field: `boolean isMcSkill`.
  Profile data (uuid/username/skin) reuses the existing `profileId`,
  `username`, and skin-face fields — populated from the proto's
  `PlayerProfile{uuid, username, skin_url}` the same way Microsoft accounts
  populate them today.
- New `fragments/McSkillLoginFragment.java` (username + password fields,
  mirrors `LocalLoginFragment`'s layout style) is added as a third button on
  `SelectAuthFragment.java` (alongside the existing Microsoft/Local buttons,
  `SelectAuthFragment.java:25-29`).
- New `authenticator/mcskill/McSkillBackgroundLogin.java` mirrors
  `MicrosoftBackgroundLogin.java`'s shape: runs on `PojavApplication.sExecutorService`,
  calls `McSkillAuth.login(...)`, builds/updates the `MinecraftAccount`, and
  bounces the result back to the UI thread via `Tools.runOnUiThread`.
- **Credential storage:** on successful login, the password is encrypted via
  Android Keystore (`androidx.security:security-crypto`'s
  `EncryptedSharedPreferences` — new dependency) and stored separately, keyed
  by username. It is never written into the account's plaintext JSON file and
  never logged.
- **Session refresh:** `com/kdt/mcgui/mcAccountSpinner.java`'s
  `performLogin()` (`:280-296`) gains an `isMcSkill` branch: call
  `GetProfile(sessionId)` first; if the server reports the session invalid,
  silently decrypt the stored password and call `McSkillAuth.login(...)`
  again to mint a fresh session, then persist it. If that also fails (e.g.
  password changed, or the server now demands MFA), fall back to
  `McSkillLoginFragment` with an explanatory dialog — never a silent failure.
- All mcskill calls are gated behind `Tools.isOnline(...)`, matching the
  existing Microsoft refresh path (`mcAccountSpinner.java:282-284`).

## 6. Client browsing & install

- New `fragments/McSkillClientsFragment.java`: a `RecyclerView` +
  `McSkillClientAdapter`, modeled on the existing modpack search screen
  (`SearchModFragment.java`) rather than the bespoke spinner widgets used for
  accounts/versions. Lists `GetClients()` results — title, version,
  description, online count, PVP/PVE badge, wipe date, mod list. Only
  reachable when the active account has `isMcSkill == true`.
- Selecting a client hands off to `mcskill/install/McSkillClientInstaller.java`,
  modeled directly on `modloaders/modpacks/api/ModpackInstaller.installModpack()`
  (`ModpackInstaller.java:30-79`):
  1. `GetClient(clientId)` → `ClientProfile`.
  2. `GetFileTree(clientId)` + `GetAssetFileTree(clientProfile.assets_dir)`.
  3. Diff against what's already on disk, hash-verified (reusing
     `DownloadUtils.ensureSha1`-style retry/verify logic). **Open question
     flagged for implementation:** the exact hash algorithm/byte length isn't
     confirmed yet — the user's own test run (`file_tree.txt`) came back
     empty, so this must be confirmed against a live server response early in
     implementation, not assumed.
  4. Stream missing files via `DownloadFiles`/`DownloadAssetFiles` into
     `Tools.DIR_GAME_HOME/custom_instances/mcskill_<clientId>/`, with
     progress reporting in the same style as `MinecraftDownloader`'s
     `ProgressLayout` feedback.
  5. Synthesize a version JSON (`mainClass`, classpath from `class_path`,
     JVM args from `jvm_args`, asset index from `asset_index`) matching the
     shape `JMinecraftVersionList.Version` expects, under
     `Tools.DIR_HOME_VERSION/mcskill_<clientId>_<version>/`. The Java runtime
     is Amethyst's own bundled JDK matched by `java_version` (per §2).
  6. Create/update a `MinecraftProfile` via `LauncherProfiles`, with `gameDir`
     pointing at the custom instance dir and `lastVersionId` set to the
     synthesized version — exactly how `ModpackInstaller` finishes.
- **Important nuance:** the browse/select screen is deliberately separate
  from the normal Play UI (discovery step). Once installed, though, the
  client becomes an ordinary profile in the regular version/profile list —
  there is no alternate launch code path in the app today
  (`Tools.launchMinecraft`/`generateLaunchClasspath` always read from a
  version JSON on disk), and building a second one would be unjustified
  duplication.

## 7. Reconstructed protobuf schema

Decoded from the serialized `FileDescriptorProto` bytes embedded in
`mcproto/mcproto/protos/{common,auth,client,update}_pb2.py`. To be checked in
verbatim as `.proto` source under `:mcskill_client/src/main/proto/`.

```proto
// common.proto
syntax = "proto3";
package launcher;
option java_package = "net.mcsgroup.launcher.proto";
option java_multiple_files = true;

message PlayerProfile {
  string uuid = 1;
  string username = 2;
  string skin_url = 3;
}

enum IntegrityMode {
  INTEGRITY_MODE_UNSPECIFIED = 0;
  INTEGRITY_MODE_STANDARD = 1;
  INTEGRITY_MODE_BYPASS = 2;
}

enum Platform {
  PLATFORM_UNSPECIFIED = 0;
  PLATFORM_WINDOWS_X64 = 1;
  PLATFORM_WINDOWS_ARM64 = 2;
  PLATFORM_LINUX_X64 = 3;
  PLATFORM_LINUX_ARM64 = 4;
  PLATFORM_MACOS_X64 = 5;
  PLATFORM_MACOS_ARM64 = 6;
}
```

```proto
// auth.proto
syntax = "proto3";
package launcher;
import "common.proto";
option java_package = "net.mcsgroup.launcher.proto";
option java_multiple_files = true;

message GetProfileRequest {}
message GetProfileResponse {
  PlayerProfile profile = 1;
}

message LoginRequest {
  string username = 1;
  string password = 2;
}
message LoginResponse {
  oneof result {
    Session session_data = 3;
    MfaChallenge mfa_required = 4;
  }
}
message Session {
  string id = 1;
  PlayerProfile profile = 2;
}
message MfaChallenge {}

message LogoutRequest {}
message LogoutResponse {}

service AuthService {
  rpc Login(LoginRequest) returns (LoginResponse);
  rpc Logout(LogoutRequest) returns (LogoutResponse);
  rpc GetProfile(GetProfileRequest) returns (GetProfileResponse);
}
```

```proto
// client.proto
syntax = "proto3";
package launcher;
import "common.proto";
option java_package = "net.mcsgroup.launcher.proto";
option java_multiple_files = true;

message GetClientsRequest {}
message GetClientsResponse {
  repeated ClientInfo clients = 1;
}

message ClientInfo {
  int32 id = 1;
  string title = 2;
  string version = 3;
  string description = 4;
  string about_url = 5;
  uint32 online = 6;
  FightMode fight_mode = 7;
  string wipe_date = 8;
  ClientTag tag = 9;
  repeated ModInfo mods = 10;
  bool is_test = 11;
}

message ModInfo {
  repeated string tags = 1;
  int32 sort = 2;
  string name = 3;
}

message GetClientRequest {
  int32 client_id = 1;
}
message GetClientResponse {
  ClientProfile client = 1;
}

message ClientProfile {
  int32 id = 1;
  string version = 2;
  string client_dir = 3;
  string assets_dir = 4;
  string asset_index = 5;
  string java_version = 6;
  int32 minimum_ram = 7;
  int32 recommended_ram = 8;
  string main_class = 9;
  repeated string class_path = 10;
  repeated string jvm_args = 11;
  repeated string client_args = 12;
  IntegrityMode integrity_mode = 13;
  repeated string update_paths = 14;
  repeated string verify_paths = 15;
  repeated string exclusion_paths = 16;
  bytes signature = 17;
}

enum FightMode {
  FIGHT_MODE_UNSPECIFIED = 0;
  PVP = 1;
  PVE = 2;
}

enum ClientTag {
  CLIENT_TAG_UNSPECIFIED = 0;
  NONE = 1;
  WIPE = 2;
  NEW = 3;
  MAINTENANCE = 4;
}

service ClientService {
  rpc GetClients(GetClientsRequest) returns (GetClientsResponse);
  rpc GetClient(GetClientRequest) returns (GetClientResponse);
}
```

```proto
// update.proto
syntax = "proto3";
package launcher;
import "common.proto";
option java_package = "net.mcsgroup.launcher.proto";
option java_multiple_files = true;

message FileTreeRequest {
  int32 client_id = 1;
}
message FileTreeResponse {
  repeated FileNode files = 1;
  optional string node_id = 2;
  optional string base_url = 3;
}
message FileNode {
  string path = 1;
  int64 size = 2;
  bytes hash = 3;
  bool is_directory = 4;
}

message DownloadRequest {
  int32 client_id = 1;
  repeated string paths = 2;
}
message FileChunk {
  string path = 1;
  bytes data = 2;
  bool is_last = 3;
}

message AssetFileTreeRequest {
  string asset_dir = 1;
}
message AssetDownloadRequest {
  string asset_dir = 1;
  repeated string paths = 2;
}

message JavaFileTreeRequest {
  string java_version = 1;
  Platform platform = 2;
}
message JavaDownloadRequest {
  string java_version = 1;
  Platform platform = 2;
  repeated string paths = 3;
}

message GetFallbackNodeRequest {
  repeated string excluded_node_ids = 1;
  oneof target {
    FallbackClient client = 2;
    FallbackAsset asset = 3;
    FallbackJavaRuntime java_runtime = 4;
  }
}
message FallbackClient { int32 client_id = 1; }
message FallbackAsset { string asset_dir = 1; }
message FallbackJavaRuntime {
  string java_version = 1;
  Platform platform = 2;
}
message GetFallbackNodeResponse {
  string base_url = 1;
  string node_id = 2;
}

service UpdateService {
  rpc GetFileTree(FileTreeRequest) returns (FileTreeResponse);
  rpc DownloadFiles(DownloadRequest) returns (stream FileChunk);
  rpc GetAssetFileTree(AssetFileTreeRequest) returns (FileTreeResponse);
  rpc DownloadAssetFiles(AssetDownloadRequest) returns (stream FileChunk);
  rpc GetJavaFileTree(JavaFileTreeRequest) returns (FileTreeResponse);
  rpc DownloadJavaFiles(JavaDownloadRequest) returns (stream FileChunk);
  rpc GetFallbackNode(GetFallbackNodeRequest) returns (GetFallbackNodeResponse);
}
```

## 8. Error handling

- gRPC failures map `io.grpc.Status.Code` to user-facing strings via a new
  `McSkillException`/reuse of the existing `PresentedException` pattern
  (`authenticator/microsoft/PresentedException.java`) — e.g. `UNAVAILABLE` →
  "server unreachable", `UNAUTHENTICATED` → triggers the session-refresh path
  in §5.
- Download failures reuse the existing retry/verify primitives
  (`DownloadUtils.ensureSha1`), not a new mechanism.
- MFA and stale-credential dead ends both surface as an explicit dialog
  (`Tools.dialog`), never a silent failure or infinite retry loop.

## 9. Verification plan

No significant automated test suite exists for this app's networking/auth
code today (verified by inspection) — verification here is manual, matching
the project's existing practice:

1. Login via `McSkillLoginFragment` with a real mcskill account; confirm the
   account appears in the account spinner with correct username/skin.
2. Force-kill and relaunch the app; confirm the stored session is reused via
   `GetProfile`, with no re-prompt.
3. Manually invalidate the session (e.g. via `Logout` through the API) and
   relaunch; confirm silent re-login via the stored encrypted password.
4. Open `McSkillClientsFragment`; confirm the live client list renders
   (title/version/online count/mods) matching what `GetClients()` returns.
5. Install one client; confirm files download with progress, hash
   verification catches a deliberately corrupted file, and the client
   appears as a normal profile afterward.
6. Launch the installed client; confirm it boots with the correct
   mainClass/classpath/JVM args and the bundled JDK matching `java_version`.
7. Trigger an MFA-required test account (if available) and confirm the
   "not supported" dialog appears instead of a crash or hang.

## 10. Housekeeping (outside this feature's scope, flagged for the user)

`main.py`, `file_tree.txt`, and `clients.txt` in the repo root are the user's
own local reverse-engineering script/output (untracked, per `git status`).
`main.py` contains a real plaintext password. These should stay untracked or
be added to `.gitignore` — never referenced or copied into the Java
implementation, and never committed.
