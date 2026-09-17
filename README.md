# kb-agents sandbox

A test bed for the kb-agents flow, and nothing else. **Everything here is
synthetic.** No client material, no real names, no real data: the repository is
public so that branch protection exists on it, and branch protection is half of
what the flow has to prove.

## What is being tested

A person assigns an agent's account to an issue, or mentions it in a comment. A
GitHub Actions workflow runs the job on a self-hosted runner on the LLM server.
The agent reads the ticket, reads the knowledge base, and delivers on the
ticket branch `auto/issue-N`, opening one pull request. A person merges. The
agent never does.

The parts live in `cavera-tech/kb-agents-git`: the command, the profiles, the
KB MCP server and the generated workflow.

## What it proves

| Question | How this repository answers it |
|---|---|
| Does the call reach the machine? | the job appears in Actions when an agent is assigned or mentioned |
| Does the agent only touch its own branch? | branch protection refuses anything else |
| Is the merge a person's? | the pull request waits for one |
| Does a mention by the wrong person do nothing? | the run ends without a tracking comment |

## What is not here yet

The workflow file arrives with the runner (issue #5 of the main repository).
Until a runner is registered, a job would queue for 24 hours and be cancelled,
so the file is added when there is something to run it.
