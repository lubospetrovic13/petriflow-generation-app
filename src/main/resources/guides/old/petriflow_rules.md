# Petriflow Rules & Checklist

---

## GENERATION WORKFLOW

```
1. Ask about roles, data fields, branching, access (anon/internal), notifications — ALL IN ONE message
2. DO NOT generate XML before receiving answers
3. Sketch net + token trace, verify no deadlocks
4. Generate XML in strict order: metadata → caseEvents → roles → data → transitions → places → arcs
5. Run checklist before output
```

---

## XML STRUCTURE

```xml
<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:noNamespaceSchemaLocation="https://petriflow.com/petriflow.schema.xsd">
    <id>process_id</id>
    <version>1.0.0</version>
    <initials>ABC</initials>
    <title>Process Title</title>
    <icon>assignment</icon>
    <defaultRole>true</defaultRole>
    <anonymousRole>false</anonymousRole>
    <transitionRole>false</transitionRole>

    <!-- caseEvents immediately after metadata flags, BEFORE roles -->
    <caseEvents>...</caseEvents>

    <!-- roles, data, transitions, places, arcs follow -->
</document>
```

> ⚠️ **Element order is strict:** metadata → caseEvents → roles → data → transitions → places → arcs.
> `<caseEvents>` goes right after the metadata flags — NOT at the end of the document.

| Config | defaultRole | anonymousRole |
|--------|-------------|---------------|
| Internal | true | false |
| eForm (public) | true | true |
| System-only | false | false |

---

## PROCESS TYPE TAXONOMY

| Type | Description | Metadata config |
|------|-------------|-----------------|
| 1 — Internal | All users logged in, explicit roles | `defaultRole=true`, `anonymousRole=false` |
| 2 — eForm | Public submission form, back-office authenticated | `defaultRole=true`, `anonymousRole=true`; public task gets `anonymous` roleRef |
| 3 — Automation | Fully system-driven, no human tasks | `defaultRole=false`, `anonymousRole=false`; only `system` role |
| 4 — Mixed | Human tasks + system automation in same process | Combine as needed |
| 5 — Hybrid eForm+automation | Public form triggers automated pipeline | `anonymousRole=true` + `system` role |
| 6 — Full hybrid | All of the above | Any combination |

---

## ROLES

```xml
<role><id>manager</id><title>Manager</title></role>
```

- Always `<role>`, never `<processRole>`
- `anonymous` and `default` are built-in — never declare, only use in `<roleRef>`
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
| `taskRef` | use `<init>transition_id</init>` for static embed; target must be on a `read` arc (permanently alive) |
| `caseRef` | holds list of case string IDs; use `allowedNets` to restrict which processes can be linked |
| `i18n` | `divider` for visual section separator |
| `button` | clickable button in form; fires `event type="set"` immediately on click without finishing task; requires `<init>1</init>`; `<placeholder>` = button label |
| `button` | clickable button in the form; fires `event type="set"` immediately on click without finishing the task; requires `<init>1</init>`; `<placeholder>` = button label |

**Component syntax:**
```xml
<data type="text">
  <id>description</id>
  <title>Description</title>
  <component><name>textarea</name></component>
</data>
```

**`icon` component** — requires `enumeration_map`; option display value must be a valid Material icon name:
```xml
<data type="enumeration_map">
  <id>priority_icon</id><title>Priority</title>
  <options>
    <option key="low">arrow_downward</option>
    <option key="high">arrow_upward</option>
  </options>
  <init>low</init>
  <component><name>icon</name></component>
</data>
```

**Option syntax — `<option key="key">Display Value</option>`:**
```xml
<data type="enumeration_map">
  <id>decision</id><title>Decision</title>
  <options>
    <option key="approve">Approve</option>
    <option key="reject">Reject</option>
  </options>
</data>
```
> ❌ Never use `<option><key>approve</key><value>Approve</value></option>` — invalid Petriflow XML.

**Initialization:**
- `<value>x</value>` — default for text / number / boolean (omit `<value>` for empty text)
- `<init>key</init>` — default for enumeration / taskRef
- `<inits><init>k1</init><init>k2</init></inits>` — default for multichoice
- Routing number fields always `<init>0</init>`; OR-join `from_X` fields `<init>1</init>` (see OR-join)
- Boolean fields: omit init or use `<value>false</value>` for explicit false

**`button` field example:**
```xml
<data type="button">
  <id>add_row</id>
  <title/>
  <placeholder>Add</placeholder>
  <init>1</init>
  <event type="set">
    <id>add_row_set</id>
    <actions phase="pre">
      <action id="N"><![CDATA[
        persons: f.persons;
        def newCase = createCase(workspace + "person")
        change persons value { persons.value + newCase.stringId }
      ]]></action>
    </actions>
  </event>
</data>
```

**`caseRef` — `allowedNets`:**

Option A — static XML (process ID known at design time):
```xml
<data type="caseRef">
  <id>child_cases</id><title>Child Cases</title>
  <allowedNets>
    <allowedNet>YOUR_SPACE_NAME/child_process</allowedNet>
  </allowedNets>
</data>
```

Option B — dynamic action (process ID computed at runtime):
```groovy
// caseEvents create post
child_cases: f.child_cases, process_id: f.process_id;
change process_id value { workspace + "child_process" }
change child_cases allowedNets { [process_id.value] }
```

> ⚠️ `workspace` is eTask-specific. On standalone NAE use the literal process ID string.


Plain `multichoice` stores the display text as key. `sel.contains("legal")` silently returns `false` when key ≠ display text. **Always use `multichoice_map` for any field read in Groovy actions.** Same rule applies to `enumeration_map` vs `enumeration`.

**`button` field:**
```xml
<data type="button">
  <id>add_person</id>
  <title/>
  <placeholder>Add Person</placeholder>
  <init>1</init>   <!-- required -->
  <event type="set">
    <id>add_person_set</id>
    <actions phase="pre">
      <action id="N"><![CDATA[
        persons: f.persons;
        def newCase = createCase(workspace + "person")
        change persons value { persons.value + newCase.stringId }
      ]]></action>
    </actions>
  </event>
</data>
```

**`caseRef` — `allowedNets` static XML:**
```xml
<data type="caseRef">
  <id>insured_persons</id><title>Insured persons</title>
  <allowedNets>
    <allowedNet>YOUR_SPACE_NAME/insured</allowedNet>
  </allowedNets>
</data>
```

**`caseRef` — `allowedNets` dynamic (computed at runtime):**
```groovy
// caseEvents create post
children_cases: f.children_cases, processId: f.processId;
change processId value { workspace + "child_process" }
change children_cases allowedNets { [processId.value] }
```

---

## TRANSITION

```xml
<transition>
  <id>submit_request</id>
  <x>208</x><y>208</y>
  <label>Submit Request</label>
  <icon>send</icon>
  <priority>1</priority>
  <roleRef><id>role_id</id><logic><perform>true</perform></logic></roleRef>
  <dataGroup>
    <id>group_id</id><cols>2</cols><layout>grid</layout><title>Section</title>
    <dataRef>
      <id>field_id</id>
      <logic><behavior>editable</behavior><behavior>required</behavior></logic>
      <layout><x>0</x><y>0</y><rows>1</rows><cols>1</cols>
        <template>material</template><appearance>outline</appearance></layout>
    </dataRef>
  </dataGroup>
</transition>
```

- Behaviors: `editable` `visible` `required` `optional` `hidden` `forbidden`
- Events are **direct children of `<transition>`** — no `<transitionEvents>` wrapper
- **Exactly one `<dataGroup>` per transition** — builder silently ignores all after the first
- `<priority>1` = shown first; Detail/status tasks use higher number (e.g. `2`)
- `<assignPolicy>auto</assignPolicy>` — auto-assigns to current user when task becomes available; place **before** `<priority>` in element order
- `<roleRef><logic>` uses only `<perform>`, `<cancel>`, `<delegate>` — never `<view>`
- System tasks: `<roleRef><id>system</id>...`, no `<dataGroup>`, fired via `async.run`

---

## PLACES

```xml
<place><id>start</id><x>112</x><y>208</y><label>Start</label><tokens>1</tokens><static>false</static></place>
<place><id>p0</id>  <x>304</x><y>208</y><label>Pending</label><tokens>0</tokens><static>false</static></place>
```

- **Exactly one `<tokens>1</tokens>`** — the start place. All others: `<tokens>0</tokens>`
- No `<static>true</static>` — all places use `<static>false</static>`

---

## ARCS

Strict alternation: **Place → Transition → Place**. Never T→T or P→P.

| Type | Behavior |
|------|----------|
| `regular` | Consumes token |
| `read` | Non-consuming — for persistent/status places |
| `reset` | Removes all tokens from source |
| `inhibitor` | Fires only when source has 0 tokens |
| `regular` + `<reference>` | Variable arc — multiplicity set at runtime by a `number` field |

> ⚠️ There is **no `type="variable"`**. Variable arcs are `type="regular"` with `<multiplicity>0</multiplicity>` and `<reference>field_id</reference>`.

**Variable arc (outgoing from routing transition — XOR/OR-split):**
```xml
<arc>
  <id>arc_to_legal</id><type>regular</type>
  <sourceId>routing_transition</sourceId>   <!-- MUST be a Transition, never a Place -->
  <destinationId>after_legal</destinationId> <!-- MUST be a Place -->
  <multiplicity>0</multiplicity>
  <reference>toLegal</reference>             <!-- MUST be a number field, never boolean -->
</arc>
```

**Variable arc (incoming into join transition — OR-join):**
```xml
<arc>
  <id>arc_join_legal</id><type>regular</type>
  <sourceId>p_legal_out</sourceId>
  <destinationId>t_final</destinationId>
  <multiplicity>1</multiplicity>
  <reference>from_legal</reference>   <!-- from_legal mirrors to_legal split value -->
</arc>
```

> ⚠️ **OR-join `from_X` fields must have `<init>1</init>`.** If init=0, the join fires immediately at case creation before routing runs (requires 0 tokens from each input = always enabled).

**Coordinates:**
- Main lane: Y=208, X starting at 112, increments of 192px
- Branch lanes: each branch gets its own Y (e.g. Y=400, Y=592 for branches; Y=16 for detail lane)
- No two elements share the same (x, y)
- Every logical branch occupies its **own Y lane** — rejected/optional paths must be on distinct Y values

---

## ACTIONS

### Event scope

| Scope | Placement | Types |
|-------|-----------|-------|
| Case | `<caseEvents>` — immediately after metadata, before roles | `create` `delete` (never `finish`) |
| Transition | directly inside `<transition>` | `assign` `finish` `cancel` `delegate` |
| Data | inside `<dataRef>` | `set` `get` |

Phases: `pre` = before event (validation, status, routing flags) / `post` = after event (email, async, API)

### Action structure

```groovy
// Import header: comma-separated, one semicolon at end
field1: f.field_id1,
field2: f.field_id2,
t_next: t.next_transition;

// Action body — use bare variable names, never f.field_id
change field1 value { "New Value" }
make field2, required on t_next when { return field1.value == "trigger" }
```

- Every field used anywhere in the body → must be in the import header
- Every transition used in `make` → must be in the import header
- `f.field_id` only in the import header line, **never** in the action body
- Action IDs must be globally unique across the entire document and sequential

### `change` vs `setData`

- **`change`** — synchronous field update in current action context
- **`setData(task, [...])`** — use inside `async.run`, or for cross-case writes. Always resolve the task via `findTask` first.

```groovy
// change — synchronous
status: f.status;
change status value { "Approved" }

// setData — inside async.run (always find task first)
async.run {
  def t = findTask { qTask ->
    qTask.transitionId.eq("result_task").and(qTask.caseId.eq(useCase.stringId))
  }
  if (t) setData(t, [status: [value: "Done", type: "text"]])
}
```

**`setData` type strings:**

| Field type | `type` string |
|---|---|
| `text` | `"text"` |
| `number` | `"number"` |
| `boolean` | `"boolean"` |
| `date` | `"date"` | `java.time.LocalDate.now()` |
| `dateTime` | `"dateTime"` | `java.time.LocalDateTime.now()` |
| `enumeration` / `enumeration_map` | `"enumeration"` / `"enumeration_map"` |
| `multichoice` / `multichoice_map` | `"multichoice"` / `"multichoice_map"` |
| `user` | `"user"` |
| `taskRef` | `"taskRef"` |
| `caseRef` | `"caseRef"` |

### `make` syntax

```groovy
make <field>, <behaviour> on <transition> when { <condition> }
```

- One field, one transition per call
- `when { true }` / `when { false }` for unconditional; `when { return expr }` for conditional
- `optional` removes a previously set `required`
- `make ... on transitions` applies to all transitions — use sparingly

```groovy
t_review: t.review_task,
comment: f.comment,
priority: f.priority;
make comment, visible  on t_review when { true }
make comment, required on t_review when { return priority.value == "high" }
```

### `async.run`

```groovy
async.run {
  def t = findTask { qTask ->
    qTask.transitionId.eq("next_task").and(qTask.caseId.eq(useCase.stringId))
  }
  if (t) {
    assignTask(t, userService.loggedOrSystem)
    finishTask(t, userService.loggedOrSystem)
  }
}
```

- **Always use `async.run` for `assignTask`/`finishTask`** called from inside another event action
- String shorthand `assignTask("id")` / `finishTask("id")` valid for same-case transitions
- Exception inside one `async.run` block may prevent subsequent blocks from executing — guard with null-checks

### Date/time types

**CRITICAL:** Petriflow uses `java.time.*`, NOT `java.util.Date`.

| Field type | Java type | Example usage |
|------------|-----------|---------------|
| `date` | `java.time.LocalDate` | `date_field.value as java.time.LocalDate` |
| `dateTime` | `java.time.LocalDateTime` | `datetime_field.value as java.time.LocalDateTime` |

```groovy
// Set current date / datetime
submitted_date: f.submitted_date, submitted_at: f.submitted_at;
change submitted_date value { java.time.LocalDate.now() }
change submitted_at   value { java.time.LocalDateTime.now() }

// Date arithmetic — calculate days between two date fields
start_date: f.start_date, end_date: f.end_date, days: f.days;
def daysDiff = java.time.temporal.ChronoUnit.DAYS.between(
    start_date.value as java.time.LocalDate,
    end_date.value as java.time.LocalDate
)
change days value { (daysDiff + 1) as Double }

// Formatting
"Date: ${start_date.value?.format(java.time.format.DateTimeFormatter.ofPattern('dd/MM/yyyy'))}"
```

### Useful services

```groovy
userService.loggedOrSystem.email
userService.loggedOrSystem.transformToUser()
findTask  { qTask -> qTask.transitionId.eq("id").and(qTask.caseId.eq(useCase.stringId)) }
findTasks { it.caseId.in(caseRefField.value).and(it.transitionId.eq("t2")) }  // .in() for multiple cases
findCase  { qCase -> qCase.stringId.eq(id) }
findCases { qCase -> qCase.processIdentifier.eq("process_id") }

// createCase variants
def c = createCase("child_process_id", "Title", "blue")  // full form
def c = createCase("child_process_id")                   // minimal — no title/color

// Get first task of a newly created case directly (without findTask query)
def taskId = c.tasks.find { it.transition == "t1" }?.task
def task   = findTask({ it._id.eq(taskId) })
assignTask(task)   // assign to current user (no second argument needed)

// Read field from case object
def val = someCase.getFieldValue("field_id")   // alternative to someCase.dataSet["field_id"]?.value

// Delete a case
workflowService.deleteCase(someCase.stringId)

// Populate enumeration_map options from other process cases
def options = findCases { it.processIdentifier.eq(workspace + "order") }
                .collectEntries { [(it.stringId): "Order: " + it.stringId] }
change my_field options { options }

// Batch finish all tasks in a taskRef list
taskref_field: f.taskref_field;
taskref_field.value.each { id ->
    def t = findTask({ it._id.eq(id) })
    if (t) finishTask(t)
}

sendEmail([email.value], "Subject", "Body")
generatePdf("transition_id", "file_field_id")
assignRole("role_id", petriNet)
removeRole("role_id", petriNet)
cancelTask(taskObj)
changeCaseProperty("title").about { "New Title" }
changeCaseProperty("color").about { "green" }  // red|orange|yellow|green|teal|cyan|blue|indigo|purple|pink|brown|grey
setData("transition_id", targetCase, ["field_id": ["value": "val", "type": "text"]])  // shorthand cross-case
```

---

## ROUTING SELECTION

| Situation | Use |
|-----------|-----|
| User picks one of N paths via a field | Variable arcs (XOR) |
| Multiple paths run simultaneously | AND-split/join (Pattern 4) |
| User selects 1–N paths via multichoice_map | OR-split via variable arcs (Pattern 6) |
| Decision needs Groovy logic (ranges, lookups) | Systematic tasks (Pattern 14) |
| ❌ NEVER | Two plain regular arcs from the same place without `<reference>` |

**Decision guide:**
```
Simple field value → Variable arcs
  One path only (XOR)      → set one number=1, rest=0; all branches → shared merge PLACE
  Multiple paths (OR-split) → set multiple numbers=1
    Fixed 2–3 branches → OR-join via incoming variable arcs (from_X fields, init=1)
    Dynamic/large N, each has own task → Pattern 6b (pre-load go_count tokens)
    Dynamic/large N, shared task → counter + systematic task

Numeric range / complex Groovy → Systematic tasks

Persistent submitter view → taskRef field (Form task on read arc)
Always-accessible status view → Detail task + read arc (Pattern 16)
```

XOR routing flags must be set in `phase="pre"` — not post.

---

## GOTCHAS

1. **`multichoice_map` not `multichoice`** when reading value in Groovy — plain multichoice stores display text as key, `sel.contains("legal")` silently returns false. Same for `enumeration_map` vs `enumeration`.
2. **Variable arc source = Transition**, never a Place
3. **Variable arc reference = `number` field**, never boolean — boolean causes import error
4. **Routing flags in `phase="pre"`** — token moves after pre, before post
5. **One `<dataGroup>` per transition** — builder silently ignores all others
6. **`caseEvents`** accepts only `create` / `delete`, never `finish`. Placed immediately **after metadata, before roles** — not at end of document.
7. **`task` variable** available only in transition events, not case events
8. **`setData` and `assignTask`/`finishTask`** only inside `async.run`
9. **XOR reconvergence** — fork branches must converge into a shared merge PLACE, not directly into a shared transition. Transition with two regular incoming arcs = AND-join = deadlock.
10. **AND-join after OR-split = deadlock.** Use incoming variable arcs on the join transition instead. Declare `from_X` fields (init=1), mirror `to_X` values into them in routing action, add `<reference>from_X</reference>` on each incoming arc to the join transition.
11. **Empty `<component/>`** causes silent bug — omit entirely or set `<name>name</name>`
12. **`f.field_id`** only in import header, never in action body
13. **Arithmetic in CDATA** — `*` must be plain ASCII char 42, never `<em>` or `&times;`
14. **Special chars in XML text** — `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;` in ALL text content (titles, labels, placeholders, option values)
15. **`taskRef` target must be permanently alive** — on a `read` arc (always enabled). Never point at a human task consumed by normal flow.
16. **System task chains** — every system task must fire the next one in its finish post via `async.run`
17. **Rejection branches** — every decision option needs: number field + variable arc + destination place
18. **Variable arc `(0)` in modeller** — expected, not a bug. Reflects `<init>0</init>`, overwritten at runtime.
19. **AND-split requires a dedicated split TRANSITION** — a place with two outgoing regular arcs is a race condition, not AND-split. The split transition (system role, no dataGroup) fires tokens into both branch places simultaneously. Bootstrap via `async.run { assignTask("split"); finishTask("split") }`.
20. **Duplicate `<?xml` declaration** — must have exactly one on line 1.
21. **`<dataGroup>` tag sequence** — correct order: `<id>` → `<cols>` → `<layout>grid</layout>` → `<title>Text</title>`. Common error: `<layout>grid</title>Text</title>`.
22. **All field reads in `caseEvents` require imports** — always use import header, never `f.field_id` inline.
23. **Place with two outgoing arcs + decision inside task = race condition** — use variable arcs set in finish pre.
    23b. **CRITICAL: Multiple regular outgoing arcs from same TRANSITION = impossible** — a transition can only output one regular token per firing. When user makes approve/reject INSIDE a task, use variable arcs: number fields (`toApprove`, `toReject`), set in finish PRE, `<reference>` on arcs from the decision transition. See Pattern 5 (XOR-split).
24. **Cancel from multiple states = race condition** — use one cancel transition per state, each reading from exactly one place.
25. **CRITICAL: Arithmetic operators in CDATA** — multiplication MUST be `*` (char 42). Never `<em>`, `&times;`, or any HTML entity.
26. **OR-join via incoming variable arcs** — `from_X` fields must have `<init>1</init>`. If init=0, the join transition requires 0 tokens from all inputs = fires immediately at case creation before any routing runs.
27. **System task bootstrap** — system tasks must be triggered via `async.run { assignTask("id"); finishTask("id") }` from the preceding task's finish post. Without this, system tasks remain dormant.
28. **`assignTask`/`finishTask` only work when the target transition is enabled** (its input place holds a token). Calling on a disabled transition throws a runtime exception.
29. **`setData` self-copy is a no-op** — all tasks in the same case share field values. Never `setData(task, [field: [value: field.value, ...]])` where task belongs to the same case.
30. **Loop reconvergence** — the revision arc must loop back to the **same input place** as the main flow, not a new merge place. A place with two incoming regular arcs fires when **any one** arrives (OR semantics), not AND. Do NOT add a new merge place or give the shared task two separate input places (= AND-join = deadlock).
31. **`breakpoint` for looping arcs** — add `<breakpoint>` elements to arcs that loop back to earlier places so they render correctly in the visual modeller without overlapping other elements.
32. **`caseRef` field** — holds a list of case string IDs; set `.value` dynamically via action to a `List<String>` of case IDs. Use `allowedNets` attribute to restrict which process IDs can be linked.
33. **`button` field** — requires `<init>1</init>` or it will not render. `<placeholder>` is the visible label. Fires `event type="set"` `phase="pre"` on click without finishing the task. Always reference with `<behavior>editable</behavior>` in the dataGroup.
34. **`createCase` minimal form** — `createCase("process_id")` is valid without title/color. After creation, get the first task immediately via `newCase.tasks.find { it.transition == "t1" }?.task` instead of a separate `findTask` query.
35. **`assignTask(taskObject)` without user** — valid; assigns to the currently logged-in user.
36. **`workflowService.deleteCase(stringId)`** — deletes a case programmatically. Use in button set events for "Delete selected" patterns.
37. **`case.getFieldValue("fieldId")`** — alternative to `case.dataSet["fieldId"]?.value` for reading a field from a case object.
38. **`findTasks { it.caseId.in(list) }`** — `.in()` operator filters tasks across multiple case IDs at once. The list comes directly from a `caseRef.value` or `taskRef.value`.

---

## CHECKLIST

**Roles & fields**
- [ ] All roles `<role>`, never `<processRole>`
- [ ] `system` role declared if systematic tasks exist
- [ ] `anonymous`/`default` only in `<roleRef>`, not declared
- [ ] Selection fields have `<options>`
- [ ] `_map` variants used wherever field is read in Groovy
- [ ] No empty `<component/>`
- [ ] `taskRef` target is on a `read` arc (permanently alive)

**IDs & references**
- [ ] All IDs unique across entire document (lowercase_underscore)
- [ ] Every `<dataRef>` ID matches a declared `<data>` ID
- [ ] Every `<roleRef>` ID matches a declared `<role>` or is `anonymous`/`default`
- [ ] All arc sourceId/destinationId exist

**Actions**
- [ ] Action IDs globally unique and sequential (never restart at 1)
- [ ] All code in `<![CDATA[...]]>`
- [ ] Every field/transition used in body is in import header — including in `caseEvents`
- [ ] `f.field_id` only in header, never in body
- [ ] Commas between imports, one semicolon at end
- [ ] `setData` and `assignTask`/`finishTask` only in `async.run`
- [ ] `task` not referenced in `caseEvents`
- [ ] Every `make` = one field + one transition
- [ ] Routing flags in `phase="pre"`
- [ ] No HTML tags or entities in CDATA (`*` not `<em>`)
- [ ] `make when` closure uses `return` for conditional expressions

**caseEvents**
- [ ] Only `create` or `delete`
- [ ] Placed **immediately after metadata flags, before `<role>` elements**

**XML structure**
- [ ] Element order: metadata → caseEvents → roles → data → transitions → places → arcs
- [ ] Exactly one `<?xml?>` prolog (or none — builder format has no prolog)
- [ ] Every `<dataGroup>` correct sequence: `<id>` → `<cols>` → `<layout>grid</layout>` → `<title>`
- [ ] Special chars escaped in ALL text content
- [ ] Main lane Y=208; branches each on distinct Y; detail lane Y=16; X increments 192px
- [ ] No two elements at same (x,y)
- [ ] Exactly one `tokens=1`
- [ ] Arcs alternate Place→Transition→Place
- [ ] Every `<place>` has `<static>false</static>`

**Design & routing**
- [ ] Token trace: no stuck states, no race conditions
- [ ] XOR branches converge into a merge PLACE, not directly into a shared transition
- [ ] No AND-join place after OR-split — use incoming variable arcs (`from_X`, init=1)
- [ ] Every decision option has its own routing number field + variable arc + destination place
- [ ] OR-join `from_X` fields initialised to `1` (not `0`)
- [ ] Variable arc source is always a Transition, never a Place
- [ ] Variable arc reference is always a `number` field, never boolean
- [ ] System tasks bootstrapped via async.run in preceding task's finish post
- [ ] AND-split uses a dedicated split transition (system role), not a place with two outgoing arcs
- [ ] Loop revision arc points back to the same place as the main flow (not a new merge place)
- [ ] Persistent detail view: regular arc from submit → p_detail; read arc from p_detail → detail_view

---

## OUTPUT & TESTING

1. Download the XML file
2. https://builder.netgrif.cloud/modeler — drag and drop XML to verify visually
3. https://etask.netgrif.cloud/ — test full functionality

| Error | Cause | Fix |
|-------|-------|-----|
| "Invalid namespace" | Wrong xmlns | Use exact namespace |
| "Duplicate ID" | Shared ID | Make all IDs unique |
| "No start place" | No tokens=1 | Exactly one start place |
| "Not a number. Cannot change arc weight" | `<reference>` on boolean | Change to number, use 1/0 |
| Variable arc P→P error | sourceId is a Place | Move source to routing transition |
| `sel.contains()` always false | plain multichoice/enumeration | Use `_map` variant |
| OR-split deadlock | AND-join place after OR-split | Incoming variable arcs on join transition |
| Parallel review race condition | Place with two outgoing arcs | Dedicated split transition (gotcha #19) |
| Time trigger never fires | Not a system-role transition | Assign `system` role, remove dataGroup |
| `taskRef` panel blank | Referenced task is dead (consumed) | Point only at tasks on a `read` arc |
| eTask arithmetic error | `*` rendered as `<em>` | Use plain ASCII `*` in CDATA |
| Final step fires twice | Race in parallel race pattern | Counter flag + dead-end place (Pattern 9) |