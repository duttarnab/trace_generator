# trace_generator

Sample Java application that runs batch authorization with the [Cedarling](https://github.com/JanssenProject/jans/tree/main/jans-cedarling) Java binding and sends a signed TRACE record for each decision to a Janssen Lock Server.

It is modeled on Lock's `TraceEndToEndTest`, but Cedarling signs and emits the records instead of the test building them by hand.

## What it does

The workflow has three programs. Run them in this order:

| Step | Program | Result |
|---|---|---|
| 1 | `io.jans.Main` | Runs Cedarling authorizations from `input/authorization.json`. Cedarling signs a TRACE record for each decision and sends it to Lock. The executions it sent are saved in `output/executions.json`. |
| 2 | `io.jans.TraceCollectMain` | Fetches those executions from Lock into `output/traces/`. |
| 3 | `io.jans.FleetMain` | Converts the traces to FACADE training files, then trains FACADE and scores the test window. |

What `Main` does:

1. Gets `trace.admin`, `trace.write` and `trace.readonly` tokens from the Auth Server.
2. Generates an Ed25519 producer key and registers its public key in Lock (`POST /audit/trace/admin/producer-keys`). It also registers a producer chain.
3. Starts a local test issuer, which creates an RSA key, serves its JWKS on `http://localhost:<port>/jwks` and signs JWTs.
4. Builds a policy store `.cjar` from `src/main/resources/config/multiIssuer/policy-store-acme`, with a generated `trusted-issuers.json` that trusts the local issuer.
5. Starts Cedarling with Lock and TRACE enabled.
6. Builds a traffic plan from the input file. For each user's call, it signs a fresh access, id and userinfo token (cached per user), prints the action, resource and context, and calls `authorizeMultiIssuerBatch` with a `TraceContext`.
7. Writes `output/executions.json`, `truth.json` and `run.json`.

Each batch call belongs to one execution. Tokens and trace options apply to a whole batch call, not to single items. Using a different user, and so a different `workload_id`, therefore takes a separate batch.

## Project layout

| File | Purpose |
|---|---|
| `Main.java` | Step 1: runs the authorizations and saves the executions. |
| `LockConfig.java` | Lock settings from environment variables, plus URLs and scope constants. |
| `LockClient.java` | HTTP calls: token, key registration, chain registration, execution lookup. |
| `TraceSigningKey.java` | Ed25519 key pair. Exports the public JWK and the private key as PKCS#8 PEM. |
| `TestIssuer.java` | Local JWKS server and RS256 JWT signer. Test use only. |
| `AcmeStore.java` | Builds the `.cjar` and writes a version 2 `trusted-issuers.json` for the local issuer. |
| `AcmeScenarios.java` | Older fixed set of 21 sample requests. `Main` no longer uses it. |
| `AcmeInputs.java`, `AcmePlan.java`, `TraceExporter.java` | Loads `input/authorization.json`, builds the traffic plan for policy-store-acme from it, and reads and writes the execution list and the traces. |
| `TraceCollectMain.java` | Fetches the traces of the saved executions from Lock into `output/traces`. |
| `FleetMain.java` | Converts the exported traces to FACADE data and runs training and scoring. |
| `FleetGenMain.java`, `FleetPlan.java`, `FleetRunner.java` | Synthetic agent fleet generator for `policy-store-fleet`. |
| `facade/*` | Conversion of traces to FACADE data, templates, TFRecord writer, settings, and the FACADE command runner. |
| `facade.properties`, `scripts/evaluate_scores.py` | FACADE settings, and the script that ranks injected anomalies in the scores. |
| `TraceSession.java`, `PolicyStoreZip.java` | Shared setup (Lock tokens, producer key, chain, bootstrap) and the `.cjar` builder used by fleet mode. |
| `lib/cedarling-java-0.0.0-nightly.jar` | Cedarling Java binding, as a system-scope dependency. |

## Requirements

- JDK 17 or later.
- A Lock Server with TRACE enabled (see [Lock Server setup](#lock-server-setup)).
- A client on that server that holds the three scopes `trace.admin`, `trace.write` and `trace.readonly`.

## Configuration

Set these environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `LOCK_BASE_URL` | `https://your-server-url.com` | Base URL of the Lock Server. Used for `/jans-lock/api/v1` and `/.well-known/lock-server-configuration`. |
| `AUTH_BASE_URL` | same as `LOCK_BASE_URL` | Base URL of the Auth Server. Used for the token endpoint `/jans-auth/restv1/token`. Set it only when Auth and Lock run on different hosts. |
| `TRACE_CLIENT_ID` | built-in test value | Client id with the three trace scopes. |
| `TRACE_CLIENT_SECRET` | built-in test value | Client secret. Set your own. The built-in value should be removed from `LockConfig.java` (see Security notes). |
| `TRACE_INSECURE_TLS` | `false` | `true` skips TLS certificate checks for the HTTP calls in this app. Use only against a test server. It does not affect Cedarling's own connection to Lock. |

## Run

The project depends on `lib/cedarling-java-0.0.0-nightly.jar`, `org.json:json`, Jackson (`databind`, `core`, `annotations`) and `bcprov-jdk18on`. With Maven:

```
mvn compile
```

Or compile and run directly with `javac` and `java` and a classpath that contains those jars:

```
export TRACE_CLIENT_SECRET=...
javac -cp "<jars>" -d out $(find src/main/java -name '*.java')
java -cp "out:src/main/resources:<jars>" io.jans.Main
java -cp "out:src/main/resources:<jars>" io.jans.TraceCollectMain
java -cp "out:src/main/resources:<jars>" io.jans.FleetMain
```

Run from the project root. The programs find the policy store, `input/` and `output/` by relative path.

## Lock Server setup

Cedarling opens a new producer chain every time it starts and registers it with its first record. Lock must therefore allow this. Set these in the Lock `traceConfiguration`:

| Setting | Value | Without it |
|---|---|---|
| `chainRegistrationMode` | `FIRST_OBSERVED` | Every record is refused with `chain_not_registered`. |
| `defaultEvidenceDomainId` | a domain id, for example `default` | `403 client_not_bound` |
| `defaultAllowedProducerIds` | includes `trace-generator` | `producer_not_allowed` |

Lock accepts a JWT issuer in a TRACE record only if it is `https`, or `http` with host `localhost`. The local test issuer uses `http://localhost:<port>`.

## Authorization inputs

All requests use `authorizeMultiIssuerBatch(tokens, items, options)`. The users, actions, resources and contexts come from `input/authorization.json` (see [Input file](#input-file)).

### Policy store

`policy-store-acme` (name `acme-ops-agent`, version `1.2.0`) has 9 policies. They are `permit` or `forbid` rules over the actions below.

| Policy | Effect | Condition |
|---|---|---|
| `read-customer-on-ticket-token` | permit `ReadCustomer` | Resource kind is `customer-record`, `resource.customer == context.ticket_customer`, and `context.ticket_assignee` equals the userinfo `given_name`. |
| `deploy-staging-token` | permit `Deploy` | Userinfo `role` contains `admin`, resource kind is `workload`, environment is `staging`, and `context.change_workload == resource.workload`. |
| `deploy-prod-approved-change-token` | permit `Deploy` | As above for `prod`, and also `change_approved` and `human_on_call` are true. |
| `block-customer-export` | forbid `ExportCustomers` | Always. |
| `block-suspicious-request` | forbid any action | `context.suspicious` is true. |
| `block-untrusted-agent`, `deploy-staging-on-call`, `deploy-prod-approved-change`, `read-customer-on-ticket` | agent policies | They apply only when the request has no tokens, so they do not match here. |

### Tokens

The token map keys must equal the entity types in `trusted-issuers.json`:

| Map key | Token |
|---|---|
| `AcmeOps::Access_token` | access token |
| `AcmeOps::id_token` | id token |
| `AcmeOps::Userinfo_token` | userinfo token |

The trusted issuer is named `Acme`, so policies read them as `context.tokens.acme_access_token`, `acme_id_token` and `acme_userinfo_token`.

Claims set by `TestIssuer` (RS256, valid for one hour):

| Claim | Access token | Id token | Userinfo token |
|---|---|---|---|
| `iss` | `http://localhost:<port>` | same | same |
| `sub` | `<inum>-<runId>` | same | same |
| `aud` | `client_id` from the input file | array with the same value | `client_id` from the input file |
| `jti` | random UUID | random UUID | random UUID |
| `iat`, `nbf`, `exp` | now, now-5s, now+1h | same | same |
| `client_id`, `token_type` | `client_id` from the input file, `Bearer` | none | none |
| `scope` | `["openid", "profile"]` | none | none |
| `given_name`, `inum`, `role` | none | none | user's values |

JOSE header: `alg=RS256`, `typ=JWT`, `kid=test-issuer-<id>`.

### Users and requests

Both come from `input/authorization.json`: the users (with `given_name`, `inum`, `roles` and `team`), and one template per action for the resource and the context. The default file has 10 users in two teams and two actions.

| Team | Users | Roles | Action |
|---|---|---|---|
| `ops` | Alice, Carol, Erin, Gina, Hank | `admin` (Carol also `sre`) | `Deploy` |
| `support` | Bob, Dave, Ivy, Jack, Kim | none | `ReadCustomer` |

The `given_name` is also the ticket assignee in the context. Only users with `role` containing `admin` can `Deploy`.

Example batch item, a `ReadCustomer` request for Bob (the printed form is the same):

```json
{
  "action": "AcmeOps::Action::\"ReadCustomer\"",
  "resource": {
    "cedar_entity_mapping": { "entity_type": "AcmeOps::Resource", "id": "customer-record-customer-9-prod-none" },
    "kind": "customer-record",
    "customer": "customer-9",
    "environment": "prod",
    "workload": "none"
  },
  "context": {
    "ticket_customer": "customer-9",
    "ticket_assignee": "Bob",
    "change_approved": false,
    "change_workload": "none",
    "human_on_call": false,
    "suspicious": false
  }
}
```

In the deployed version, Cedarling adds `context.tokens` from the token map.

### Trace context

Each batch call gets one `TraceContext`:

| Field | Value |
|---|---|
| `execution_id` | `exec-<runId>-<nnnn>`, shared by all users in the execution |
| `execution_authority` | `execution_authority` from the input file (default `https://gateway.example.org`) |
| `workload_id` | `<given_name>-<inum>` |
| `workload_instance_id` | `<workload_id>-1` |

`workload_id` and `workload_instance_id` are set per call by the host. Cedarling does not derive them from bootstrap properties.

### Cedarling bootstrap

| Property | Value |
|---|---|
| `CEDARLING_APPLICATION_NAME` | `trace-generator` |
| `CEDARLING_POLICY_STORE_LOCAL_FN` | generated `.cjar` in the temp directory |
| `CEDARLING_LOG_TYPE`, `CEDARLING_LOG_LEVEL` | `std_out`, `INFO` |
| `CEDARLING_LOCK` | `enabled` |
| `CEDARLING_LOCK_SERVER_CONFIGURATION_URI` | `<LOCK_BASE_URL>/.well-known/lock-server-configuration` |
| `CEDARLING_LOCK_ACCESS_TOKEN_JWT` | a `trace.write` token |
| `CEDARLING_TRACE` | `enabled` |
| `CEDARLING_TRACE_PRODUCER_ID` | `trace-generator` |
| `CEDARLING_TRACE_SIGNING_KEY` | temp file, mode `rw-------`, with the PKCS#8 PEM key |
| `CEDARLING_TRACE_SIGNING_KID` | `trace-generator-kid-<runId>` |

Signature validation (`CEDARLING_JWT_SIG_VALIDATION`) is left at its default, `enabled`.

## Reading the output

- **`Main`:** for each request it prints `authorize execution=... workload=...` with the action, resource and context. Then it prints `decisions: allowed=N denied=M failed=K`. `failed` counts items that did not evaluate. The Decision log line for each request has `trace_record_status`. `emitted` means Cedarling queued the record. Check Lock for whether it was accepted.
- **`TraceCollectMain`:** prints `execution <id>: got of expected records` for each execution that is short, and a final `missing records` count. Records may reach Lock after the authorization call returns, so run it again if some are missing.

## Troubleshooting

| Message | Cause |
|---|---|
| `chain_not_registered` | Lock `chainRegistrationMode` is `PRE_REGISTERED`. Set it to `FIRST_OBSERVED`. |
| `403 client_not_bound` | Lock has no evidence domain for the client. Set `defaultEvidenceDomainId`. |
| `producer_not_allowed` | Add `trace-generator` to `defaultAllowedProducerIds`. |
| `not_emitted_missing_context` | The `TraceContext` was missing or had an empty required field. |
| Token dropped, `failed` greater than 0 | The token map key does not match an entity type in `trusted-issuers.json`. |

## FACADE training data from policy-store-acme traces

This is the detail of the three steps above.

The workflow has three steps. The output directory (`output/` at the project root, see `output.dir`) starts empty.

1. **Generate traces.** Run `io.jans.Main`. It generates traffic for `policy-store-acme` and sends the TRACE records to Lock Server through Cedarling. It saves the executions it sent (execution id, execution authority, phase, expected record count, workload ids) in `output/executions.json`.
2. **Collect traces.** Run `io.jans.TraceCollectMain`. It reads `output/executions.json`, fetches each execution from Lock and saves it in `output/traces/<execution_id>.json`. It waits up to 60 seconds per execution for the expected records (`-Dcollect.timeoutSeconds` changes this) and prints the number of missing records. Run it again if records are missing.
3. **Build FACADE data and train.** Run `io.jans.FleetMain`. It reads `output/traces/`, writes the JSONL, TFRecord and config files for FACADE, then runs the FACADE commands for training and anomaly detection.

`Main` stops if `output/traces` has files or `output/executions.json` exists. Empty the directory, or run with `-Doutput.clean=true` to replace the traces, the `facade/` directory, `executions.json`, `truth.json` and `run.json`.

### What `Main` generates

- **Users:** 10 users in two teams, each with a signed access, id and userinfo token from the local test issuer. The ops team has the `admin` role (Alice, Carol, Erin, Gina, Hank). The support team has no role (Bob, Dave, Ivy, Jack, Kim). The `workload_id` is `<given_name>-<inum>`.
- **Executions:** each execution has 2 to 4 users of one team, and 10% add a user from the other team. All users in an execution share the same `execution_id`. Each user makes one batch call (`authorizeMultiIssuerBatch`) of 1 to 3 requests.
- **Normal behavior:** ops users `Deploy` workloads to staging, or to prod with an approved change and a human on call (permit). Support users `ReadCustomer` on their own tickets (permit). About 3% of requests break a rule on purpose (a mismatched workload or ticket) and are denied.
- **Train and test phases:** `train_executions` (default 200), then `test_executions` (default 40).
- **Injected anomalies:** `anomalies` (default 6) executions at the end where an ops user reads a customer record (set by `anomaly` in the input file). It is allowed, but ops users never do it otherwise. The anomaly request ids go to `output/truth.json`.

### Input file

Authorization inputs come from `input/authorization.json` (override the path with `-Dinput.file=...`). Fields:

| Field | Meaning |
|---|---|
| `execution_authority`, `client_id` | Trace `execution_authority`, and the `client_id` claim of the generated tokens. |
| `traffic` | `seed`, `train_executions`, `test_executions`, `anomalies`, `pause_millis` (pause after each execution, sets run length), `deny_rate`, `cross_team_rate`. |
| `customers`, `workloads`, `environments` | Customer count (`customer-1..N`), workload names and environments that fill the templates. |
| `actions` | One entry per action, keyed by name: `action` (Cedar action id), `resource` and `context` templates, and `deny_context` (context fields overridden on the `deny_rate` share of requests). |
| `team_actions` | Name of the `actions` entry each team performs. |
| `anomaly` | `team` and `action` (an `actions` name) of the injected anomalies. |
| `users` | `given_name`, `inum`, `roles`, `team` for each user. Workload id is `<given_name>-<inum>`. Each team in `team_actions` needs at least 2 users. |

Templates in `actions` are copied per request. A string like `"${customer}"` is replaced by the value, and keeps its type when it is the whole string (so `"${is_prod}"` becomes a boolean). Variables: `given_name`, `inum`, `customer`, `workload`, `environment`, `is_prod`. An unknown variable stops the run.

Other `Main` option (system property): `output.clean`.

`Main` also writes `output/run.json` with the start of the test phase. `FleetMain` uses it as the split between training and scoring.

### Run

```
java -cp "<jars>" io.jans.Main
java -cp "<jars>" io.jans.TraceCollectMain
java -cp "<jars>" io.jans.FleetMain
```

`FleetMain` can also read other trace files: `io.jans.FleetMain [traces dir] [truth.json]`.

The synthetic agent fleet generator that used `policy-store-fleet` is still available as `io.jans.FleetGenMain`. It does everything in one run.

### Output directory

Everything goes to `output/` at the project root (`output.dir` in `facade.properties`):

| Path | Content |
|---|---|
| `output/executions.json` | Executions sent by `Main`: the input of `TraceCollectMain`. |
| `output/traces/<execution_id>.json` | Execution responses from Lock, one file per execution. |
| `output/truth.json` | Injected anomalies. `record_id` is Cedarling's request id, which is also the TRACE `record_id`. |
| `output/facade/actions.jsonl`, `contexts.jsonl` | Human-readable FACADE actions and contexts. You can review and edit them. |
| `output/facade/action.tfrecord`, `context.tfrecord` | The same data as FACADE TFRecords. |
| `output/facade/directive.textproto`, `config.textproto` | FACADE directive and model config. |
| `output/facade/train.tfrecord`, `vocabs.tfrecord`, `model/` | Training dataset, vocabulary and trained model. |
| `output/facade/scores.tfrecord` | Anomaly scores for the test window. |
| `output/facade/summary.txt` | Conversion counts and the time windows used. |
| `output/facade/logs/*.log` | Output of each FACADE command. |

### Conversion

The conversion follows `lock2facade` and is in the `io.jans.facade` package. `TraceReader` reads Lock execution responses, envelopes and bare assertions (`.json` or `.jsonl`, or a directory of them). `FacadeMapper` maps them to FACADE data:

- **Principal:** `trace.subject.workload_id`. If that is missing, it follows the `authorized_by`, `authorized` and `produced_effect` parents, then falls back to `trace.execution_authority`.
- **Action:** one per decision, with id `<producer_id>/<record_id>:<index>`, resource `<action>|<resource_type>` and time `signed_at`. Only `AUTHORIZATION_DECISION` with outcome `ALLOW` or `SUCCESS` by default.
- **Context:** one per principal and execution, with the peer attribute `execution = <execution_authority>|<trace_execution_id>`.
- **Dropped:** records Lock marked `signature_valid=false` or `key_authorized=false`, `VOID` receipts, and duplicate `(producer, record_id)`.

`FacadeFiles` writes the JSONL and TFRecord files. The TFRecords are written directly in Java, so conversion needs no Python or TensorFlow. On the `lock2facade` mock traces, the JSONL files are identical to `lock2facade` output, and both TFRecords decode correctly with FACADE's protos.

To convert existing traces without generating new ones, run `io.jans.facade.FacadeMain [traces dir] [truth.json]`. It reads `output/traces` and `output/truth.json` by default.

### Configure the FACADE project

Settings are read, in this order, from a system property (`-Dfacade.dir=...`), an environment variable where one exists, and `facade.properties` in the project root.

| Setting | Meaning |
|---|---|
| `facade.dir` or env `FACADE_DIR` | Path to the FACADE checkout. It must contain `batch/`. |
| `facade.python` or env `FACADE_PYTHON` | Python with FACADE's dependencies. Default is `<facade.dir>/env/bin/python`, then `python3`. |
| `output.dir` | Output directory. Default `output`. |
| `facade.actionKinds`, `facade.outcomes`, `facade.peerAttrs`, `facade.timeSource` | Conversion options, as in `lock2facade` (`--action-kinds`, `--outcomes`, `--peer-attrs`, `--time-source`). |
| `facade.trainingExamples` | Training examples. Default 20000. |
| `facade.testFraction`, `facade.testStart` | The last 20% of the action time is scored by default. `facade.testStart` is an ISO instant that overrides it. `FleetMain` uses `output/run.json` when it exists. |
| `facade.snapshotSeconds`, `historySeconds`, `lookbackSeconds`, `dedupSeconds` | Directive windows in seconds, or `auto`. |
| `facade.run` | `false` converts the traces but does not run FACADE. |

The FACADE commands that run, from the FACADE directory: `batch.dataset_maker_main`, `model.train_main`, `batch.inference_main`, `batch.read_scores_main`, and `scripts/evaluate_scores.py` against `truth.json`.

### Limits

- **Time cannot be backdated.** Cedarling signs each record with the current time, so a run spans minutes, not days. With `auto`, the directive windows scale to the time span of the data: a snapshot every `span / 60` seconds (at least 5), and 1 hour or more of history. Spread the run with `pause_millis` in the input file to get more snapshots.
- **Volume.** The defaults plan about 1,500 decisions. The `lock2facade` mock data has about 14,000 records. Raise `train_executions` and `test_executions` for more.
- **One chain.** One Cedarling instance writes all records, so they share one producer chain. Lock must run with `FIRST_OBSERVED` chain registration.
- Scores from a small or under-trained model are noisy. In test runs the 6 injected anomalies ranked from 1 to 29 of about 360 actions, with AUC between 0.94 and 1.0. Treat this as a check that the pipeline works, not as a measure of detection quality.

## Security notes

- The local issuer and its tokens exist only for local testing. Only the store that `AcmeStore` generates trusts them.
- Never commit client secrets or tokens. `LockConfig` currently has a default client id and secret written in the source. Remove the secret, rotate it, and keep it in `TRACE_CLIENT_SECRET`.
- `TRACE_INSECURE_TLS=true` disables certificate checks for this app's own HTTP calls. Do not use it outside a test environment.
