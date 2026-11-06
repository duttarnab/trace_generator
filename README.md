# trace_generator

This project generates authorization traffic with [Cedarling](https://github.com/JanssenProject/jans/tree/main/jans-cedarling), collects the signed TRACE records from a Janssen Lock Server, and turns them into training data for the FACADE anomaly detector.

## How it works

You run three programs, one after the other. Each one reads what the previous one wrote to the `output/` directory.

```
input/authorization.json
        |
        v
  1. Main               Cedarling authorizes requests; each decision becomes a signed
        |               TRACE record sent to Lock.            -> output/executions.json
        v
  2. TraceCollectMain   Fetches those records back from Lock. -> output/traces/
        |
        v
  3. FleetMain          Converts traces to FACADE files, trains, scores.
                                                              -> output/facade/
```

| Step | Command (class) | What you get |
|---|---|---|
| 1 | `io.jans.Main` | `output/executions.json`, `truth.json`, `run.json` |
| 2 | `io.jans.TraceCollectMain` | `output/traces/<execution_id>.json`, one per execution |
| 3 | `io.jans.FleetMain` | `output/facade/` with the FACADE input files, the trained model and the scores |

## Quick start

**1. Check the prerequisites.**

- JDK 17 or later.
- A Lock Server with TRACE enabled, set up as in [Lock Server setup](#lock-server-setup).
- A client on that server with the scopes `trace.admin`, `trace.write` and `trace.readonly`.
- A FACADE checkout with a Python environment. Set its path in `facade.properties` (`facade.dir`).

**2. Set the connection.** Environment variables:

```
export LOCK_BASE_URL=https://your-lock-server
export AUTH_BASE_URL=https://your-auth-server      # only if different from LOCK_BASE_URL
export TRACE_CLIENT_ID=...
export TRACE_CLIENT_SECRET=...
```

**3. Check the inputs.** Open `input/authorization.json`. The defaults work as they are. See [Input file](#input-file) to change users, actions or traffic.

**4. Make sure `output/` is empty.** `Main` stops if it finds a previous run. Delete the old files, or add `-Doutput.clean=true` to the `Main` command.

**5. Run the three programs** from the project root:

```
javac -cp "<jars>" -d out $(find src/main/java -name '*.java')
java -cp "out:src/main/resources:<jars>" io.jans.Main
java -cp "out:src/main/resources:<jars>" io.jans.TraceCollectMain
java -cp "out:src/main/resources:<jars>" io.jans.FleetMain
```

`<jars>` are `lib/cedarling-java-0.0.0-nightly.jar`, `org.json:json`, Jackson (`databind`, `core`, `annotations`) and `bcprov-jdk18on`. `mvn compile` fetches the Maven ones.

**6. Read the result.** `FleetMain` prints where the 6 injected anomalies rank among all scored actions. See [Output directory](#output-directory) for all files.

## What `Main` generates

The traffic is made to look like normal work, plus a few deliberate anomalies, so FACADE has something to learn and something to catch.

- **Users:** 10 users in two teams. Each user gets a signed access, id and userinfo token from a local test issuer.

  | Team | Users | Roles | Normal action |
  |---|---|---|---|
  | `ops` | Alice, Carol, Erin, Gina, Hank | `admin` | `Deploy` a workload |
  | `support` | Bob, Dave, Ivy, Jack, Kim | none | `ReadCustomer` for their own ticket |

- **Executions:** an execution is a group of 2 to 4 users from one team (10% of them also include one user from the other team). They share one `execution_id`. Each user makes one batch call with 1 to 3 requests.
- **Denials:** about 3% of requests break a rule on purpose, such as a mismatched workload or ticket, so they are denied.
- **Train and test:** the first 200 executions are the training phase, the next 40 are the test phase.
- **Anomalies:** at the end, 6 executions where an `ops` user reads a customer record. The policy allows it, but `ops` users never do it otherwise. Their record ids go to `output/truth.json`, and `FleetMain` checks how highly FACADE ranks them.

All numbers above are defaults from the input file.

## Input file

`input/authorization.json` holds everything `Main` authorizes. Use `-Dinput.file=<path>` to read another file.

| Field | Meaning |
|---|---|
| `execution_authority` | The trace `execution_authority`. |
| `client_id` | The `client_id` and `aud` of the generated tokens. |
| `traffic` | `seed`, `train_executions`, `test_executions`, `anomalies`, `pause_millis` (pause after each execution, which sets how long the run takes), `deny_rate`, `cross_team_rate`. |
| `customers`, `workloads`, `environments` | Customer count (`customer-1` to `customer-N`), workload names and environments used to fill the templates. |
| `actions` | One entry per action, keyed by name. It has `action` (the Cedar action id), a `resource` template, a `context` template, and `deny_context` (context fields to overwrite on the `deny_rate` share of requests). |
| `team_actions` | Which `actions` entry each team performs. |
| `anomaly` | The `team` and `action` (an `actions` name) used for the injected anomalies. |
| `users` | `given_name`, `inum`, `roles` and `team` for each user. Each team in `team_actions` needs at least 2 users. |

**Templates.** The `resource` and `context` in `actions` are copied for each request. A value like `"${customer}"` is replaced with the real value. If it is the whole string, it keeps its type, so `"${is_prod}"` becomes a boolean. The variables are `given_name`, `inum`, `customer`, `workload`, `environment` and `is_prod`. An unknown variable stops the run.

**Workload id.** Each user's `workload_id` is `<given_name>-<inum>`, for example `Alice-inum-alice`.

**Example.** This is one request that `Main` sends, a `ReadCustomer` for Bob:

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

`Main` prints the action, resource and context of every request before it is authorized. Cedarling adds `context.tokens` from the user's tokens.

## Configuration

### Connection (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `LOCK_BASE_URL` | `https://admin-ui-test.gluu.org` | Base URL of the Lock Server. Used for `/jans-lock/api/v1` and `/.well-known/lock-server-configuration`. |
| `AUTH_BASE_URL` | same as `LOCK_BASE_URL` | Base URL of the Auth Server. Used for the token endpoint `/jans-auth/restv1/token`. |
| `TRACE_CLIENT_ID` | built-in test value | Client with the three trace scopes. |
| `TRACE_CLIENT_SECRET` | built-in test value | The client secret. Set your own. See [Security notes](#security-notes). |
| `TRACE_INSECURE_TLS` | `false` | `true` skips TLS certificate checks for this app's own HTTP calls. Test servers only. It does not change Cedarling's own connection to Lock. |

Use the same values for `Main` and `TraceCollectMain`, so they talk to the same server.

### FACADE (`facade.properties`)

Each setting is read from a system property (`-Dfacade.dir=...`) first, then from an environment variable where one exists, then from `facade.properties`.

| Setting | Meaning |
|---|---|
| `facade.dir` or env `FACADE_DIR` | Path to the FACADE checkout. It must contain `batch/`. |
| `facade.python` or env `FACADE_PYTHON` | Python with FACADE's dependencies. Default `<facade.dir>/env/bin/python`, then `python3`. |
| `output.dir` | Output directory. Default `output`. |
| `facade.trainingExamples` | Number of training examples. Default 20000. |
| `facade.testFraction`, `facade.testStart` | By default the last 20% of the action time is scored. `facade.testStart` is an ISO instant that overrides that. `FleetMain` uses `output/run.json` when it exists. |
| `facade.snapshotSeconds`, `historySeconds`, `lookbackSeconds`, `dedupSeconds` | Directive windows in seconds, or `auto` (scaled to the time span of the data). |
| `facade.actionKinds`, `facade.outcomes`, `facade.peerAttrs`, `facade.timeSource` | Conversion options, as in `lock2facade`. |
| `facade.run` | `false` converts the traces but does not run FACADE. |

## Output directory

Everything is written to `output/` at the project root.

| Path | Written by | Content |
|---|---|---|
| `executions.json` | `Main` | The executions that were sent: execution id, execution authority, phase, expected record count and workload ids. It is the input of `TraceCollectMain`. |
| `truth.json` | `Main` | The injected anomalies. `record_id` is Cedarling's request id, which is also the TRACE `record_id`. |
| `run.json` | `Main` | The start of the test phase. `FleetMain` uses it to split training from scoring. |
| `traces/<execution_id>.json` | `TraceCollectMain` | The records Lock stored for one execution. |
| `facade/actions.jsonl`, `contexts.jsonl` | `FleetMain` | FACADE actions and contexts in readable form. You can review and edit them. |
| `facade/action.tfrecord`, `context.tfrecord` | `FleetMain` | The same data as FACADE TFRecords. |
| `facade/directive.textproto`, `config.textproto` | `FleetMain` | FACADE directive and model configuration. |
| `facade/train.tfrecord`, `vocabs.tfrecord`, `model/` | FACADE | Training dataset, vocabulary and the trained model. |
| `facade/scores.tfrecord` | FACADE | Anomaly scores for the test window. |
| `facade/summary.txt` | `FleetMain` | Conversion counts and the time windows used. |
| `facade/logs/*.log` | `FleetMain` | Output of each FACADE command. |

## Step details

### 1. `Main`

1. Gets `trace.admin`, `trace.write` and `trace.readonly` tokens from the Auth Server.
2. Generates an Ed25519 producer key, and registers its public key and a producer chain in Lock.
3. Starts a local test issuer, which signs the user tokens and serves its keys on `http://localhost:<port>/jwks`.
4. Builds a policy store from `src/main/resources/config/multiIssuer/policy-store-acme`, with a generated `trusted-issuers.json` that trusts the local issuer.
5. Starts Cedarling with Lock and TRACE enabled.
6. Plays the traffic plan. For each user's call it calls `authorizeMultiIssuerBatch` with that user's tokens and a `TraceContext`. Cedarling signs one TRACE record per request and sends it to Lock.
7. Writes `executions.json`, `truth.json` and `run.json`.

`Main` stops if `output/traces` has files or `output/executions.json` exists. Use `-Doutput.clean=true` to delete the old `traces/`, `facade/`, `executions.json`, `truth.json` and `run.json` first.

### 2. `TraceCollectMain`

Reads `output/executions.json`, fetches each execution from Lock and saves it in `output/traces/`. It waits up to 60 seconds per execution for the expected number of records (`-Dcollect.timeoutSeconds=<n>` changes this). Cedarling sends records after the authorization call returns, so some may not be in Lock yet. It prints each execution that is short and a final count of missing records. Run it again if records are missing.

### 3. `FleetMain`

Converts the traces to FACADE files, then runs the FACADE commands from the FACADE directory: `batch.dataset_maker_main`, `model.train_main`, `batch.inference_main` and `batch.read_scores_main`, and then `scripts/evaluate_scores.py` against `truth.json`.

`FleetMain` stops with a clear message if the traces hold too little data to train, for example when `Main` was interrupted. Run `Main` to the end first.

The conversion follows `lock2facade` and lives in the `io.jans.facade` package:

- **Principal:** `trace.subject.workload_id`. If it is missing, the converter follows the `authorized_by`, `authorized` and `produced_effect` parents, and then falls back to `trace.execution_authority`.
- **Action:** one per decision, with id `<producer_id>/<record_id>:<index>`, resource `<action>|<resource_type>` and time `signed_at`. By default only `AUTHORIZATION_DECISION` records with outcome `ALLOW` or `SUCCESS` are used.
- **Context:** one per principal and execution, with the peer attribute `execution = <execution_authority>|<trace_execution_id>`.
- **Dropped:** records Lock marked `signature_valid=false` or `key_authorized=false`, `VOID` receipts, and duplicate `(producer, record_id)`.

The TFRecords are written directly in Java, so conversion needs no Python or TensorFlow. To convert traces from another source, run `io.jans.FleetMain [traces dir] [truth.json]`, or `io.jans.facade.FacadeMain` with the same arguments.

## Lock Server setup

Cedarling opens a new producer chain every time it starts and registers it with its first record. Lock must allow this. Set these in the Lock `traceConfiguration`:

| Setting | Value | Without it |
|---|---|---|
| `chainRegistrationMode` | `FIRST_OBSERVED` | Every record is refused with `chain_not_registered`. |
| `defaultEvidenceDomainId` | a domain id, for example `default` | `403 client_not_bound` |
| `defaultAllowedProducerIds` | includes `trace-generator` | `producer_not_allowed` |

Lock accepts a JWT issuer in a TRACE record only if it is `https`, or `http` with host `localhost`. The local test issuer uses `http://localhost:<port>`.

## Troubleshooting

| Message | Cause and fix |
|---|---|
| `SSLHandshakeException: PKIX path building failed` | The server certificate is not trusted by Java, for example because it is self-signed. Add it to the Java truststore. `TRACE_INSECURE_TLS=true` is a test-server shortcut and covers only this app's own HTTP calls, not Cedarling's connection to Lock. |
| `chain_not_registered` | Lock `chainRegistrationMode` is `PRE_REGISTERED`. Set it to `FIRST_OBSERVED`. |
| `403 client_not_bound` | Lock has no evidence domain for the client. Set `defaultEvidenceDomainId`. |
| `producer_not_allowed` | Add `trace-generator` to `defaultAllowedProducerIds`. |
| `not_emitted_missing_context` | The `TraceContext` was missing or had an empty required field. |
| `failed` greater than 0 in `decisions: ...` | A token was dropped. The token map key does not match an entity type in `trusted-issuers.json`. |
| `missing records` from `TraceCollectMain` | Lock has fewer records than were sent. Run `TraceCollectMain` again, or raise `-Dcollect.timeoutSeconds`. |
| `too little trace data to train` from `FleetMain` | `output/traces` holds only part of a run. Clean `output/` and run `Main` to the end. |
| `output ... already holds a previous run` from `Main` | Empty `output/` or use `-Doutput.clean=true`. |

## Reference

### Reading the `Main` output

- `authorize execution=... workload=...` with the action, resource and context, for every request.
- `decisions: allowed=N denied=M failed=K`. `failed` counts requests that did not evaluate.
- In Cedarling's decision log, `trace_record_status` of `emitted` means the record was queued. Check Lock for whether it accepted it.

### Policy store

`policy-store-acme` (name `acme-ops-agent`, version `1.2.0`) has 9 policies, `permit` or `forbid` rules over the actions below.

| Policy | Effect | Condition |
|---|---|---|
| `read-customer-on-ticket-token` | permit `ReadCustomer` | Resource kind is `customer-record`, `resource.customer == context.ticket_customer`, and `context.ticket_assignee` equals the userinfo `given_name`. |
| `deploy-staging-token` | permit `Deploy` | Userinfo `role` contains `admin`, resource kind is `workload`, environment is `staging`, and `context.change_workload == resource.workload`. |
| `deploy-prod-approved-change-token` | permit `Deploy` | As above for `prod`, and `change_approved` and `human_on_call` are true. |
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
| `given_name`, `inum`, `role` | none | none | the user's values |

JOSE header: `alg=RS256`, `typ=JWT`, `kid=test-issuer-<id>`.

### Trace context

Each batch call gets one `TraceContext`:

| Field | Value |
|---|---|
| `execution_id` | `exec-<runId>-<nnnn>`, shared by all users in the execution |
| `execution_authority` | `execution_authority` from the input file |
| `workload_id` | `<given_name>-<inum>` |
| `workload_instance_id` | `<workload_id>-1` |

Tokens and trace options apply to a whole batch call, not to single requests. A different user, and so a different `workload_id`, therefore needs a separate batch call. The host sets `workload_id` and `workload_instance_id` per call. Cedarling does not derive them from bootstrap properties.

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

### Limits

- **Time cannot be backdated.** Cedarling signs each record with the current time, so a run spans minutes, not days. With `auto`, the directive windows scale to the time span of the data. Use `pause_millis` in the input file to spread the run and get more snapshots.
- **Volume.** The defaults plan about 1,500 decisions. The `lock2facade` mock data has about 14,000 records. Raise `train_executions` and `test_executions` for more.
- **One chain.** One Cedarling instance writes all records, so they share one producer chain. Lock must run with `FIRST_OBSERVED` chain registration.
- **Scores are noisy on small data.** In test runs the 6 injected anomalies ranked from 1 to 29 of about 360 actions, with AUC between 0.94 and 1.0. Use this as a check that the pipeline works, not as a measure of detection quality.

### Project layout

| File | Purpose |
|---|---|
| `Main.java` | Step 1: runs the authorizations and saves the executions. |
| `TraceCollectMain.java` | Step 2: fetches the traces from Lock. |
| `FleetMain.java` | Step 3: converts the traces to FACADE data, trains and scores. |
| `AcmeInputs.java`, `AcmePlan.java` | Load `input/authorization.json` and build the traffic plan from it. |
| `TraceExporter.java` | Reads and writes `executions.json`, `truth.json`, `run.json` and the trace files. |
| `TraceSession.java` | Shared setup: Lock tokens, producer key and chain, Cedarling bootstrap. |
| `LockConfig.java`, `LockClient.java` | Lock and Auth settings from environment variables, and the HTTP calls (token, key and chain registration, execution lookup). |
| `TraceSigningKey.java` | Ed25519 key pair. Exports the public JWK and the private key as PKCS#8 PEM. |
| `TestIssuer.java` | Local key server and RS256 token signer. Test use only. |
| `AcmeStore.java` | Builds the policy store `.cjar` and a version 2 `trusted-issuers.json` for the local issuer. |
| `facade/*` | Trace-to-FACADE conversion, templates, TFRecord writer, settings and the FACADE command runner. |
| `facade.properties`, `scripts/evaluate_scores.py` | FACADE settings, and the script that ranks the injected anomalies in the scores. |
| `AcmeScenarios.java` | An older fixed set of 21 sample requests. `Main` no longer uses it. |
| `FleetGenMain.java`, `FleetPlan.java`, `FleetRunner.java`, `PolicyStoreZip.java` | An alternative generator, `io.jans.FleetGenMain`, that uses `policy-store-fleet` and does all steps in one run. |
| `lib/cedarling-java-0.0.0-nightly.jar` | Cedarling Java binding, as a system-scope dependency. |

## Security notes

- The local issuer and its tokens exist only for local testing. Only the store that `AcmeStore` generates trusts them.
- Never commit client secrets or tokens. `LockConfig` currently has a default client id and secret written in the source. Remove the secret, rotate it, and keep it in `TRACE_CLIENT_SECRET`.
- `TRACE_INSECURE_TLS=true` turns off certificate checks for this app's own HTTP calls. Do not use it outside a test environment.
