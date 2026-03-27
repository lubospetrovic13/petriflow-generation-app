# Petriflow — Workflow Patterns

---

## Pattern 1 — Simple Sequence

```
[start:1] → [submit] → [p0:0] → [review] → [p1:0] → [approve] → [done:0]
```

```xml
<place><id>start</id><x>112</x><y>208</y><label>Start</label>          <tokens>1</tokens><static>false</static></place>
<place><id>p0</id>   <x>304</x><y>208</y><label>Pending Review</label>  <tokens>0</tokens><static>false</static></place>
<place><id>p1</id>   <x>688</x><y>208</y><label>Reviewed</label>        <tokens>0</tokens><static>false</static></place>
<place><id>done</id> <x>1072</x><y>208</y><label>Done</label>           <tokens>0</tokens><static>false</static></place>

<arc><id>a1</id> <type>regular</type> <sourceId>start</sourceId>   <destinationId>submit</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>a2</id> <type>regular</type> <sourceId>submit</sourceId>  <destinationId>p0</destinationId>      <multiplicity>1</multiplicity></arc>
<arc><id>a3</id> <type>regular</type> <sourceId>p0</sourceId>      <destinationId>review</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>a4</id> <type>regular</type> <sourceId>review</sourceId>  <destinationId>p1</destinationId>      <multiplicity>1</multiplicity></arc>
<arc><id>a5</id> <type>regular</type> <sourceId>p1</sourceId>      <destinationId>approve</destinationId> <multiplicity>1</multiplicity></arc>
<arc><id>a6</id> <type>regular</type> <sourceId>approve</sourceId> <destinationId>done</destinationId>    <multiplicity>1</multiplicity></arc>
```

---

## Pattern 2 — Approval with Rejection (single decision task + variable arcs)

> ✅ **Preferred approach:** one task with a decision field; variable arcs route to approved/rejected place.
> ❌ **Avoid** two separate "Approve" / "Reject" tasks competing for the same input place token — confusing UX, hard to extend.

```
                  ┌─(ref:go_approve)──→ [approved:0]
[p0:0] → [review] ┤
                  └─(ref:go_reject)───→ [rejected:0]
```

```xml
<data type="number"><id>go_approve</id><title>Go Approve</title><init>0</init></data>
<data type="number"><id>go_reject</id> <title>Go Reject</title> <init>0</init></data>
<data type="enumeration_map">
<id>decision</id><title>Decision</title>
<options>
    <option key="approve">Approve</option>
    <option key="reject">Reject</option>
</options>
</data>

<place><id>approved</id><x>880</x><y>112</y><label>Approved</label><tokens>0</tokens><static>false</static></place>
<place><id>rejected</id><x>880</x><y>304</y><label>Rejected</label><tokens>0</tokens><static>false</static></place>

        <!-- Variable arcs from review transition -->
<arc><id>a_to_approved</id><type>regular</type><sourceId>review</sourceId><destinationId>approved</destinationId><multiplicity>0</multiplicity><reference>go_approve</reference></arc>
<arc><id>a_to_rejected</id><type>regular</type><sourceId>review</sourceId><destinationId>rejected</destinationId><multiplicity>0</multiplicity><reference>go_reject</reference></arc>
```

```groovy
// review finish PRE — set routing before token moves
go_approve: f.go_approve, go_reject: f.go_reject, decision: f.decision;
if (decision.value == "approve") { change go_approve value { 1 } change go_reject value { 0 } }
else                              { change go_approve value { 0 } change go_reject value { 1 } }
```

**Dynamic field reveal on decision change (`event type="set"` on the decision dataRef):**
```xml
<dataRef>
  <id>decision</id>
  <logic><behavior>editable</behavior><behavior>required</behavior></logic>
  <layout>...</layout>
  <event type="set">
    <id>decision_set</id>
    <actions phase="post">
      <action id="N"><![CDATA[
        t_review: t.review,
        rejection_reason: f.rejection_reason,
        decision: f.decision;
        if (decision.value == "reject") {
          make rejection_reason, editable on t_review when { true }
        } else {
          make rejection_reason, hidden on t_review when { true }
        }
      ]]></action>
    </actions>
  </event>
</dataRef>
```

---

## Pattern 4 — AND-split / AND-join (parallel branches)

```
                   ┌→ [legal_p:0]   → [review_legal]   → [legal_done:0]   ─┐
[p0:0] → [split,sys] ┤                                                         [join:0] →(mult=2)→ [finalize]
                   └→ [finance_p:0] → [review_finance] → [finance_done:0] ─┘
```

> ⚠️ A **place** with two outgoing arcs is a race condition, not AND-split.
> ⚠️ Split is a **system task** — bootstrap from preceding task's finish post.

```xml
<transition>
  <id>split</id><x>304</x><y>208</y><label>Start Parallel Review</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
</transition>

<place><id>legal_p</id>      <x>496</x><y>112</y><label>Legal Queue</label>    <tokens>0</tokens><static>false</static></place>
<place><id>finance_p</id>    <x>496</x><y>304</y><label>Finance Queue</label>  <tokens>0</tokens><static>false</static></place>
<place><id>legal_done</id>   <x>880</x><y>112</y><label>Legal Done</label>     <tokens>0</tokens><static>false</static></place>
<place><id>finance_done</id> <x>880</x><y>304</y><label>Finance Done</label>   <tokens>0</tokens><static>false</static></place>
<place><id>join</id>         <x>1072</x><y>208</y><label>Both Done</label>     <tokens>0</tokens><static>false</static></place>

<arc><id>a_p0_split</id>    <type>regular</type> <sourceId>p0</sourceId>             <destinationId>split</destinationId>         <multiplicity>1</multiplicity></arc>
<arc><id>a_split_legal</id> <type>regular</type> <sourceId>split</sourceId>          <destinationId>legal_p</destinationId>       <multiplicity>1</multiplicity></arc>
<arc><id>a_split_fin</id>   <type>regular</type> <sourceId>split</sourceId>          <destinationId>finance_p</destinationId>     <multiplicity>1</multiplicity></arc>
<arc><id>a_lp_rev</id>      <type>regular</type> <sourceId>legal_p</sourceId>        <destinationId>review_legal</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>a_fp_rev</id>      <type>regular</type> <sourceId>finance_p</sourceId>      <destinationId>review_finance</destinationId><multiplicity>1</multiplicity></arc>
<arc><id>a_rev_ld</id>      <type>regular</type> <sourceId>review_legal</sourceId>   <destinationId>legal_done</destinationId>    <multiplicity>1</multiplicity></arc>
<arc><id>a_rev_fd</id>      <type>regular</type> <sourceId>review_finance</sourceId> <destinationId>finance_done</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>a_ld_join</id>     <type>regular</type> <sourceId>legal_done</sourceId>     <destinationId>join</destinationId>          <multiplicity>1</multiplicity></arc>
<arc><id>a_fd_join</id>     <type>regular</type> <sourceId>finance_done</sourceId>   <destinationId>join</destinationId>          <multiplicity>1</multiplicity></arc>
<arc><id>a_join_fin</id>    <type>regular</type> <sourceId>join</sourceId>           <destinationId>finalize</destinationId>      <multiplicity>2</multiplicity></arc>
```

```groovy
// preceding task finish POST — bootstrap split
async.run { assignTask("split"); finishTask("split") }
```

---

## Pattern 4a — AND-split with approve/reject in each branch

When each parallel review can approve or reject, use variable arcs from each review transition.

```
[review_legal] ─(ref:legal_approve)─→ [legal_done:0] ─┐
               └(ref:legal_reject)──→ [rejected:0]     │ (dead end)
                                                        [join:0] →(mult=2)→ [final]
[review_finance]─(ref:fin_approve)──→ [finance_done:0]─┘
                └(ref:fin_reject)───→ [rejected:0]
```

> ❌ NEVER: two regular arcs from the same transition to different places — a transition can only produce one token per firing without variable arcs.

```xml
<data type="number"><id>legal_approve</id><title>Legal Approve</title><init>0</init></data>
<data type="number"><id>legal_reject</id> <title>Legal Reject</title> <init>0</init></data>
<data type="number"><id>fin_approve</id>  <title>Finance Approve</title><init>0</init></data>
<data type="number"><id>fin_reject</id>   <title>Finance Reject</title> <init>0</init></data>

<data type="enumeration_map">
  <id>legal_decision</id><title>Legal Decision</title>
  <options><option key="approve">Approve</option><option key="reject">Reject</option></options>
</data>

<transition>
  <id>review_legal</id>
  <dataGroup>
    <id>legal_group</id><cols>2</cols><layout>grid</layout><title>Legal Review</title>
    <dataRef><id>legal_decision</id><logic><behavior>editable</behavior><behavior>required</behavior></logic>
      <layout><x>0</x><y>0</y><rows>1</rows><cols>2</cols><template>material</template><appearance>outline</appearance></layout>
    </dataRef>
  </dataGroup>
  <event type="finish"><id>on_legal_finish</id>
    <actions phase="pre">
      <action id="1"><![CDATA[
        legal_approve: f.legal_approve, legal_reject: f.legal_reject, legal_decision: f.legal_decision;
        if (legal_decision.value == "approve") { change legal_approve value { 1 } change legal_reject value { 0 } }
        else                                   { change legal_approve value { 0 } change legal_reject value { 1 } }
      ]]></action>
    </actions>
  </event>
</transition>

<arc><id>arc_legal_approve</id><type>regular</type><sourceId>review_legal</sourceId><destinationId>legal_done</destinationId><multiplicity>0</multiplicity><reference>legal_approve</reference></arc>
<arc><id>arc_legal_reject</id> <type>regular</type><sourceId>review_legal</sourceId><destinationId>rejected</destinationId>  <multiplicity>0</multiplicity><reference>legal_reject</reference></arc>
<!-- Same structure for review_finance -->
<arc><id>arc_fin_approve</id>  <type>regular</type><sourceId>review_finance</sourceId><destinationId>finance_done</destinationId><multiplicity>0</multiplicity><reference>fin_approve</reference></arc>
<arc><id>arc_fin_reject</id>   <type>regular</type><sourceId>review_finance</sourceId><destinationId>rejected</destinationId>   <multiplicity>0</multiplicity><reference>fin_reject</reference></arc>
```

> ⚠️ If either branch rejects, token goes to `rejected` — the other branch's token gets stuck in `join`. This is expected: rejection terminates immediately.

---

## Pattern 5 — XOR-split / XOR-merge (exclusive choice)

```
              ┌─(ref:toA)→ [path_a:0] → [task_a] ─┐
[p0:0] → [router] ┤                                   [merge:0] → [finalize]
              └─(ref:toB)→ [path_b:0] → [task_b] ─┘
```

> ⚠️ Both branches must converge into a shared merge **PLACE** — not directly into a shared transition (that would be an AND-join = deadlock)

```xml
<data type="number"><id>toA</id><title>To A</title><init>0</init></data>
<data type="number"><id>toB</id><title>To B</title><init>0</init></data>

<arc><id>arc_to_a</id>   <type>regular</type> <sourceId>router</sourceId> <destinationId>path_a</destinationId> <multiplicity>0</multiplicity><reference>toA</reference></arc>
<arc><id>arc_to_b</id>   <type>regular</type> <sourceId>router</sourceId> <destinationId>path_b</destinationId> <multiplicity>0</multiplicity><reference>toB</reference></arc>
<arc><id>arc_a_mrg</id>  <type>regular</type> <sourceId>task_a</sourceId> <destinationId>merge</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>arc_b_mrg</id>  <type>regular</type> <sourceId>task_b</sourceId> <destinationId>merge</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>arc_mrg_fin</id><type>regular</type> <sourceId>merge</sourceId>  <destinationId>finalize</destinationId><multiplicity>1</multiplicity></arc>
```

```groovy
// router finish PRE
toA: f.toA, toB: f.toB, decision: f.decision;
if (decision.value == "path_a") { change toA value { 1 } change toB value { 0 } }
else                             { change toA value { 0 } change toB value { 1 } }
```

---

## Pattern 6 — OR-split / OR-join via incoming variable arcs (2–3 fixed branches)

Best approach for a fixed small number of branches where the user selects 1 or more.

```
              ┌─(ref:to_legal)──→ [p_legal_in:0] → [legal_task] → [p_legal_out:0] ─┐
[t_register] ─┤                                                                        [t_final] (OR-join via incoming variable arcs)
              └─(ref:to_finance)─→ [p_finance_in:0]→[finance_task]→[p_finance_out:0]─┘
```

> ❌ Do NOT use a plain AND-join place — it deadlocks when the user selects only one branch.
> ✅ Use incoming variable arcs on the join transition; mirror split `to_X` values as `from_X`.

```xml
<!-- Split routing fields -->
<data type="number"><id>to_legal</id>   <title>Route to Legal</title>   <init>0</init></data>
<data type="number"><id>to_finance</id> <title>Route to Finance</title> <init>0</init></data>

<!-- Join routing fields — init=1 (not 0!) to avoid premature firing -->
<data type="number"><id>from_legal</id>   <title>From Legal</title>   <init>1</init></data>
<data type="number"><id>from_finance</id> <title>From Finance</title> <init>1</init></data>

<data type="multichoice_map">
  <id>departments</id><title>Departments</title>
  <options>
    <option key="legal">Legal</option>
    <option key="finance">Finance</option>
  </options>
</data>
```

```groovy
// t_register finish PRE — set BOTH split and join fields together
to_legal: f.to_legal, to_finance: f.to_finance,
from_legal: f.from_legal, from_finance: f.from_finance,
departments: f.departments;
def sel = departments.value as Set
def goLegal   = sel.contains("legal")   ? 1 : 0
def goFinance = sel.contains("finance") ? 1 : 0
change to_legal    value { goLegal   as Double }
change to_finance  value { goFinance as Double }
change from_legal  value { goLegal   as Double }   // mirror for join
change from_finance value { goFinance as Double }  // mirror for join
```

```xml
<!-- OR-split variable arcs (outgoing from routing transition) -->
<arc><id>a_var_legal</id>   <type>regular</type><sourceId>t_register</sourceId><destinationId>p_legal_in</destinationId>  <multiplicity>0</multiplicity><reference>to_legal</reference></arc>
<arc><id>a_var_finance</id> <type>regular</type><sourceId>t_register</sourceId><destinationId>p_finance_in</destinationId><multiplicity>0</multiplicity><reference>to_finance</reference></arc>

<!-- OR-join variable arcs (incoming into join transition) -->
<arc><id>a_join_legal</id>   <type>regular</type><sourceId>p_legal_out</sourceId>  <destinationId>t_final</destinationId><multiplicity>1</multiplicity><reference>from_legal</reference></arc>
<arc><id>a_join_finance</id> <type>regular</type><sourceId>p_finance_out</sourceId><destinationId>t_final</destinationId><multiplicity>1</multiplicity><reference>from_finance</reference></arc>
```

| Selection | from_legal | from_finance | t_final fires when |
|---|---|---|---|
| Legal only | 1 | 0 | legal done ✅ |
| Finance only | 0 | 1 | finance done ✅ |
| Both | 1 | 1 | both done ✅ |

---

## Pattern 6b — OR-split with per-department tasks and dynamic join

For dynamic or larger N branches where each activated branch has its own task. Uses a `go_count` token counter pre-loaded into the merge place.

```
              ┌─(ref:to_dept_a, mult=0)──→ [p_a:0] → [task_dept_a] ─┐
[t_register] ─┤  also fires (ref:go_count) tokens into [merge:0]      [merge] →(ref:go_count)→ [t_final]
              └─(ref:to_dept_b, mult=0)──→ [p_b:0] → [task_dept_b] ─┘
```

```xml
<data type="number"><id>go_count</id><title>Branch Count</title><init>0</init></data>
<data type="number"><id>to_dept_a</id><title>To A</title><init>0</init></data>
<data type="number"><id>to_dept_b</id><title>To B</title><init>0</init></data>
```

```groovy
// t_register finish PRE
go_count: f.go_count, to_dept_a: f.to_dept_a, to_dept_b: f.to_dept_b, departments: f.departments;
def sel = departments.value as Set
def aGo = sel.contains("dept_a") ? 1 : 0
def bGo = sel.contains("dept_b") ? 1 : 0
change to_dept_a value { aGo as Double }
change to_dept_b value { bGo as Double }
change go_count  value { (aGo + bGo) as Double }
```

```xml
<!-- Split arcs -->
<arc><id>a_to_a</id>     <type>regular</type><sourceId>t_register</sourceId><destinationId>p_a</destinationId>   <multiplicity>0</multiplicity><reference>to_dept_a</reference></arc>
<arc><id>a_to_b</id>     <type>regular</type><sourceId>t_register</sourceId><destinationId>p_b</destinationId>   <multiplicity>0</multiplicity><reference>to_dept_b</reference></arc>
<!-- Pre-load go_count tokens into merge place -->
<arc><id>a_pre_merge</id><type>regular</type><sourceId>t_register</sourceId><destinationId>p_merge</destinationId><multiplicity>0</multiplicity><reference>go_count</reference></arc>

<!-- Each dept task deposits 1 token back into merge -->
<arc><id>a_a_merge</id>  <type>regular</type><sourceId>task_dept_a</sourceId><destinationId>p_merge</destinationId><multiplicity>1</multiplicity></arc>
<arc><id>a_b_merge</id>  <type>regular</type><sourceId>task_dept_b</sourceId><destinationId>p_merge</destinationId><multiplicity>1</multiplicity></arc>

<!-- Variable arc on join: fires when merge holds go_count tokens -->
<arc><id>a_merge_fin</id><type>regular</type><sourceId>p_merge</sourceId><destinationId>t_final</destinationId><multiplicity>0</multiplicity><reference>go_count</reference></arc>
```

---

## Pattern 7 — Loop / Revision

```
[start:1] → [submit] → [p0:0] → [review]
                ↑                   │
                └──[p1:0]──[reject_revision]──┘
                                         ↓ also arc to p0 or start
```

**Correct reconvergence:** loop arc must return to the **same input place** the main flow uses. Do NOT add a new merge place.

```xml
<!-- Loop back to start so submitter can resubmit -->
<arc><id>a_p1_rej</id>  <type>regular</type><sourceId>p1</sourceId>             <destinationId>reject_revision</destinationId><multiplicity>1</multiplicity></arc>
<arc><id>a_rej_start</id><type>regular</type><sourceId>reject_revision</sourceId><destinationId>start</destinationId>          <multiplicity>1</multiplicity>
  <breakpoint><x>432</x><y>368</y></breakpoint>
  <breakpoint><x>208</x><y>368</y></breakpoint>
</arc>
```

```groovy
// review finish PRE — set routing (variable arcs used when decision is inside review task)
go_approve: f.go_approve, go_remake: f.go_remake, decision: f.decision, revision_count: f.revision_count;
if (decision.value == "approve") {
  change go_approve value { 1 }; change go_remake value { 0 }
} else {
  change go_approve value { 0 }; change go_remake value { 1 }
  change revision_count value { ((revision_count.value as Integer) ?: 0) + 1 as Double }
}
```

> ⚠️ A place with two incoming regular arcs (main flow + loop) fires when **any one** arrives — this is OR semantics, not AND. No deadlock. Do NOT give the task two separate input places (= AND-join = deadlock).

---

## Pattern 8 — Time Trigger (automatic action after delay)

```
[p_pending:0] ──→ [t_review, reviewer role]    (human — consumes token when assigned)
              └──→ [t_sla_check, system, PT24H] (fires automatically after 24h if token still there)
```

```xml
<transition>
  <id>t_sla_check</id><x>496</x><y>400</y><label>SLA Check</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
  <trigger type="time"><delay>PT24H</delay></trigger>
  <!-- no dataGroup — system task -->
</transition>
```

ISO 8601 durations: `PT5S` (5s) · `PT30M` (30min) · `PT2H` (2h) · `PT24H` (24h) · `P1D` (1 day) · `P7D` (7 days)

> ⚠️ Time triggers only work on **system-role transitions**. The delay counts from when the input place receives a token. If the human task fires first and consumes the token, the time trigger is automatically disabled.

```groovy
// t_sla_check finish PRE — route only if human hasn't finished yet
go_reviewed: f.go_reviewed, go_escalated: f.go_escalated, status: f.status;
if ((go_reviewed.value as Integer) == 0) {
  change go_escalated value { 1 }
  change status value { "SLA breached — escalated" }
  changeCaseProperty("color").about { "red" }
}
// else: go_escalated stays 0 — variable arc fires no token

// t_review finish PRE — mark reviewed so time trigger does nothing
go_reviewed: f.go_reviewed, status: f.status;
change go_reviewed value { 1 }
change status value { "Reviewed within SLA" }
```

---

## Pattern 9 — Parallel Race (first completion triggers next step)

Multiple parallel branches run simultaneously. Only the **first** completion unblocks the next step — subsequent completions are absorbed silently.

> ⚠️ `cancelTask` alone is unsafe (race condition). Use a **counter flag + dead-end place**.

```
              ┌→ [p_rev_a:0] → [review_a] ─(ref:go_final)──→ [p_merge:0] → [t_final]
[split] ──────┤                            └(ref:go_dead)───→ [p_dead:0]   (absorbed)
              └→ [p_rev_b:0] → [review_b] ─(ref:go_final)──→ [p_merge:0]
                                           └(ref:go_dead)───→ [p_dead:0]
```

```groovy
// Each reviewer's finish PRE
first_done: f.first_done, go_final: f.go_final, go_dead: f.go_dead, status: f.status;
def flag = (first_done.value as Integer) ?: 0
if (flag == 0) {
  change first_done value { 1 }
  change go_final value { 1 }; change go_dead value { 0 }
  change status value { "Done — first reviewer finished" }
} else {
  change go_final value { 0 }; change go_dead value { 1 }
}
```

```xml
<arc><id>a_rev_a_merge</id><type>regular</type><sourceId>review_a</sourceId><destinationId>p_merge</destinationId><multiplicity>0</multiplicity><reference>go_final</reference></arc>
<arc><id>a_rev_a_dead</id> <type>regular</type><sourceId>review_a</sourceId><destinationId>p_dead</destinationId> <multiplicity>0</multiplicity><reference>go_dead</reference></arc>
<!-- Same arcs for review_b -->
```

> `p_dead` has no outgoing arcs — tokens arriving there are permanently absorbed. This is intentional.

---

## Pattern 10 — Voting / Consensus (N of M)

```
              ┌→ [rev_a_p:0] → [review_a] ─┐
[split] ──────┤  [rev_b_p:0] → [review_b]  ├→ [voted:0] →(mult=2)→ [finalize]
              └→ [rev_c_p:0] → [review_c] ─┘
```

```groovy
// each review_x finish POST
vote_count: f.vote_count;
def current = (vote_count.value as Integer) ?: 0
change vote_count value { (current + 1) as Double }
if (current + 1 >= 2) {
  findTasks { qTask ->
    qTask.caseId.eq(useCase.stringId).and(qTask.transitionId.in(["review_a", "review_b", "review_c"]))
  }.each { t -> cancelTask(t) }
}
```

**Dynamic quorum** — store required count in a `number` field, use as variable arc reference on the join arc:
```xml
<!-- quorum_required is a number field set at case creation -->
<arc><id>a_join</id><type>regular</type><sourceId>p_voted</sourceId><destinationId>t_finalize</destinationId><multiplicity>0</multiplicity><reference>quorum_required</reference></arc>
```

---

## Pattern 11 — Four-Eyes Principle (dual approval, different users)

```
[start:1] → [submit] → [p0:0] → [first_approval] → [p1:0] → [second_approval] → [done:0]
```

```groovy
// first_approval finish POST
first_approver: f.first_approver;
change first_approver value { userService.loggedOrSystem.email }
```

```groovy
// second_approval assign PRE
first_approver: f.first_approver;
if (userService.loggedOrSystem.email == first_approver.value) {
  throw new java.lang.IllegalStateException("Second approver must be a different person.")
}
```

---

## Pattern 13 — Variable Arcs XOR fork (full example)

```
[p0:0] → [triage] ──(ref:toLegal)──→ [after_legal:0] → [legal_task]
                  └─(ref:toPR)────→ [after_pr:0]    → [pr_task]
```

```xml
<data type="number"><id>toLegal</id><title>To Legal</title><init>0</init></data>
<data type="number"><id>toPR</id>   <title>To PR</title>   <init>0</init></data>

<transition>
  <id>triage</id><x>496</x><y>208</y><label>Triage</label>
  <roleRef><id>registration_employee</id><logic><perform>true</perform></logic></roleRef>
  <dataGroup>
    <id>triage_group</id><cols>2</cols><layout>grid</layout><title>Triage</title>
    <dataRef>
      <id>legal_required</id>
      <logic><behavior>editable</behavior><behavior>required</behavior></logic>
      <layout><x>0</x><y>0</y><rows>1</rows><cols>2</cols><template>material</template><appearance>outline</appearance></layout>
    </dataRef>
  </dataGroup>
  <event type="finish"><id>on_triage_finish</id>
    <actions phase="pre">
      <action id="1"><![CDATA[
        toLegal: f.toLegal, toPR: f.toPR, legal_required: f.legal_required;
        if (legal_required.value == "yes") { change toLegal value { 1 } change toPR value { 0 } }
        else                               { change toLegal value { 0 } change toPR value { 1 } }
      ]]></action>
    </actions>
  </event>
</transition>

<place><id>after_legal</id><x>688</x><y>112</y><label>After Legal</label><tokens>0</tokens><static>false</static></place>
<place><id>after_pr</id>   <x>688</x><y>304</y><label>After PR</label>   <tokens>0</tokens><static>false</static></place>

<arc><id>arc_triage_legal</id><type>regular</type><sourceId>triage</sourceId><destinationId>after_legal</destinationId><multiplicity>0</multiplicity><reference>toLegal</reference></arc>
<arc><id>arc_triage_pr</id>   <type>regular</type><sourceId>triage</sourceId><destinationId>after_pr</destinationId>   <multiplicity>0</multiplicity><reference>toPR</reference></arc>
```

---

## Pattern 14 — Systematic Tasks (code-driven routing)

```
[p0:0] → [route_to_legal, sys] → [legal_p:0] → [legal_task]
       → [route_to_pr, sys]    → [pr_p:0]    → [pr_task]
```

Both system tasks share `p0` as input — only the one fired by the action consumes the token.

```xml
<transition>
  <id>route_to_legal</id><x>496</x><y>112</y><label>To Legal</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
</transition>
<transition>
  <id>route_to_pr</id><x>496</x><y>304</y><label>To PR</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
</transition>
```

```groovy
// preceding task finish POST
amount: f.amount;
if ((amount.value as Double) < 2000) {
  async.run { assignTask("route_to_legal"); finishTask("route_to_legal") }
} else {
  async.run { assignTask("route_to_pr"); finishTask("route_to_pr") }
}
```

**System task chain — each task fires the next:**
```groovy
// system_task_1 finish POST
async.run {
  def t = findTask { qTask ->
    qTask.transitionId.eq("system_task_2").and(qTask.caseId.eq(useCase.stringId))
  }
  if (t) { assignTask(t, userService.loggedOrSystem); finishTask(t, userService.loggedOrSystem) }
}
```

---

## Pattern 15 — taskRef (embedded form panel)

```xml
<data type="taskRef"><id>form_ref</id><title/><init>form_task</init></data>

<transition>
  <id>form_task</id><x>304</x><y>16</y><label>Form</label>
  <roleRef><id>system</id><logic><perform>true</perform></logic></roleRef>
  <dataGroup>
    <id>form_group</id><cols>2</cols><layout>grid</layout><title>Request</title>
    <dataRef>
      <id>applicant_name</id>
      <logic><behavior>editable</behavior></logic>
      <layout><x>0</x><y>0</y><rows>1</rows><cols>2</cols><template>material</template><appearance>outline</appearance></layout>
    </dataRef>
  </dataGroup>
</transition>

<place><id>p_form</id><x>112</x><y>16</y><label>Form Place</label><tokens>1</tokens><static>false</static></place>

<arc><id>arc_form_read</id><type>read</type><sourceId>p_form</sourceId><destinationId>form_task</destinationId><multiplicity>1</multiplicity></arc>
```

> ⚠️ `taskRef` target (`form_task`) must be permanently alive — always on a `read` arc, never consumed by normal flow. `p_form` can have `tokens=1` (always enabled from case start).

Embed in any human task as `visible`:
```xml
<dataRef>
  <id>form_ref</id>
  <logic><behavior>visible</behavior></logic>
  <layout><x>0</x><y>4</y><rows>1</rows><cols>4</cols><template>material</template><appearance>outline</appearance></layout>
</dataRef>
```

Bootstrap if form_task needs to be fire-and-forget (not on a static read arc):
```xml
<caseEvents>
  <event type="create"><id>on_create</id>
    <actions phase="post">
      <action id="1"><![CDATA[
        async.run { assignTask("form_task"); finishTask("form_task") }
      ]]></action>
    </actions>
  </event>
</caseEvents>
```

---

## Pattern 15b — caseRef (linked case tracker)

`caseRef` holds a list of case string IDs and renders them as linked case panels.

```xml
<data type="caseRef">
  <id>child_cases</id>
  <title>Related Cases</title>
</data>
```

Set dynamically via action (e.g. when creating child cases):
```groovy
child_cases: f.child_cases;
def child = createCase("child_process_id", "Child: ${useCase.title}", "blue")
change child_cases value { (child_cases.value ?: []) + child.stringId }
```

**Reactive cross-process pattern** — child writes to parent field, parent's `set` event reacts:
```groovy
// Child process: Register Invoice finish POST
invoice_id: f.invoice_id, parent_order_id: f.parent_order_id;
def parentCase = findCase({ it._id.eq(parent_order_id.value) })
setData("t1", parentCase, ["new_invoice_id": ["value": invoice_id.value, "type": "text"]])

// Parent process: data event set on new_invoice_id
invoice_approvals: f.invoice_approvals, children_invoice_cases: f.children_invoice_cases, new_invoice_id: f.new_invoice_id;
if (new_invoice_id.value !in (children_invoice_cases.value ?: [])) {
  change children_invoice_cases value { (children_invoice_cases.value ?: []) + new_invoice_id.value }
}
// Refresh taskRef to show new child task
change invoice_approvals value { /* list of task string IDs for all child approvals */ }
```

---

## Pattern 16 — Persistent Detail View (read arc)

```
[submit] ──→ [p_detail:0] ──(read)──→ [detail_view]   (always visible, token not consumed)
```

```xml
<place><id>p_detail</id><x>304</x><y>16</y><label>Detail</label><tokens>0</tokens><static>false</static></place>

<arc><id>arc_sub_det</id>  <type>regular</type> <sourceId>submit</sourceId>   <destinationId>p_detail</destinationId>  <multiplicity>1</multiplicity></arc>
<arc><id>arc_det_read</id> <type>read</type>    <sourceId>p_detail</sourceId> <destinationId>detail_view</destinationId><multiplicity>1</multiplicity></arc>
```

Status updates must be in `phase="pre"` of the transitions that change state — so the detail view reflects the new state atomically at the moment the task completes:
```groovy
// any task's finish PRE
status: f.status;
change status value { "Processing" }
```

Progressively reveal fields in the detail view as the process advances:
```groovy
// later task's finish PRE
t_detail: t.detail_view,
result_field: f.result_field;
make result_field, visible on t_detail when { true }
```

---

---

## Inter-Process Communication Patterns

### IPC-1 — Parent creates child case and gets its first task immediately

```groovy
// parent task finish POST (or button set PRE)
persons: f.persons, insured_persons_ref: f.insured_persons_ref;

def newCase = createCase(workspace + "child_process")   // minimal form — no title/color needed
// Get the first task of the new case directly without a separate findTask query
def firstTaskId = newCase.tasks.find { it.transition == "t1" }?.task
def firstTask   = findTask({ it._id.eq(firstTaskId) })
assignTask(firstTask)   // assign to current user

// Pre-fill a field in the child immediately
setData("t1", newCase, ["id_parent": ["value": useCase.stringId, "type": "text"]])

// Track child case and task in parent fields
change persons             value { persons.value + newCase.stringId }
change insured_persons_ref value { insured_persons_ref.value + firstTaskId }
```

> ⚠️ `workspace` is eTask-specific. On standalone NAE use the literal process ID string (e.g. `"child_process"`).

---

### IPC-2 — Child notifies parent via setData → parent reacts via data event set

**Step 1 — Child writes to parent field (child process, Register task finish POST):**
```groovy
invoice_id: f.invoice_id, parent_order_id: f.parent_order_id;
def parentCase = findCase({ it._id.eq(parent_order_id.value) })
setData("t1", parentCase, ["new_invoice_id": ["value": invoice_id.value, "type": "text"]])
```

**Step 2 — Parent reacts (parent process, data event set on `new_invoice_id` field):**
```groovy
invoice_approvals: f.invoice_approvals,
children_invoice_cases: f.children_invoice_cases,
new_invoice_id: f.new_invoice_id;

if (new_invoice_id.value !in (children_invoice_cases.value ?: [])) {
    change children_invoice_cases value { (children_invoice_cases.value ?: []) + new_invoice_id.value }
}
// Refresh taskRef to show all child approval tasks as embedded panels
change invoice_approvals value {
    findTasks { it.caseId.in(children_invoice_cases.value).and(it.transitionId.eq("t2")) }
        ?.collect { it.stringId }
}
```

**`new_invoice_id` data field with the set event:**
```xml
<data type="text">
  <id>new_invoice_id</id><title>New Invoice ID</title>
  <event type="set">
    <id>new_invoice_id_set</id>
    <actions phase="post">
      <action id="N"><![CDATA[
        invoice_approvals: f.invoice_approvals,
        children_invoice_cases: f.children_invoice_cases,
        new_invoice_id: f.new_invoice_id;
        if (new_invoice_id.value !in (children_invoice_cases.value ?: [])) {
          change children_invoice_cases value { (children_invoice_cases.value ?: []) + new_invoice_id.value }
        }
        change invoice_approvals value {
          findTasks { it.caseId.in(children_invoice_cases.value).and(it.transitionId.eq("t2")) }
            ?.collect { it.stringId }
        }
      ]]></action>
    </actions>
  </event>
</data>
```

---

### IPC-3 — Populate enumeration_map options from cases of another process

Used to let the user select a parent case by name/ID from a dropdown at task assign time.

```groovy
// task assign PRE
parent_order_id: f.parent_order_id;
def options = findCases { it.processIdentifier.eq(workspace + "order") }
    .collectEntries { [(it.stringId): "Order: " + it.stringId] }
change parent_order_id options { options }
```

```xml
<data type="enumeration_map">
  <id>parent_order_id</id>
  <title>Parent Order</title>
  <!-- options populated dynamically at assign time — no static <options> needed -->
</data>
```

---

### IPC-4 — caseRef with allowedNets

**Option A — static XML (process ID known at design time):**
```xml
<data type="caseRef">
  <id>child_cases</id><title>Child Cases</title>
  <allowedNets>
    <allowedNet>YOUR_SPACE_NAME/child_process</allowedNet>
  </allowedNets>
</data>
```

**Option B — dynamic action (process ID computed at runtime):**
```groovy
// caseEvents create post
child_cases: f.child_cases, process_id: f.process_id;
change process_id value { workspace + "child_process" }
change child_cases allowedNets { [process_id.value] }
```

---

### IPC-5 — Delete child cases programmatically (button-driven)

```groovy
// delete button set PRE
persons_ref: f.persons_ref, case_ref: f.case_ref, period: f.period;

persons_ref.value.each { taskId ->
    def task    = findTask({ it._id.eq(taskId) })
    def theCase = findCase { it._id.eq(task.caseId) }
    if (theCase.getFieldValue("delete_flag") == true) {
        change persons_ref value { persons_ref.value - taskId }
        change case_ref    value { case_ref.value - theCase.stringId }
        workflowService.deleteCase(theCase.stringId)
    }
}
// Recalculate derived value after deletion
change period value { period.value * persons_ref.value.size() }
```

---

## Button Field Pattern

Inline action button — fires immediately on click without finishing the task.

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
        rows: f.rows, count: f.count;
        def newCase = createCase(workspace + "row_process")
        def taskId  = newCase.tasks.find { it.transition == "t1" }?.task
        def task    = findTask({ it._id.eq(taskId) })
        assignTask(task)
        change rows  value { rows.value + taskId }
        change count value { rows.value.size() as Double }
      ]]></action>
    </actions>
  </event>
</data>
```

Reference in dataGroup (always `editable`):
```xml
<dataRef>
  <id>add_row</id>
  <logic><behavior>editable</behavior></logic>
  <layout><x>5</x><y>1</y><rows>1</rows><cols>1</cols><template>material</template><appearance>outline</appearance></layout>
</dataRef>
```

Key rules:
- `<init>1</init>` is required — button will not render without it
- `<placeholder>` = button label; `<title>` typically empty
- `phase="pre"` for synchronous inline actions visible immediately
- Pair Add button with Delete button for row management UIs



**Status + email:**
```groovy
// finish PRE
status: f.status;
change status value { "Approved" }
```
```groovy
// finish POST
email: f.email, status: f.status;
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

**Auto-trigger on case create:**
```xml
<caseEvents>
  <event type="create"><id>on_create</id>
    <actions phase="post">
      <action id="1"><![CDATA[
        async.run { assignTask("init_task"); finishTask("init_task") }
      ]]></action>
    </actions>
  </event>
</caseEvents>
```

**Section divider:**
```xml
<data type="i18n"><id>div_decision</id><title>Decision</title><init>Decision</init></data>
```
```xml
<dataRef>
  <id>div_decision</id>
  <logic><behavior>editable</behavior></logic>
  <layout><x>0</x><y>3</y><rows>1</rows><cols>4</cols><template>material</template><appearance>outline</appearance></layout>
  <component><name>divider</name></component>
</dataRef>
```

**Case color by outcome:**
```groovy
// finish POST
changeCaseProperty("color").about { "green" }  // approved
// changeCaseProperty("color").about { "red" }  // rejected
```

**Dynamic options from external API:**
```groovy
country: f.country, city: f.city;
def conn = (java.net.HttpURLConnection) new java.net.URL(
  "https://api.example.com/cities?country=${country.value}"
).openConnection()
conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(10000)
def result = new groovy.json.JsonSlurper().parseText(conn.getInputStream().getText("UTF-8"))
change city options { result.collectEntries { [(it.code): it.name] } }
```

**External API + store result (with LLM JSON fence stripping):**
```groovy
openai_api_key: f.openai_api_key, prompt_field: f.prompt_field, llm_result: f.llm_result;
async.run {
    def conn = (java.net.HttpURLConnection) new java.net.URL(
            "https://api.openai.com/v1/chat/completions"
    ).openConnection()
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer ${openai_api_key.value}")
    conn.setDoOutput(true); conn.setConnectTimeout(15_000); conn.setReadTimeout(60_000)
    def payload = groovy.json.JsonOutput.toJson([
            model: "gpt-4o-mini",
            messages: [[role: "user", content: prompt_field.value ?: ""]]
    ])
    conn.outputStream.write(payload.getBytes("UTF-8"))
    def responseBody = ""
    try { responseBody = conn.inputStream.getText("UTF-8") }
    catch (java.lang.Exception e) { responseBody = conn.errorStream?.getText("UTF-8") ?: '{"error":"unreachable"}' }
    def parsed = new groovy.json.JsonSlurper().parseText(responseBody)
    def content = parsed?.choices?.getAt(0)?.message?.content ?: "{}"
    // Strip markdown fences safely (use single-quoted strings for regex)
    def normalized = content
            .replaceAll('(?m)^```(?:json)?\\s*', '')
            .replaceAll('(?m)^```\\s*$', '')
            .trim()
    def t = findTask { qTask -> qTask.transitionId.eq("result_task").and(qTask.caseId.eq(useCase.stringId)) }
    if (t) setData(t, [llm_result: [value: normalized, type: "text"]])
}
```

> ⚠️ Declare `openai_api_key` as a `text` data field with `<value>sk-YOUR-KEY-HERE</value>`. Mark it `hidden` on all user-facing transitions. Replace the value before importing.