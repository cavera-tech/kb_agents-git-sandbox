# kb-agents

**Agents that work your tickets, from your knowledge base, inside your git.**

You assign a ticket to an agent the way you assign it to a colleague. It reads
the ticket, reads the knowledge base, works on a branch, and opens a pull
request. A person merges. The agent never does.

No new tool to learn, no dashboard, no queue of ours. If your team uses issues
and pull requests, it already uses this.

---

## How it works

**Assign an agent** and it delivers. It creates `auto/issue-N`, commits its work
there, opens one pull request, and reports on the ticket.

**Mention an agent** and it answers. It reads the ticket and the branch, replies
in the thread, and changes nothing — it has no shell, so it cannot commit even
if asked to. This is how you resolve an ambiguity before it becomes a wrong
deliverable in three documents downstream.

Nothing happens until a person calls an agent, and that call is the
supervision.

## The team

Each agent is a GitHub account with its own profile. Commits, comments and pull
requests carry its own name, so who did what is in the history and not in a
report.

| Agent | Delivers |
|---|---|
| Business analyst | `docs/functional/issue-N.md` — the behaviour, the rules, the edge cases, in business terms |
| Architect | `docs/architecture/issue-N.md` — components, data, decisions and why |
| Developer | the implementation, against both documents |

The roster is configuration, not code. A client who wants one developer gets
one; a client who wants four roles gets four. Adding an agent is a directory, an
account and a token.

## One ticket at a time

The agents run on your own machine, on one GPU, and a single runner executes one
job at a time. A second call waits in GitHub's queue and starts when the first
finishes — the queue survives a reboot, and nothing is lost.

That is a deliberate constraint, not a limit we forgot to raise: work is
serialised so that a run is reproducible and the machine is never oversubscribed.

## The knowledge base

The agents do not answer from what a model remembers. They query a knowledge
base built from the target system's own documentation, and every claim carries
where it came from.

For Findur/Endur that is 360,000 indexed passages of product documentation,
4,500 database schema entries, and a graph of tables, keys, foreign keys and API
classes. The agent names the table and the column, and cites the page.

A different domain is a different pack. The agents, the flow and the git line do
not change.

## What runs where

Your code and your knowledge base stay on your machine. The model runs there
too — local inference, on your GPU. What travels to GitHub is what you already
put there: the issue, the branch, the pull request.

```
your repository   assignment or mention  →  job on your runner
your server       the agents · the knowledge base · the model
```

## Proof, in this repository

Everything here is synthetic — no client material, no real names, no real data.
The repository is public so that branch protection applies to it, and branch
protection is half of what the flow has to prove.

Two runs of the same ticket, months apart in maturity:

| | Issue #1 | Issue #6 |
|---|---|---|
| Functional document | yes | yes |
| Architecture document | yes | yes |
| Implementation | 5 Java files | **1** |
| Answering a question without delivering | not possible | **55 seconds, branch untouched** |

Read them, and the pull requests they opened. Both are agents' work, unedited.

## What this does not do

It does not merge. It does not touch a branch that is not its ticket's. It does
not act unless a person calls it. And when the knowledge base does not hold an
answer, the agent writes the open question instead of inventing a fact.
