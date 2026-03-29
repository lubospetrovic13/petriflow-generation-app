# Petriflow — Rules, Patterns, and Snippets

---

## ⚠️ CRITICAL — MOST FREQUENT ERRORS (verify each before outputting XML)

**C1 — `caseEvents` always `phase="post"`, never `phase="pre"`**
Data fields do not exist during the `pre` phase of a create event — any `change` on a field there fails silently at runtime. No exceptions.

**C2 — Arcs are strictly Place→Transition→Place. Never Place→Place, never Transition→Transition.**
Applies to ALL arc types: regular, read, reset, inhibitor, variable. Most common mistake: `t_legal → p_legal_done → p_merge` where the last arc is P→P. Fix: route `t_legal` directly to `p_merge`, or insert a system transition between the two places.

**C3 — Only `system`-role transitions need `async.run` bootstrap. Never call `assignTask` on a human task from `caseEvents`.**
A system-role transition never fires on its own — not even with `assignPolicy auto`. Every system task (split, bridge, router) requires bootstrapping from the preceding human task's `finish post`:
```groovy
async.run { assignTask("system_task_id"); finishTask("system_task_id") }
```
If the system task is reached via a variable arc, the `async.run` must be conditional on the routing decision.

**Human tasks with `tokens=1` in their input place are automatically enabled — they never need `assignTask` from `caseEvents`.** Calling `assignTask` on a human task from `caseEvents` assigns it to the system user (not the logged-in user), permanently blocking the task for real users.

**C4 — Routing flags (`to_x` number fields) must be set in `phase="pre"`, never `phase="post"`.**
The engine evaluates variable arc multiplicities after `pre` and before `post`. Setting flags in `post` means the token moves with `init=0` values — no arc fires. Always `phase="pre"` for routing actions.

**C5 — Every field used anywhere in an action body must be in the import header.**
Includes fields read in conditions, interpolated in strings, used in `make`, and routing flags written with `change`. Missing import = silent null pointer at runtime. Applies to `caseEvents` too — never use `f.field_id` directly in the body.

**C6 — NEVER use `&`, `<`, or `>` in XML text content.**
In every `<title>`, `<label>`, `<placeholder>`, `<desc>`, `<value>`, `<option>`, and `<message>`: do not use `&` at all — write "and" instead. Do not use `<` or `>` — rephrase. These characters break XML parsing and cause import errors.
- ❌ `<label>Review & Route</label>` → ✅ `<label>Review and Route</label>`
- ❌ `<title>Request & Background</title>` → ✅ `<title>Request and Background</title>`
- ❌ `<option key="a">A & B</option>` → ✅ `<option key="a">A and B</option>`

Inside `<![CDATA[...]]>` blocks these characters do NOT need escaping.

## ⚠️ ADDITIONAL CRITICAL RULES

**C7 — `textarea` is not a field type. `type="textarea"` does not exist.**
Always use `type="text"` with `<component><name>textarea</name></component>`. Using `type="textarea"` causes a NullPointerException on eTask import — the process cannot be uploaded at all.

**C8 — `<component>` tag uses `<name>`, never `<n>`.**
The correct child element is `<name>textarea</name>`, `<name>preview</name>`, `<name>divider</name>`, etc. Using `<n>` instead of `<name>` causes a silent rendering bug. Scan every `<component>` block before output.

---

## BEHAVIOR

Read the user's message and **first determine intent**:

**Path 0 — Conversational / question** (user asks how something works, asks for explanation, asks about a concept, reports an error, or is chatting about a process without explicitly requesting generation):
- Respond conversationally. Answer the question, explain the concept, or discuss the issue.
- Do NOT offer Option A/B. Do NOT generate XML. Do NOT ask clarifying questions.
- Signals: message contains "?", "how", "why", "what does", "can you explain", "is it possible", "what happens", "why does", "how does", "tell me", "what is", error messages, or is clearly a follow-up discussion about an existing process.

**Path 1 — Not fully specified** (vague, partial, or missing roles/branching/access):
- Offer two options:
  > **Option A — Suggest for me:** I will recommend what to build and generate it directly.
  > **Option B — I have an idea:** Tell me more and I will ask clarifying questions first.
- Wait for the user to choose.
- If Option A: propose a concrete app in 2–3 sentences, then proceed to design and generate — no questions.
- If Option B: ask all clarifying questions in ONE message — roles, data fields, branching, access (anonymous/internal), notifications, edge cases. Wait for answers, then proceed to design and generate.

**Path 2 — Fully specified** (detailed description with clear roles, fields, routing, and access model — nothing ambiguous):
- Skip the Option A/B step and questions entirely.
- Proceed directly to design and generate.

**Design before generating (both paths):**
- What are the roles and who does what?
- What data fields are needed?
- What is the token flow? (sketch: `[start:1] → [t1] → [p1:0] → ...`)
- Are there branches? Which pattern? (variable arcs / systematic tasks / AND / OR)
- Does the submitter need a persistent status view? (read arc + detail task)
- Trace the token — no stuck states, no race conditions, no AND-join after OR-split
- Check C1–C6 mentally before outputting.

**NEVER** ask follow-up questions one at a time — collect everything in one round.
**NEVER** generate XML before the design step is complete.

**OUTPUT FORMAT — strictly:**
1. 2–3 sentences describing what was built and the key design decisions made.
2. Complete XML.
3. Nothing else. No design rationale, no architecture explanation, no token flow description, no design notes, no testing checklists, no C1–C6 checklist. **The XML ends the response.**

---

## GENERATION WORKFLOW

```
1. Ask roles, data fields, branching, access (anon/internal), notifications — ALL IN ONE message
2. DO NOT generate XML before receiving answers
3. Sketch net + token trace, verify no deadlocks
4. XML order: metadata → caseEvents → roles → data → transitions → places → arcs
5. Run checklist before output
```

---

## XML STRUCTURE

```xml
<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:noNamespaceSchemaLocation="https://petriflow.com/petriflow.schema.xsd">
  <id>process_id</id><version>1.0.0</version><initials>ABC</initials>
  <title>Process Title</title><icon>assignment</icon>
  <defaultRole>true</defaultRole><anonymousRole>false</anonymousRole><transitionRole>false</transitionRole>
  <!-- caseEvents immediately after metadata, BEFORE roles -->
</document>
```

| Config | defaultRole | anonymousRole |
|--------|-------------|---------------|
| Internal | true | false |
| eForm (public) | true | true |
| System-only | false | false |

---

## PROCESS TYPES

| Type | Description |
|------|-------------|
| 1 — Internal | All logged-in, explicit roles |
| 2 — eForm | Public submission + authenticated back-office; `anonymousRole=true`; public task gets `anonymous` roleRef |
| 3 — Automation | Fully system-driven; `defaultRole=false`; only `system` role |
| 4–6 — Mixed/Hybrid | Combinations of above |

---

## ROLES

```xml
<role><id>manager</id><title>Manager</title></role>
```
- Always `<role>`, never `<processRole>`
- `anonymous` / `default` — built-in, only in `<roleRef>`, **never declared as `<role>`** — declaring them causes an import error
- Declare `system` role if any systematic tasks exist

---

## DATA FIELDS

| Type | Components |
|------|-----------|
| `text` | `textarea`, `richtextarea`, `currency` |
| `number` | `currency` |
| `date` / `dateTime` | — |
| `boolean` | — |
| `enumeration` / `enumeration_map` | `select`(default), `list`, `stepper`, `autocomplete`, `dynamic_autocomplete`, `icon` |
| `multichoice` / `multichoice_map` | `select`(default), `list`, `autocomplete` |
| `file` / `fileList` | `preview` |
| `user` / `userList` | — |
| `taskRef` | `<init>transition_id</init>` for static; target must be on `read` arc |
| `caseRef` | list of case string IDs; `allowedNets` restricts linked processes |
| `i18n` | `divider` |
| `button` | `<init>1</init>` required; `<placeholder>` = label; fires `set` event on click without finishing task |

**Option syntax:** `<option key="k">Display</option>` — never `<option><key>k</key><value>v</value></option>`

**Init:** `<value>` for text/number/boolean · `<init>key</init>` for enumeration/taskRef · `<inits><init>k</init></inits>` for multichoice · routing number fields `<init>0</init>` · OR-join `from_X` fields `<init>1</init>`

**`_map` rule:** always use `enumeration_map` / `multichoice_map` for fields read in Groovy — plain variants store display text as key, so `sel.contains("key")` silently returns false when key ≠ display text.

**`icon` component** — requires `enumeration_map`; option display value must be a Material icon name.

**`button` example:**
```xml
<data type="button">
  <id>add_row</id><title/><placeholder>Add</placeholder><init>1</init>
  <event type="set"><id>add_row_set</id>
    <actions phase="pre"><action id="N"><![CDATA[
      rows: f.rows;
      def c = createCase(workspace + "child")
      def tid = c.tasks.find { it.transition == "t1" }?.task
      assignTask(findTask({ it._id.eq(tid) }))
      change rows value { rows.value + tid }
    ]]></action></actions>
  </event>
</data>
```

**`caseRef` allowedNets — Option A (static XML):**
```xml
<data type="caseRef"><id>children</id><title>Children</title>
  <allowedNets><allowedNet>YOUR_SPACE/child_process</allowedNet></allowedNets>
</data>
```
**Option B (dynamic):**
```groovy
children: f.children, pid: f.pid;
change pid value { workspace + "child_process" }
change children allowedNets { [pid.value] }
```

---

## TRANSITION

```xml
<transition>
  <id>submit_request</id><x>208</x><y>208</y><label>Submit Request</label>
  <icon>send</icon><assignPolicy>auto</assignPolicy><priority>1</priority>
  <roleRef><id>role_id</id><logic><perform>true</perform></logic></roleRef>
  <dataGroup>
    <id>group_id</id><cols>2</cols><layout>grid</layout><title>Section</title>
    <dataRef>
      <id>field_id</id>
      <logic><behavior>editable</behavior><behavior>required</behavior></logic>
      <layout><x>0</x><y>0</y><rows>1</rows><cols>1</cols><template>material</template><appearance>outline</appearance></layout>
    </dataRef>
  </dataGroup>
</transition>
```

- Behaviors: `editable` `visible` `required` `optional` `hidden` `forbidden`
- Events direct children of `<transition>` — no `<transitionEvents>` wrapper
- **Exactly one `<dataGroup>`** per transition
- `<assignPolicy>auto</assignPolicy>` before `<priority>` — auto-assigns task on creation
- `<roleRef><logic>`: only `<perform>`, `<cancel>`, `<delegate>` — never `<view>`
- System tasks: `system` roleRef, no dataGroup, fired via `async.run`
- `<priority>1` = shown first; detail/status tasks use higher number (e.g. `2`)

---

## PLACES AND ARCS

```xml
<place><id>start</id><x>112</x><y>208</y><label>Start</label><tokens>1</tokens><static>false</static></place>
<place><id>p0</id>  <x>304</x><y>208</y><label>Pending</label><tokens>0</tokens><static>false</static></place>
```

- Exactly **one** `<tokens>1</tokens>` — the start place. All others: `0`.
- All places: `<static>false</static>`
- Strict alternation: **Place → Transition → Place**

| Arc type | Behavior |
|----------|----------|
| `regular` | Consumes token |
| `read` | Non-consuming — persistent/status places |
| `reset` | Removes all tokens from source |
| `inhibitor` | Fires only when source has 0 tokens |
| `regular` + `<reference>` | Variable arc — multiplicity from `number` field at runtime |

**Variable arc** — no `type="variable"`, always `type="regular"` + `<multiplicity>0</multiplicity>` + `<reference>field_id</reference>`:
```xml
<arc><id>a</id><type>regular</type><sourceId>routing_transition</sourceId>
  <destinationId>after_legal</destinationId><multiplicity>0</multiplicity><reference>toLegal</reference></arc>
```

**Coordinates:** main lane Y=208; branches own Y (e.g. 400, 592); detail lane Y=16; X increments 192px. No two elements at same (x,y).

---

## ACTIONS

| Scope | Placement | Types |
|-------|-----------|-------|
| Case | `<caseEvents>` — after metadata, before roles | `create` `delete` |
| Transition | inside `<transition>` | `assign` `finish` `cancel` `delegate` |
| Data | inside `<dataRef>` or `<data>` | `set` `get` |

**Phases:** `pre` = before event (status, routing flags, validation) · `post` = after event (email, async, API)

**Action structure:**
```groovy
field1: f.field_id1,
field2: f.field_id2,
t_next: t.transition_id;
change field1 value { "New Value" }
make field2, required on t_next when { return field1.value == "x" }
```
- Every field/transition used in body → in import header. `f.field_id` only in header, never in body.
- Action IDs globally unique, sequential across entire document.
- Commas between imports, one semicolon at end.

**`change` vs `setData`:** `change` = synchronous · `setData(task, [...])` = inside `async.run` or cross-case.

**`setData` type strings:** `"text"` · `"number"` · `"boolean"` · `"date"` · `"dateTime"` · `"enumeration"` / `"enumeration_map"` · `"multichoice"` / `"multichoice_map"` · `"user"` · `"taskRef"` · `"caseRef"`

**`make` syntax:**
```groovy
make <field>, <behaviour> on <transition> when { <condition> }
// when { true } / when { false } unconditional; when { return expr } conditional
// optional removes required; make ... on transitions — use sparingly
```

**`async.run`:**
```groovy
async.run {
  def t = findTask { qTask -> qTask.transitionId.eq("id").and(qTask.caseId.eq(useCase.stringId)) }
  if (t) { assignTask(t, userService.loggedOrSystem); finishTask(t, userService.loggedOrSystem) }
}
```
- Always use `async.run` for `assignTask`/`finishTask` inside event actions.
- Exception in one `async.run` block may prevent subsequent blocks — guard with null-checks.

**Date/time — CRITICAL: use `java.time.*`, NOT `java.util.Date`:**

| Field type | Java type | Set current |
|------------|-----------|-------------|
| `date` | `java.time.LocalDate` | `java.time.LocalDate.now()` |
| `dateTime` | `java.time.LocalDateTime` | `java.time.LocalDateTime.now()` |

```groovy
// Date arithmetic
def days = java.time.temporal.ChronoUnit.DAYS.between(
        start.value as java.time.LocalDate, end.value as java.time.LocalDate)
// Business days deadline
def date = request_date.value as java.time.LocalDate
def added = 0
while (added < daysToAdd) {
  date = date.plusDays(1)
  if (date.dayOfWeek != java.time.DayOfWeek.SATURDAY && date.dayOfWeek != java.time.DayOfWeek.SUNDAY) added++
}
change deadline value { date }
```

---

## SERVICES AND OPERATIONS

```groovy
// User
userService.loggedOrSystem.email
userService.loggedOrSystem.transformToUser()

// Case
def c  = findCase  { it.stringId.eq(id) }
def cs = findCases { it.processIdentifier.eq("process_id") }
def c  = createCase("child_id", "Title", "blue")  // full
def c  = createCase("child_id")                   // minimal
def val = c.getFieldValue("field_id")             // alt to c.dataSet["field_id"]?.value
workflowService.deleteCase(c.stringId)
changeCaseProperty("title").about { "New Title" }
changeCaseProperty("color").about { "green" }     // red|orange|yellow|green|teal|cyan|blue|indigo|purple|pink|brown|grey

// ⚠️ QUERYDSL LIMITATION — NEVER filter by dataSet field values inside findCases/findTasks query blocks.
// it.dataSet.get("field").value.eq(...) and it.dataSet.get("field").contains(...) do NOT work — throws MissingPropertyException.
// Valid predicates: processIdentifier, title, _id, stringId, caseId, transitionId, author, color, creationDate.
// To filter by field value: load all cases first, then filter in Groovy with findAll:
//
//   ❌ findCases { it.processIdentifier.eq("proc").and(it.dataSet.get("status").value.eq("Done")) }
//   ✅ findCases { it.processIdentifier.eq("proc") }
//          .findAll { c -> c.dataSet?.get("status")?.value == "Done" }

// Task — single
def t  = findTask  { it.transitionId.eq("id").and(it.caseId.eq(useCase.stringId)) }
def ts = findTasks { it.caseId.in(caseRefField.value).and(it.transitionId.eq("t2")) }
def tid = newCase.tasks.find { it.transition == "t1" }?.task  // first task of new case
assignTask("transition_id")                   // assign to current user, same case
assignTask(task)                              // assign task object to current user
assignTask(task, userService.loggedOrSystem)
finishTask("transition_id")
finishTask(task)
cancelTask(taskObj)

// Task — plural (prefer over .each loops — more efficient)
def tasks = findTasks { it.transitionId.eq("review").and(it.caseId.in(caseIds)) }
assignTasks(tasks)                            // assign list to current user
assignTasks(tasks, userService.loggedOrSystem)
finishTasks(tasks)
finishTasks(tasks, userService.loggedOrSystem)
cancelTasks(tasks)
cancelTasks(tasks, userService.loggedOrSystem)

// getData — read all field values from a task (returns Map<String,Field>)
def task = findTask { it.transitionId.eq("edit_limit").and(it.caseId.eq(useCase.stringId)) }
def data = getData(task)
change my_field value { data["remote_field"].value }
// also: getData(transitionObject) or getData("transitionId", caseObject)

// setData
setData(task, [field: [value: "v", type: "text"]])
setData("transition_id", targetCase, ["field": ["value": "v", "type": "text"]])  // cross-case shorthand

// Role
assignRole("role_id", petriNet); removeRole("role_id", petriNet)

// Email / PDF
sendEmail([email.value], "Subject", "Body")
generatePdf("transition_id", "file_field_id")

// Populate options from cases of another process
def opts = findCases { it.processIdentifier.eq(workspace + "order") }
        .collectEntries { [(it.stringId): "Order: " + it.stringId] }
change my_field options { opts }

// Batch finish all tasks in taskRef list (manual loop with null-check)
taskref: f.taskref;
taskref.value.each { id -> def t = findTask({ it._id.eq(id) }); if (t) finishTask(t) }
```

---

## ROUTING SELECTION

| Situation | Use |
|-----------|-----|
| User picks one of N paths via field | Variable arcs (XOR) — set one `number`=1, rest=0 in `phase="pre"` |
| All N branches must complete | AND-split/join (Pattern 4) |
| User selects 1–N paths via multichoice_map | OR-split via variable arcs (Pattern 6) |
| Decision needs Groovy logic (ranges, lookups) | Systematic tasks (Pattern 14) |
| ❌ NEVER | Two plain regular arcs from same place without `<reference>` |

```
Simple field → Variable arcs
  XOR (one path)  → one number=1, rest=0 → all branches → shared merge PLACE
  OR-split        → multiple numbers=1
    Fixed 2–3 branches → OR-join via incoming variable arcs (from_X init=1)
    Dynamic N, own tasks → Pattern 6b (pre-load go_count tokens)
    Dynamic N, shared task → counter + systematic task
Complex Groovy logic → Systematic tasks
Persistent submitter view → taskRef (Form task on read arc)
Always-accessible status → Detail task + read arc (Pattern 16)
```

---

## GOTCHAS

| # | Rule |
|---|------|
| 1 | `multichoice_map` not `multichoice`; `enumeration_map` not `enumeration` — when field read in Groovy |
| 2 | Variable arc source = **Transition**, never Place |
| 3 | Variable arc reference = **`number` field**, never boolean |
| 4 | Routing flags in **`phase="pre"`** — token moves after pre |
| 5 | **One `<dataGroup>`** per transition — builder silently ignores rest |
| 6 | `caseEvents` only `create`/`delete`; placed **after metadata, before roles** |
| 7 | `task` variable only in transition events, not caseEvents |
| 8 | `setData` / `assignTask` / `finishTask` only inside `async.run` |
| 9 | XOR reconvergence → shared merge **PLACE**, not shared transition (= AND-join = deadlock) |
| 10 | AND-join after OR-split = deadlock → use incoming variable arcs (`from_X`, `init=1`) |
| 11 | Empty `<component/>` = silent bug — omit or set `<name>name</name>` |
| 12 | `f.field_id` only in import header, never in body |
| 13 | Arithmetic in CDATA: `*` must be plain ASCII, never `<em>` or `&times;` |
| 14 | Special chars in XML text: `&`→`&amp;` `<`→`&lt;` `>`→`&gt;` |
| 15 | `taskRef` target must be permanently alive — on a `read` arc |
| 16 | System task chains — every system task fires next via `async.run` in finish post |
| 17 | Every decision option needs: number field + variable arc + destination place |
| 18 | Variable arc `(0)` in modeller — expected, not a bug |
| 19 | AND-split needs dedicated **split transition** (system role) — place with two outgoing arcs = race condition |
| 20 | Exactly one `<?xml?>` prolog (or none — builder format has no prolog) |
| 21 | `<dataGroup>` order: `<id>` → `<cols>` → `<layout>grid</layout>` → `<title>` |
| 22 | All field reads in `caseEvents` require import header |
| 23 | Decision inside task → variable arcs from that transition; never two regular arcs from preceding place |
| 24 | Cancel from multiple states → one cancel transition per state |
| 25 | `from_X` OR-join fields: `<init>1</init>` — if `0`, join fires immediately at case creation |
| 26 | Loop revision arc → back to **same input place** as main flow, not new merge place |
| 27 | `assignTask`/`finishTask` only when target transition is enabled (input place has token) |
| 28 | `setData` self-copy is no-op — all tasks in same case share field values |
| 29 | `button` field: `<init>1</init>` required; `<placeholder>` = label; `editable` in dataGroup |
| 30 | `createCase(id)` minimal form valid; get first task via `newCase.tasks.find { it.transition=="t1" }?.task` |
| 31 | `workflowService.deleteCase(stringId)` — deletes case programmatically |
| 32 | `findTasks { it.caseId.in(list) }` — `.in()` filters across multiple case IDs |
| 33 | `case.getFieldValue("id")` — alternative to `case.dataSet["id"]?.value` |
| 34 | `workspace` — eTask-specific prefix variable; use literal process ID on standalone NAE |
| 35 | **Empty `<caseEvents>` block causes eTask import error** — if there are no real actions, omit the entire `<caseEvents>` block. A block with only a comment or empty action is invalid. |
| 36 | **Permanently open task: read arc only, no outgoing arc** — a task that must never finish needs only a `read` arc from a place with `tokens=1`. Never add a regular arc from the same place — it makes the task finishable and breaks the pattern. |
| 37 | **`taskRef` `<init>` is for static single-task embeds only** — for a dynamic list built via button+createCase, leave `<init>` empty. Set the taskRef value dynamically via `change field value { list + tid }` in the button action. |

---

## CHECKLIST

| Category | Items |
|----------|-------|
| **Roles and fields** | All roles `<role>`; `system` declared if needed; `anonymous`/`default` only in roleRef; selection fields have `<options>`; `_map` variants for Groovy reads; no empty `<component/>`; `taskRef` target on `read` arc |
| **IDs and refs** | All IDs unique lowercase_underscore; every `<dataRef>` matches `<data>`; every `<roleRef>` matches `<role>` or built-in; all arc sourceId/destinationId exist |
| **Actions** | IDs globally unique sequential; all code in CDATA; every field/transition in body → in header; no `f.field_id` in body; commas + one semicolon; `setData`/`assignTask`/`finishTask` in `async.run`; no `task` in caseEvents; one field+one transition per `make`; routing flags `phase="pre"`; no HTML in CDATA; `make when` uses `return` for conditions |
| **caseEvents** | Only `create`/`delete`; placed after metadata, before roles |
| **XML** | Order: metadata→caseEvents→roles→data→transitions→places→arcs; `<dataGroup>` sequence correct; special chars escaped; Y=208 main lane, branches distinct Y, X+192; no duplicate (x,y); one `tokens=1`; P→T→P arcs; all places `<static>false</static>` |
| **Design** | Token trace: no stuck states/race conditions; XOR → merge PLACE; no AND-join after OR-split; every option has branch; `from_X` init=1; var arc source=Transition; var arc ref=number; system tasks bootstrapped; AND-split uses split transition; loop arc → same place as main flow; detail view: regular arc→p_detail + read arc→detail_task |

---

## OUTPUT AND TESTING

1. https://builder.netgrif.cloud/modeler — drag and drop XML
2. https://etask.netgrif.cloud/ — test full functionality

| Error | Cause | Fix |
|-------|-------|-----|
| "Invalid namespace" | Wrong xmlns | Use exact namespace |
| "Duplicate ID" | Shared ID | Make all IDs unique |
| "No start place" | No tokens=1 | Exactly one start place |
| "Not a number. Cannot change arc weight" | `<reference>` on boolean | Change to number, use 1/0 |
| Variable arc P→P error | sourceId is a Place | Move source to routing transition |
| `sel.contains()` always false | plain multichoice/enumeration | Use `_map` variant |
| OR-split deadlock | AND-join place after OR-split | Incoming variable arcs on join |
| Parallel review race | Place with two outgoing arcs | Dedicated split transition |
| Time trigger never fires | Not system-role transition | Assign `system` role, remove dataGroup |
| `taskRef` panel blank | Referenced task dead | Point only at tasks on `read` arc |
| eTask arithmetic error | `*` as `<em>` | Plain ASCII `*` in CDATA |

---

# PATTERNS

## Pattern 1 — Simple Sequence

```
[start:1] → [submit] → [p0:0] → [review] → [p1:0] → [approve] → [done:0]
```

```xml
<place><id>start</id><x>112</x><y>208</y><label>Start</label><tokens>1</tokens><static>false</static></place>
<place><id>p0</id>  <x>304</x><y>208</y><label>Pending</label><tokens>0</tokens><static>false</static></place>
<place><id>p1</id>  <x>688</x><y>208</y><label>Reviewed</label><tokens>0</tokens><static>false</static></place>
<place><id>done</id><x>1072</x><y>208</y><label>Done</label>  <tokens>0</tokens><static>false</static></place>
<arc><id>a1</id><type>regular</type><sourceId>start</sourceId> <destinationId>submit</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>a2</id><type>regular</type><sourceId>submit</sourceId><destinationId>p0</destinationId>     <multiplicity>1</multiplicity></arc>
<arc><id>a3</id><type>regular</type><sourceId>p0</sourceId>    <destinationId>review</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>a4</id><type>regular</type><sourceId>review</sourceId><destinationId>p1</destinationId>     <multiplicity>1</multiplicity></arc>
<arc><id>a5</id><type>regular</type><sourceId>p1</sourceId>    <destinationId>approve</destinationId><multiplicity>1</multiplicity></arc>
<arc><id>a6</id><type>regular</type><sourceId>approve</sourceId><destinationId>done</destinationId>  <multiplicity>1</multiplicity></arc>
```

---

## Pattern 2 — Approval with Rejection (single decision task + variable arcs)

> ✅ One task with decision field + variable arcs. ❌ Never two separate Approve/Reject tasks competing for same place token.

```
                  ┌─(ref:go_approve)→ [approved:0]
[p0:0] → [review] ┤
                  └─(ref:go_reject)→  [rejected:0]
```

```xml
<data type="number"><id>go_approve</id><title>Go Approve</title><init>0</init></data>
<data type="number"><id>go_reject</id><title>Go Reject</title><init>0</init></data>
<data type="enumeration_map"><id>decision</id><title>Decision</title>
<options><option key="approve">Approve</option><option key="reject">Reject</option></options>
</data>
<place><id>approved</id><x>880</x><y>112</y><label>Approved</label><tokens>0</tokens><static>false</static></place>
<place><id>rejected</id><x>880</x><y>304</y><label>Rejected</label><tokens>0</tokens><static>false</static></place>
<arc><id>a_app</id><type>regular</type><sourceId>review</sourceId><destinationId>approved</destinationId><multiplicity>0</multiplicity><reference>go_approve</reference></arc>
<arc><id>a_rej</id><type>regular</type><sourceId>review</sourceId><destinationId>rejected</destinationId><multiplicity>0</multiplicity><reference>go_reject</reference></arc>
```

```groovy
// review finish PRE
go_approve: f.go_approve, go_reject: f.go_reject, decision: f.decision;
if (decision.value == "approve") { change go_approve value { 1 }; change go_reject value { 0 } }
else                              { change go_approve value { 0 }; change go_reject value { 1 } }
```

**Dynamic field reveal on decision change (`event type="set"` on decision dataRef):**
```xml
<dataRef>
  <id>decision</id>
  <logic><behavior>editable</behavior><behavior>required</behavior></logic>
  <layout>...</layout>
  <event type="set"><id>decision_set</id>
    <actions phase="post"><action id="N"><![CDATA[
      t_review: t.review, rejection_reason: f.rejection_reason, decision: f.decision;
      if (decision.value == "reject") { make rejection_reason, editable on t_review when { true } }
      else                            { make rejection_reason, hidden   on t_review when { true } }
    ]]></action></actions>
  </event>
</dataRef>
```

---

## Pattern 4 — AND-split / AND-join (parallel branches)

```
                   ┌→ [legal_p:0] → [review_legal] → [legal_done:0] ─┐
[p0:0] → [split,sys] ┤                                                   [join:0] →(mult=2)→ [finalize]
                   └→ [finance_p:0]→[review_finance]→[finance_done:0]─┘
```

> ⚠️ Place with two outgoing arcs = race condition, not AND-split. Split is a system task.

```xml
<transition><id>split</id><x>304</x><y>208</y><label>Split</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
</transition>
<place><id>legal_p</id>     <x>496</x><y>112</y><label>Legal Queue</label>   <tokens>0</tokens><static>false</static></place>
<place><id>finance_p</id>   <x>496</x><y>304</y><label>Finance Queue</label> <tokens>0</tokens><static>false</static></place>
<place><id>legal_done</id>  <x>880</x><y>112</y><label>Legal Done</label>    <tokens>0</tokens><static>false</static></place>
<place><id>finance_done</id><x>880</x><y>304</y><label>Finance Done</label>  <tokens>0</tokens><static>false</static></place>
<place><id>join</id>        <x>1072</x><y>208</y><label>Both Done</label>    <tokens>0</tokens><static>false</static></place>
<arc><id>a1</id><type>regular</type><sourceId>p0</sourceId>            <destinationId>split</destinationId>        <multiplicity>1</multiplicity></arc>
<arc><id>a2</id><type>regular</type><sourceId>split</sourceId>         <destinationId>legal_p</destinationId>      <multiplicity>1</multiplicity></arc>
<arc><id>a3</id><type>regular</type><sourceId>split</sourceId>         <destinationId>finance_p</destinationId>    <multiplicity>1</multiplicity></arc>
<arc><id>a4</id><type>regular</type><sourceId>legal_p</sourceId>       <destinationId>review_legal</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>a5</id><type>regular</type><sourceId>finance_p</sourceId>     <destinationId>review_finance</destinationId><multiplicity>1</multiplicity></arc>
<arc><id>a6</id><type>regular</type><sourceId>review_legal</sourceId>  <destinationId>legal_done</destinationId>   <multiplicity>1</multiplicity></arc>
<arc><id>a7</id><type>regular</type><sourceId>review_finance</sourceId><destinationId>finance_done</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>a8</id><type>regular</type><sourceId>legal_done</sourceId>    <destinationId>join</destinationId>         <multiplicity>1</multiplicity></arc>
<arc><id>a9</id><type>regular</type><sourceId>finance_done</sourceId>  <destinationId>join</destinationId>         <multiplicity>1</multiplicity></arc>
<arc><id>a10</id><type>regular</type><sourceId>join</sourceId>         <destinationId>finalize</destinationId>     <multiplicity>2</multiplicity></arc>
```

```groovy
// preceding task finish POST
async.run { assignTask("split"); finishTask("split") }
```

---

## Pattern 4a — AND-split with approve/reject in each branch

```
[review_legal]  ─(ref:legal_approve)→ [legal_done:0] ─┐
                └(ref:legal_reject)→  [rejected:0]      [join:0] →(mult=2)→ [final]
[review_finance]─(ref:fin_approve)→  [finance_done:0]─┘
                └(ref:fin_reject)→   [rejected:0]
```

Each review branch has routing number fields + variable arcs. Apply Pattern 2 routing action to each review transition independently. Arc structure per branch:
```xml
<data type="number"><id>legal_approve</id><title>Legal Approve</title><init>0</init></data>
<data type="number"><id>legal_reject</id><title>Legal Reject</title><init>0</init></data>
        <!-- same for fin_approve, fin_reject -->
<arc><id>arc_la</id><type>regular</type><sourceId>review_legal</sourceId><destinationId>legal_done</destinationId><multiplicity>0</multiplicity><reference>legal_approve</reference></arc>
<arc><id>arc_lr</id><type>regular</type><sourceId>review_legal</sourceId><destinationId>rejected</destinationId>  <multiplicity>0</multiplicity><reference>legal_reject</reference></arc>
        <!-- same arcs for review_finance → fin_approve/fin_reject -->
```

> ⚠️ If either branch rejects, its token goes to `rejected` — other branch token gets stuck in `join`. Expected: rejection terminates immediately.

---

## Pattern 5 — XOR-split / XOR-merge (exclusive choice)

```
              ┌─(ref:toA)→ [path_a:0] → [task_a] ─┐
[p0:0] → [router] ┤                                   [merge:0] → [finalize]
              └─(ref:toB)→ [path_b:0] → [task_b] ─┘
```

> ⚠️ Both branches converge into a shared merge **PLACE** — not directly into a shared transition (= AND-join = deadlock).

```xml
<data type="number"><id>toA</id><title>To A</title><init>0</init></data>
<data type="number"><id>toB</id><title>To B</title><init>0</init></data>
<arc><id>arc_a</id>  <type>regular</type><sourceId>router</sourceId><destinationId>path_a</destinationId><multiplicity>0</multiplicity><reference>toA</reference></arc>
<arc><id>arc_b</id>  <type>regular</type><sourceId>router</sourceId><destinationId>path_b</destinationId><multiplicity>0</multiplicity><reference>toB</reference></arc>
<arc><id>arc_am</id> <type>regular</type><sourceId>task_a</sourceId><destinationId>merge</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>arc_bm</id> <type>regular</type><sourceId>task_b</sourceId><destinationId>merge</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>arc_mf</id> <type>regular</type><sourceId>merge</sourceId> <destinationId>finalize</destinationId><multiplicity>1</multiplicity></arc>
```

```groovy
// router finish PRE
toA: f.toA, toB: f.toB, decision: f.decision;
if (decision.value == "path_a") { change toA value { 1 }; change toB value { 0 } }
else                             { change toA value { 0 }; change toB value { 1 } }
```

---

## Pattern 6 — OR-split / OR-join via incoming variable arcs (2–3 fixed branches)

```
              ┌─(ref:to_legal)→  [p_legal_in:0] → [legal_task] → [p_legal_out:0] ─┐
[t_register] ─┤                                                                       [t_final]
              └─(ref:to_finance)→[p_finance_in:0]→[finance_task]→[p_finance_out:0]─┘
```

> ❌ Never AND-join place after OR-split — deadlocks when user selects only one branch.
> ✅ Incoming variable arcs on join; mirror `to_X` as `from_X` with `<init>1</init>`.

```xml
<data type="number"><id>to_legal</id>   <title>To Legal</title>   <init>0</init></data>
<data type="number"><id>to_finance</id> <title>To Finance</title> <init>0</init></data>
<data type="number"><id>from_legal</id>   <title>From Legal</title>   <init>1</init></data>
<data type="number"><id>from_finance</id> <title>From Finance</title> <init>1</init></data>
<data type="multichoice_map"><id>departments</id><title>Departments</title>
<options><option key="legal">Legal</option><option key="finance">Finance</option></options>
</data>
        <!-- OR-split arcs -->
<arc><id>a_vl</id><type>regular</type><sourceId>t_register</sourceId><destinationId>p_legal_in</destinationId>  <multiplicity>0</multiplicity><reference>to_legal</reference></arc>
<arc><id>a_vf</id><type>regular</type><sourceId>t_register</sourceId><destinationId>p_finance_in</destinationId><multiplicity>0</multiplicity><reference>to_finance</reference></arc>
        <!-- OR-join incoming variable arcs -->
<arc><id>a_jl</id><type>regular</type><sourceId>p_legal_out</sourceId>  <destinationId>t_final</destinationId><multiplicity>1</multiplicity><reference>from_legal</reference></arc>
<arc><id>a_jf</id><type>regular</type><sourceId>p_finance_out</sourceId><destinationId>t_final</destinationId><multiplicity>1</multiplicity><reference>from_finance</reference></arc>
```

```groovy
// t_register finish PRE — set BOTH split and join fields together
to_legal: f.to_legal, to_finance: f.to_finance, from_legal: f.from_legal, from_finance: f.from_finance, departments: f.departments;
def sel = departments.value as Set
def gl = sel.contains("legal") ? 1 : 0; def gf = sel.contains("finance") ? 1 : 0
change to_legal value { gl as Double }; change to_finance value { gf as Double }
change from_legal value { gl as Double }; change from_finance value { gf as Double }
```

---

## Pattern 6b — OR-split with per-department tasks and dynamic join (go_count)

Use when N branches is dynamic. Pre-load `go_count` tokens into merge place; each branch deposits 1 back; variable arc on join consumes `go_count`.

```xml
<data type="number"><id>go_count</id><title>Branch Count</title><init>0</init></data>
<data type="number"><id>to_a</id><title>To A</title><init>0</init></data>
        <!-- OR-split + pre-load -->
<arc><id>a_ta</id>   <type>regular</type><sourceId>t_register</sourceId><destinationId>p_a</destinationId>    <multiplicity>0</multiplicity><reference>to_a</reference></arc>
<arc><id>a_pre</id>  <type>regular</type><sourceId>t_register</sourceId><destinationId>p_merge</destinationId><multiplicity>0</multiplicity><reference>go_count</reference></arc>
        <!-- Each branch task → merge -->
<arc><id>a_am</id>   <type>regular</type><sourceId>task_a</sourceId>   <destinationId>p_merge</destinationId><multiplicity>1</multiplicity></arc>
        <!-- join -->
<arc><id>a_mf</id>   <type>regular</type><sourceId>p_merge</sourceId>  <destinationId>t_final</destinationId><multiplicity>0</multiplicity><reference>go_count</reference></arc>
```

```groovy
// t_register finish PRE
go_count: f.go_count, to_a: f.to_a, departments: f.departments;
def sel = departments.value as Set
def aGo = sel.contains("dept_a") ? 1 : 0
change to_a value { aGo as Double }
change go_count value { sel.size() as Double }
```

---

## Pattern 7 — Loop / Revision

```
[start:1] → [submit] → [p0:0] → [review] ─(ref:go_approve)→ [approved:0]
                ↑                          └(ref:go_remake)→  [start] (loop back)
```

Loop arc back to `start` with breakpoints:
```xml
<arc><id>a_loop</id><type>regular</type><sourceId>review</sourceId><destinationId>start</destinationId>
  <multiplicity>0</multiplicity><reference>go_remake</reference>
  <breakpoint><x>432</x><y>368</y></breakpoint><breakpoint><x>112</x><y>368</y></breakpoint>
</arc>
```

> ⚠️ Loop arc returns to the **same place** as the main flow — a place with two incoming regular arcs fires when **any one** arrives (OR semantics, not AND). Do NOT create a new merge place or give the shared task two input places (= AND-join = deadlock).

---

## Pattern 8 — Time Trigger (SLA enforcement)

```
[p_pending:0] → [t_review, reviewer]     (human — consumes token when assigned)
              → [t_sla_check, system, PT24H] (auto-fires after 24h if token still there)
```

```xml
<transition><id>t_sla_check</id><x>496</x><y>400</y><label>SLA Check</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
  <trigger type="time"><delay>PT24H</delay></trigger>
</transition>
```

Durations: `PT5S` · `PT30M` · `PT2H` · `PT24H` · `P1D` · `P7D`

> ⚠️ Time triggers only on **system-role** transitions. Human task consuming the token automatically disables the timer.

```groovy
// t_sla_check finish PRE
go_reviewed: f.go_reviewed, go_escalated: f.go_escalated, status: f.status;
if ((go_reviewed.value as Integer) == 0) {
  change go_escalated value { 1 }; change status value { "SLA breached" }
  changeCaseProperty("color").about { "red" }
}
// t_review finish PRE
go_reviewed: f.go_reviewed, status: f.status;
change go_reviewed value { 1 }; change status value { "Reviewed within SLA" }
```

---

## Pattern 9 — Parallel Race (first completion wins)

```
              ┌→ [p_a:0] → [review_a] ─(ref:go_final)→ [p_merge:0] → [t_final]
[split] ──────┤                        └(ref:go_dead)→  [p_dead:0]
              └→ [p_b:0] → [review_b] ─(ref:go_final)→ [p_merge:0]
                                       └(ref:go_dead)→  [p_dead:0]
```

```groovy
// each reviewer finish PRE
first_done: f.first_done, go_final: f.go_final, go_dead: f.go_dead;
def flag = (first_done.value as Integer) ?: 0
if (flag == 0) { change first_done value { 1 }; change go_final value { 1 }; change go_dead value { 0 } }
else           { change go_final value { 0 }; change go_dead value { 1 } }
```

`p_dead` has no outgoing arcs — tokens arriving there are permanently absorbed.

---

## Pattern 10 — Voting / Consensus (N of M)

```groovy
// each reviewer finish POST
vote_count: f.vote_count;
def current = (vote_count.value as Integer) ?: 0
change vote_count value { (current + 1) as Double }
if (current + 1 >= 2) {
  findTasks { it.caseId.eq(useCase.stringId).and(it.transitionId.in(["review_a","review_b","review_c"])) }
          .each { t -> cancelTask(t) }
}
```

Join arc uses `<multiplicity>2</multiplicity>` (static quorum). For **dynamic quorum** — store count in `number` field + use as variable arc `<reference>` on join arc.

---

## Pattern 11 — Four-Eyes Principle

```groovy
// first_approval finish POST
first_approver: f.first_approver;
change first_approver value { userService.loggedOrSystem.email }
// second_approval assign PRE
first_approver: f.first_approver;
if (userService.loggedOrSystem.email == first_approver.value)
  throw new java.lang.IllegalStateException("Second approver must be different.")
```

---

## Pattern 13 — Variable Arcs XOR fork (full example)

```
[p0:0] → [triage] ─(ref:toLegal)→ [after_legal:0] → [legal_task]
                  └(ref:toPR)→    [after_pr:0]    → [pr_task]
```

```xml
<data type="number"><id>toLegal</id><title>To Legal</title><init>0</init></data>
<data type="number"><id>toPR</id><title>To PR</title><init>0</init></data>
<place><id>after_legal</id><x>688</x><y>112</y><label>After Legal</label><tokens>0</tokens><static>false</static></place>
<place><id>after_pr</id>  <x>688</x><y>304</y><label>After PR</label>   <tokens>0</tokens><static>false</static></place>
<arc><id>arc_l</id><type>regular</type><sourceId>triage</sourceId><destinationId>after_legal</destinationId><multiplicity>0</multiplicity><reference>toLegal</reference></arc>
<arc><id>arc_p</id><type>regular</type><sourceId>triage</sourceId><destinationId>after_pr</destinationId>   <multiplicity>0</multiplicity><reference>toPR</reference></arc>
```

```groovy
// triage finish PRE
toLegal: f.toLegal, toPR: f.toPR, legal_required: f.legal_required;
if (legal_required.value == "yes") { change toLegal value { 1 }; change toPR value { 0 } }
else                               { change toLegal value { 0 }; change toPR value { 1 } }
```

---

## Pattern 14 — Systematic Tasks (code-driven routing)

Both system tasks share `p0` — only the one fired by action consumes the token.

```xml
<transition><id>route_to_legal</id><x>496</x><y>112</y><label>To Legal</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef></transition>
<transition><id>route_to_pr</id><x>496</x><y>304</y><label>To PR</label>
<roleRef><id>system</id><logic><perform>true</perform></logic></roleRef></transition>
```

```groovy
// preceding task finish POST
amount: f.amount;
if ((amount.value as Double) < 2000) { async.run { assignTask("route_to_legal"); finishTask("route_to_legal") } }
else                                  { async.run { assignTask("route_to_pr");    finishTask("route_to_pr") } }
// system task chain — each fires next:
async.run {
  def t = findTask { it.transitionId.eq("next_sys_task").and(it.caseId.eq(useCase.stringId)) }
  if (t) { assignTask(t, userService.loggedOrSystem); finishTask(t, userService.loggedOrSystem) }
}
```

---

## Pattern 15 — taskRef (embedded form panel)

```xml
<data type="taskRef"><id>form_ref</id><title/><init>form_task</init></data>
<transition><id>form_task</id><x>304</x><y>16</y><label>Form</label>
<roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
<dataGroup><id>form_group</id><cols>2</cols><layout>grid</layout><title>Request</title>
  <dataRef><id>field_id</id><logic><behavior>editable</behavior></logic>
    <layout><x>0</x><y>0</y><rows>1</rows><cols>2</cols><template>material</template><appearance>outline</appearance></layout>
  </dataRef>
</dataGroup>
</transition>
<place><id>p_form</id><x>112</x><y>16</y><label>Form</label><tokens>1</tokens><static>false</static></place>
<arc><id>arc_form</id><type>read</type><sourceId>p_form</sourceId><destinationId>form_task</destinationId><multiplicity>1</multiplicity></arc>
```

Embed in any task as `visible` dataRef. Target must be permanently alive (on `read` arc).

**Dynamic taskRef from child cases:**
```groovy
invoice_approvals: f.invoice_approvals, children: f.children;
change invoice_approvals value {
  findTasks { it.caseId.in(children.value).and(it.transitionId.eq("t2")) }?.collect { it.stringId }
}
```

---

## Pattern 15b — caseRef (linked case tracker)

```xml
<data type="caseRef"><id>children</id><title>Children</title>
  <allowedNets><allowedNet>YOUR_SPACE/child_process</allowedNet></allowedNets>
</data>
```

```groovy
// add child case ID (guard duplicate)
children: f.children, new_id: f.new_id;
if (new_id.value !in (children.value ?: [])) {
  change children value { (children.value ?: []) + new_id.value }
}
```

---

## Pattern 16 — Persistent Detail View (read arc)

```
[submit] → [p_detail:0] ─(read)→ [detail_view]   (token not consumed, always enabled)
```

```xml
<place><id>p_detail</id><x>304</x><y>16</y><label>Detail</label><tokens>0</tokens><static>false</static></place>
<arc><id>arc_sd</id><type>regular</type><sourceId>submit</sourceId>  <destinationId>p_detail</destinationId>   <multiplicity>1</multiplicity></arc>
<arc><id>arc_dr</id><type>read</type>   <sourceId>p_detail</sourceId><destinationId>detail_view</destinationId><multiplicity>1</multiplicity></arc>
```

Status updates in `phase="pre"` so detail view reflects new state atomically. Reveal fields progressively:
```groovy
// later task finish PRE
t_detail: t.detail_view, result_field: f.result_field;
make result_field, visible on t_detail when { true }
```

---

## Pattern 16b — Permanently Open Task (never finishes)

Use when a task must stay open indefinitely — a dynamic list, a dashboard, or any task that aggregates items from other processes via a button.

```
[p_list:1] ─(read)→ [t_list]    NO outgoing arc — task can never finish
```

```xml
<place><id>p_list</id><x>112</x><y>208</y><label>List</label><tokens>1</tokens><static>false</static></place>
<arc><id>arc_read</id><type>read</type><sourceId>p_list</sourceId><destinationId>t_list</destinationId><multiplicity>1</multiplicity></arc>
        <!-- NO outgoing arc from t_list -->
```

> ⚠️ **Never pair a regular arc with a read arc on a permanently open task.**
> `start(tokens=1) ──regular──→ t_list ──regular──→ p_list ──read──→ t_list` makes the task finishable — once fired, the token leaves `start` and the task behaviour changes. A permanently open task must have **only** a read arc from a place that permanently holds `tokens=1`.

**Button + createCase on a permanently open task:**
```groovy
// button set PRE
item_tasks: f.item_tasks;
def c   = createCase("item_process", "New Item", "blue", userService.loggedOrSystem)
def tid = c.tasks.find { it.transition == "t_item_form" }?.task
change item_tasks value { (item_tasks.value ?: []) + tid }
```

- `c.tasks.find { it.transition == "t_item_form" }?.task` — gets task string ID directly, no `findTask` query needed
- Do NOT call `assignTask`/`finishTask` on the item task — adding its ID to taskRef is enough to embed it
- The item process task must use `system` role so it can be embedded without human role assignment
- **`taskRef` `<init>` is for static single-task embeds only** — for a dynamic list, leave `<init>` empty and manage the list via `change item_tasks value { ... }` in the button action

---

## Inter-Process Communication

> **Each process is a completely isolated namespace.** Roles, fields, transitions, and places from one process are invisible to all others — they cannot be referenced in XML from another process. Cross-process interaction happens exclusively through action code (`findCase`, `findTask`, `setData`, `assignTask`, `finishTask`). When generating a multi-process application, always produce each process as a fully independent `<document>` with its own complete set of roles, fields, and structure. Never reference a role ID, field ID, or transition ID from another process in XML.

### IPC-0 — Cross-process taskRef update via permanently alive system task

The most reliable way to embed tasks from Process B into a `taskRef` field in Process A.

**Setup in Process A (Order):** a system task on a read arc, token arrives when case reaches open state:
```xml
<arc><id>a1</id><type>regular</type><sourceId>t_approve</sourceId><destinationId>p_panel</destinationId><multiplicity>0</multiplicity><reference>go_approve</reference></arc>
<arc><id>a2</id><type>read</type><sourceId>p_panel</sourceId><destinationId>t_invoice_panel</destinationId><multiplicity>1</multiplicity></arc>
<!-- t_invoice_panel: system role, no dataGroup — only a setData target -->
```

**Process B (Invoice) appends its task ID to Process A's taskRef:**
```groovy
// t_notify finish post
parent_order_id: f.parent_order_id;
def orderCase = findCase { it._id.eq(parent_order_id.value) }
if (orderCase) {
    def myTask = findTask { it.transitionId.eq("t_invoice_approval").and(it.caseId.eq(useCase.stringId)) }
    if (myTask) {
        def currentList = orderCase.dataSet?.get("linked_invoices")?.value ?: []
        setData("t_invoice_panel", orderCase, [
            "linked_invoices": ["value": currentList + myTask.stringId, "type": "taskRef"]
        ])
    }
}
```

**Invoice approval task must be permanently open (read arc) so it stays embeddable:**
```xml
<arc><id>a3</id><type>read</type><sourceId>p_approval</sourceId><destinationId>t_invoice_approval</destinationId><multiplicity>1</multiplicity></arc>
```

> ⚠️ Do NOT use trigger field + UUID pattern for cross-process taskRef updates — it relies on QueryDSL dataSet filtering which fails at runtime. Do NOT call `setData` via a human task transition ID — human tasks may not be active. Always target a permanently alive **system-role** task.

---

### IPC-1 — Parent creates child, gets first task immediately

```groovy
// button set PRE or task finish POST
rows: f.rows, taskref: f.taskref;
def c   = createCase(workspace + "child")
def tid = c.tasks.find { it.transition == "t1" }?.task
assignTask(findTask({ it._id.eq(tid) }))
setData("t1", c, ["id_parent": ["value": useCase.stringId, "type": "text"]])
change rows    value { rows.value + c.stringId }
change taskref value { taskref.value + tid }
```

---

### IPC-2 — Child notifies parent (setData → data event set)

**Child finish POST:**
```groovy
invoice_id: f.invoice_id, parent_id: f.parent_id;
def parent = findCase({ it._id.eq(parent_id.value) })
setData("t1", parent, ["new_invoice_id": ["value": invoice_id.value, "type": "text"]])
```

**Parent — `new_invoice_id` data field with set event:**
```xml
<data type="text"><id>new_invoice_id</id><title>New Invoice ID</title>
  <event type="set"><id>nii_set</id>
    <actions phase="post"><action id="N"><![CDATA[
      approvals: f.approvals, children: f.children, new_invoice_id: f.new_invoice_id;
      if (new_invoice_id.value !in (children.value ?: []))
        change children value { (children.value ?: []) + new_invoice_id.value }
      change approvals value {
        findTasks { it.caseId.in(children.value).and(it.transitionId.eq("t2")) }?.collect { it.stringId }
      }
    ]]></action></actions>
  </event>
</data>
```

---

### IPC-3 — Populate enumeration_map from cases of another process

```groovy
// task assign PRE
parent_id: f.parent_id;
change parent_id options {
  findCases { it.processIdentifier.eq(workspace + "order") }
    .collectEntries { [(it.stringId): "Order: " + it.stringId] }
}
```

---

### IPC-4 — caseRef allowedNets (static vs dynamic)

Static XML: `<allowedNets><allowedNet>SPACE/process</allowedNet></allowedNets>` on `<data type="caseRef">`.

Dynamic: `change children allowedNets { [workspace + "process"] }` in caseEvents create post.

---

### IPC-5 — Delete child cases (button-driven)

```groovy
// delete button set PRE
taskref: f.taskref, caseref: f.caseref, total: f.total, unit_price: f.unit_price;
taskref.value.each { tid ->
  def t = findTask({ it._id.eq(tid) })
  def c = findCase { it._id.eq(t.caseId) }
  if (c.getFieldValue("delete_flag") == true) {
    change taskref value { taskref.value - tid }
    change caseref value { caseref.value - c.stringId }
    workflowService.deleteCase(c.stringId)
  }
}
change total value { unit_price.value * taskref.value.size() }
```

---

## Common Snippets

**Status + email:**
```groovy
status: f.status; change status value { "Approved" }  // finish PRE
email: f.email, status: f.status;                      // finish POST
sendEmail([email.value], "Update: ${useCase.title}", "Status: ${status.value}")
```

**Record approver:**
```groovy
approved_by: f.approved_by, approved_at: f.approved_at;
change approved_by value { userService.loggedOrSystem.email }
change approved_at value { java.time.LocalDateTime.now() }
```

**Conditional field visibility:**
```groovy
t_submit: t.submit_request, contract_type: f.contract_type, iban: f.iban;
make iban, required on t_submit when { return contract_type.value == "bank_transfer" }
make iban, hidden   on t_submit when { return contract_type.value != "bank_transfer" }
```

**Auto-trigger system task on case create:**
```xml
<caseEvents>
  <event type="create"><id>on_create</id>
    <actions phase="post"><action id="1"><![CDATA[
      async.run { assignTask("init_task"); finishTask("init_task") }
    ]]></action></actions>
  </event>
</caseEvents>
```

**Section divider:**
```xml
<data type="i18n"><id>div_1</id><title>Section</title><init>Section</init></data>
<dataRef><id>div_1</id><logic><behavior>editable</behavior></logic>
  <layout><x>0</x><y>3</y><rows>1</rows><cols>4</cols><template>material</template><appearance>outline</appearance></layout>
  <component><name>divider</name></component></dataRef>
```

**Dynamic options from external API:**
```groovy
country: f.country, city: f.city;
def conn = (java.net.HttpURLConnection) new java.net.URL(
  "https://api.example.com/cities?country=${country.value}").openConnection()
conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(10000)
def result = new groovy.json.JsonSlurper().parseText(conn.getInputStream().getText("UTF-8"))
change city options { result.collectEntries { [(it.code): it.name] } }
```

**LLM API call + JSON fence stripping:**
```groovy
api_key: f.api_key, prompt: f.prompt, result: f.result;
async.run {
  def conn = (java.net.HttpURLConnection) new java.net.URL("https://api.openai.com/v1/chat/completions").openConnection()
  conn.setRequestMethod("POST")
  conn.setRequestProperty("Content-Type", "application/json")
  conn.setRequestProperty("Authorization", "Bearer ${api_key.value}")
  conn.setDoOutput(true); conn.setConnectTimeout(15_000); conn.setReadTimeout(60_000)
  conn.outputStream.write(groovy.json.JsonOutput.toJson([
          model: "gpt-4o-mini", messages: [[role: "user", content: prompt.value ?: ""]]
  ]).getBytes("UTF-8"))
  def body = ""
  try { body = conn.inputStream.getText("UTF-8") }
  catch (e) { body = conn.errorStream?.getText("UTF-8") ?: '{"error":"unreachable"}' }
  def content = new groovy.json.JsonSlurper().parseText(body)?.choices?.getAt(0)?.message?.content ?: "{}"
  def normalized = content.replaceAll('(?m)^```(?:json)?\\s*','').replaceAll('(?m)^```\\s*$','').trim()
  def t = findTask { it.transitionId.eq("result_task").and(it.caseId.eq(useCase.stringId)) }
  if (t) setData(t, [result: [value: normalized, type: "text"]])
}
```

> ⚠️ API key: `text` field with `<value>sk-YOUR-KEY-HERE</value>`, `hidden` on all user-facing transitions.