# RaasPal Internal Ops backend

Spring Boot 3.4.5 · Java 21 · PostgreSQL. Build with **`sh mvnw`**, not `./mvnw`.

## Read the vault before exploring the code

Working memory for this project lives in the **`RaasPal-Vault`** repo, checked out beside this one:

```
../RaasPal-Vault/now.md          # relative to this repo
/Users/kusk/Desktop/RassPal/RaasPal-Vault/now.md   # where it sits on the original workspace
```

If it is not beside this repo, clone it (it is private) or ask where it lives — do not skip it.

**Read `now.md` first.** It is ~100 lines and carries the current HEADs, the
applied-vs-shipped migration version, the local databases, the active feature, the open items and
the traps that have already cost people time. Reading it first is almost always cheaper and more
accurate than re-deriving the same facts from files and git history.

**Do not read `index.md` or `history.md` end to end.** They are 600+ and 2,900+ lines. Grep them
for a heading (`grep -n "^#" `) and read the section you need, when you need the reasoning behind a
specific decision.

**Write back as you go, not at the end of the session:**

| When | Where | How |
|---|---|---|
| A fact in `now.md` stops being true — a merge, a deploy, a new migration, an item closed | `now.md` | Edit that line in place; move the "Verified" date |
| Something ships, is decided, or breaks and is fixed | `history.md` | Append a dated entry at the bottom |
| Long-term state changes — a new service, schema area, hosting | `index.md` | Edit the relevant section |

If a session ends with something learned that the next session would have to rediscover, it goes in
the vault. Something that mattered only to one conversation does not.

**The vault has one branch, `main`.** `git pull` **before** you start writing, then commit, pull
again, and push — in that order. `history.md` is append-only, so a conflict there keeps **both**
sides in date order. In `now.md` and `index.md`, where both sides describe the same current state,
keep the one that was actually **verified**, and say in the entry which it was.

**No secrets in the vault.** Config goes in by variable name with a placeholder value. It does hold
customer names and internal architecture, so the repo stays private.
